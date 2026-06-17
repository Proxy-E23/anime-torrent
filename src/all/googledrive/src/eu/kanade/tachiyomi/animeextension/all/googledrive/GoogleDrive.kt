package eu.kanade.tachiyomi.animeextension.all.googledrive

import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import aniyomi.lib.googledriveextractor.GoogleDriveExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.commonEmptyRequestBody
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.ProtocolException
import org.jsoup.nodes.Document
import uy.kohesive.injekt.injectLazy
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale

class GoogleDrive :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Google Drive"

    override val id = 4222017068256633289

    override var baseUrl = "https://drive.google.com"

    override val lang = "all"

    override val supportsLatest = false

    private val json: Json by injectLazy()

    private val preferences by getPreferencesLazy()

    private val getHeaders = headers.newBuilder().apply {
        add("Accept", "*/*")
        add("Connection", "keep-alive")
        add("Cookie", getCookie("https://drive.google.com"))
        add("Host", "drive.google.com")
    }.build()

    private var nextPageToken: String? = ""

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

    private var cachedAnimes: List<SAnime>? = null

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    private fun migrateLegacyPrefs() {
        val legacy = preferences.getString("domain_list", "") ?: return
        if (legacy.isBlank()) return

        legacy.split(";")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { url ->
                val match = DRIVE_FOLDER_REGEX.matchEntire(url) ?: return@forEach
                val name = match.groups["name"]?.value
                    ?.substringAfter("[")?.substringBeforeLast("]")
                    ?: match.groups["id"]!!.value
                GoogleDrivePreferences.addEntry(preferences, name, url)
            }

        preferences.edit().remove("domain_list").apply()
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        cachedAnimes?.let { return AnimesPage(it, false) }

        migrateLegacyPrefs()

        val entries = GoogleDrivePreferences.getEntries(preferences)
        if (entries.isEmpty()) return AnimesPage(emptyList(), false)

        val animeList = mutableListOf<SAnime>()

        entries.forEach { entry ->
            val match = DRIVE_FOLDER_REGEX.matchEntire(entry.url) ?: return@forEach
            val folderId = match.groups["id"]!!.value
            val recurDepth = match.groups["depth"]?.value ?: ""
            val folderUrl = "https://drive.google.com/drive/folders/$folderId$recurDepth"

            val httpResponse = try {
                client.newCall(GET(folderUrl, headers = getHeaders)).execute()
            } catch (a: ProtocolException) {
                return@forEach
            }

            if (httpResponse.code == 404) {
                httpResponse.close()
                animeList.add(
                    SAnime.create().apply {
                        title = "❌ ${entry.name} (Carpeta eliminada)"
                        thumbnail_url = "https://http.cat/404"
                        url = LinkData(entry.url, "error404", LinkDataInfo(entry.name, entry.url)).toJsonString()
                        status = SAnime.UNKNOWN
                        description = "Esta carpeta fue eliminada o ya no está disponible.\n\nPara eliminarla, copia la línea de abajo y pégala en Ajustes → Eliminar enlace:\n\n${entry.name}::${entry.url}"
                    },
                )
                return@forEach
            }

            val driveDocument = httpResponse.asJsoup()

            val hasKey = driveDocument.select("script").any { KEY_REGEX.find(it.data()) != null }
            if (!hasKey) return@forEach

            var pageToken: String? = ""
            var shouldBreak = false
            while (pageToken != null && !shouldBreak) {
                try {
                    val response = client.newCall(
                        createPost(driveDocument, folderId, pageToken),
                    ).execute()

                    val bodyText = response.body.string()
                    val jsonText = JSON_REGEX.find(bodyText)?.groupValues?.get(1)
                    if (jsonText == null) {
                        shouldBreak = true
                        return@forEach
                    }

                    val parsed = json.decodeFromString<PostResponse>(jsonText)

                    if (parsed.items == null) {
                        shouldBreak = true
                        return@forEach
                    }

                    val folders = parsed.items.filter {
                        it.mimeType.endsWith(".folder") ||
                            (it.mimeType == "application/vnd.google-apps.shortcut" && it.shortcutDetails?.targetMimeType?.endsWith(".folder") == true)
                    }
                    val videos = parsed.items.filter {
                        it.mimeType.startsWith("video") ||
                            (it.mimeType == "application/vnd.google-apps.shortcut" && it.shortcutDetails?.targetMimeType?.startsWith("video") == true)
                    }

                    if (folders.isEmpty() && videos.isNotEmpty()) {
                        animeList.add(
                            SAnime.create().apply {
                                title = entry.name
                                url = LinkData(folderUrl, "multi").toJsonString()
                                thumbnail_url = ""
                            },
                        )
                    } else {
                        folders.forEach { item ->
                            val itemId = if (item.mimeType == "application/vnd.google-apps.shortcut") {
                                item.shortcutDetails?.targetId ?: item.id
                            } else {
                                item.id
                            }
                            animeList.add(
                                SAnime.create().apply {
                                    title = item.title
                                    url = LinkData(
                                        "https://drive.google.com/drive/folders/$itemId$recurDepth",
                                        "multi",
                                    ).toJsonString()
                                    thumbnail_url = ""
                                },
                            )
                        }
                        videos.forEach { item ->
                            val itemId = if (item.mimeType == "application/vnd.google-apps.shortcut") {
                                item.shortcutDetails?.targetId ?: item.id
                            } else {
                                item.id
                            }
                            animeList.add(
                                SAnime.create().apply {
                                    title = item.title
                                    url = LinkData(
                                        "https://drive.google.com/uc?id=$itemId",
                                        "single",
                                        LinkDataInfo(item.title, item.fileSize?.toLongOrNull()?.let { formatBytes(it) } ?: ""),
                                    ).toJsonString()
                                    thumbnail_url = ""
                                },
                            )
                        }
                    }

                    pageToken = parsed.nextPageToken
                } catch (e: Exception) {
                    shouldBreak = true
                }
            }
        }

        cachedAnimes = animeList
        return AnimesPage(animeList, false)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // =============================== Search ===============================

    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val urlFilter = filterList.find { it is URLFilter } as URLFilter
        val nameFilter = filterList.find { it is NameFilter } as NameFilter

        return if (urlFilter.state.isNotBlank()) {
            val match = DRIVE_FOLDER_REGEX.matchEntire(urlFilter.state.trim())
                ?: throw Exception("URL de Google Drive inválida")

            val folderId = match.groups["id"]!!.value
            val recurDepth = match.groups["depth"]?.value ?: ""
            val folderUrl = "https://drive.google.com/drive/folders/$folderId$recurDepth"

            val entryName = nameFilter.state.trim().takeIf { it.isNotBlank() }
                ?: match.groups["name"]?.value?.substringAfter("[")?.substringBeforeLast("]")
                ?: folderId

            GoogleDrivePreferences.addEntry(preferences, entryName, folderUrl)
            cachedAnimes = null

            addSinglePage(folderUrl)
        } else if (query.isNotBlank()) {
            val all = getPopularAnime(page)
            AnimesPage(all.animes.filter { it.title.contains(query, ignoreCase = true) }, false)
        } else {
            getPopularAnime(page)
        }
    }

    // ============================== FILTERS ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Agregar carpeta de Google Drive"),
        URLFilter(),
        NameFilter(),
    )

    private class URLFilter : AnimeFilter.Text("URL de carpeta")

    private class NameFilter : AnimeFilter.Text("Nombre (opcional)")

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        val parsed = try {
            json.decodeFromString<LinkData>(anime.url)
        } catch (e: Exception) {
            return GET(baseUrl, headers = getHeaders)
        }
        if (parsed.type == "error404") return GET(baseUrl, headers = getHeaders)
        return GET(parsed.url, headers = getHeaders)
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val parsed = try {
            json.decodeFromString<LinkData>(anime.url)
        } catch (e: Exception) {
            return anime
        }

        // FIX: entrada de error 404 — devolver tal cual para preservar la descripcion
        if (parsed.type == "error404") return anime

        if (parsed.type == "single") return anime

        val folderId = DRIVE_FOLDER_REGEX.matchEntire(parsed.url)!!.groups["id"]!!.value

        val driveDocument = try {
            client.newCall(GET(parsed.url, headers = getHeaders)).execute().asJsoup()
        } catch (a: ProtocolException) {
            null
        } ?: return anime

        // Get cover
        val coverResponse = client.newCall(
            createPost(driveDocument, folderId, nextPageToken, searchReqWithType(folderId, "cover", IMAGE_MIMETYPE)),
        ).execute().parseAs<PostResponse> { JSON_REGEX.find(it)!!.groupValues[1] }

        coverResponse.items?.firstOrNull()?.let {
            anime.thumbnail_url = "https://drive.google.com/uc?id=${it.id}"
        }

        // Get details
        val detailsResponse = client.newCall(
            createPost(driveDocument, folderId, nextPageToken, searchReqWithType(folderId, "details.json", "")),
        ).execute().parseAs<PostResponse> { JSON_REGEX.find(it)!!.groupValues[1] }

        detailsResponse.items?.firstOrNull()?.let {
            val newPostHeaders = getHeaders.newBuilder().apply {
                add("Content-Type", "application/x-www-form-urlencoded;charset=utf-8")
                set("Host", "drive.usercontent.google.com")
                add("Origin", "https://drive.google.com")
                add("Referer", "https://drive.google.com/")
                add("X-Drive-First-Party", "DriveWebUi")
                add("X-Json-Requested", "true")
            }.build()

            val newPostUrl = "https://drive.usercontent.google.com/uc?id=${it.id}&authuser=0&export=download"

            val newResponse = client.newCall(
                POST(newPostUrl, headers = newPostHeaders, body = commonEmptyRequestBody),
            ).execute().parseAs<DownloadResponse> { JSON_REGEX.find(it)!!.groupValues[1] }

            val downloadHeaders = headers.newBuilder().apply {
                add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                add("Connection", "keep-alive")
                add("Cookie", getCookie("https://drive.usercontent.google.com"))
                add("Host", "drive.usercontent.google.com")
            }.build()

            client.newCall(
                GET(newResponse.downloadUrl, headers = downloadHeaders),
            ).execute().parseAs<DetailsJson>().let { t ->
                t.title?.let { anime.title = it }
                t.author?.let { anime.author = it }
                t.artist?.let { anime.artist = it }
                t.description?.let { anime.description = it }
                t.genre?.let { anime.genre = it.joinToString(", ") }
                t.status?.let { anime.status = it.toIntOrNull() ?: SAnime.UNKNOWN }
            }
        }

        return anime
    }

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val episodeList = mutableListOf<SEpisode>()
        val parsed = json.decodeFromString<LinkData>(anime.url)

        // FIX: entrada de error 404 — sin episodios
        if (parsed.type == "error404") return emptyList()

        if (parsed.type == "single") {
            return listOf(
                SEpisode.create().apply {
                    name = "Video"
                    scanlator = parsed.info!!.size
                    url = parsed.url
                    episode_number = 1F
                    date_upload = -1L
                },
            )
        }

        val match = DRIVE_FOLDER_REGEX.matchEntire(parsed.url)!!
        val maxRecursionDepth = match.groups["depth"]?.let {
            it.value.substringAfter("#").substringBefore(",").toInt()
        } ?: 2
        val (start, stop) = match.groups["range"]?.let {
            it.value.substringAfter(",").split(",").map { it.toInt() }
        } ?: listOf(null, null)

        fun traverseFolder(folderUrl: String, path: String, recursionDepth: Int = 0) {
            if (recursionDepth == maxRecursionDepth) return

            val folderId = DRIVE_FOLDER_REGEX.matchEntire(folderUrl)!!.groups["id"]!!.value

            val driveDocument = try {
                client.newCall(GET(folderUrl, headers = getHeaders)).execute().asJsoup()
            } catch (a: ProtocolException) {
                throw Exception("Unable to get items, check webview")
            }

            if (driveDocument.selectFirst("title:contains(Error 404 \\(Not found\\))") != null) return

            var pageToken: String? = ""
            var counter = 1

            while (pageToken != null) {
                val response = client.newCall(
                    createPost(driveDocument, folderId, pageToken),
                ).execute()

                val parsed = response.parseAs<PostResponse> {
                    JSON_REGEX.find(it)!!.groupValues[1]
                }

                if (parsed.items == null) throw Exception("Failed to load items, please log in through webview")

                parsed.items.forEachIndexed { index, it ->
                    val isVideo = it.mimeType.startsWith("video") ||
                        (it.mimeType == "application/vnd.google-apps.shortcut" && it.shortcutDetails?.targetMimeType?.startsWith("video") == true)
                    val itemId = if (it.mimeType == "application/vnd.google-apps.shortcut") {
                        it.shortcutDetails?.targetId ?: it.id
                    } else {
                        it.id
                    }

                    if (isVideo) {
                        val size = it.fileSize?.toLongOrNull()?.let { formatBytes(it) } ?: ""
                        val pathName = path

                        if (start != null && maxRecursionDepth == 1 && counter < start) {
                            counter++
                            return@forEachIndexed
                        }
                        if (stop != null && maxRecursionDepth == 1 && counter > stop) return

                        val epNumber = (index + 1).toFloat()
                        episodeList.add(
                            SEpisode.create().apply {
                                name = if (GoogleDrivePreferences.showFilename(preferences)) {
                                    it.title
                                } else {
                                    "Episodio $counter"
                                }
                                url = "https://drive.google.com/uc?id=$itemId"
                                episode_number = ITEM_NUMBER_REGEX.find(it.title)?.groupValues?.get(1)
                                    ?.toFloatOrNull() ?: epNumber
                                date_upload = it.modifiedDate?.let { date ->
                                    runCatching { dateFormat.parse(date)?.time }.getOrNull()
                                } ?: -1L
                                scanlator = if (pathName.isBlank()) {
                                    size.takeIf { it.isNotBlank() } ?: ""
                                } else {
                                    if (size.isNotBlank()) "/$pathName • $size" else "/$pathName"
                                }
                            },
                        )
                        counter++
                    }

                    val isFolder = it.mimeType.endsWith(".folder") ||
                        (it.mimeType == "application/vnd.google-apps.shortcut" && it.shortcutDetails?.targetMimeType?.endsWith(".folder") == true)
                    val itemFolderId = if (it.mimeType == "application/vnd.google-apps.shortcut") {
                        it.shortcutDetails?.targetId ?: it.id
                    } else {
                        it.id
                    }

                    if (isFolder) {
                        traverseFolder(
                            "https://drive.google.com/drive/folders/$itemFolderId",
                            if (path.isEmpty()) it.title else "$path/${it.title}",
                            recursionDepth + 1,
                        )
                    }
                }

                pageToken = parsed.nextPageToken
            }
        }

        traverseFolder(parsed.url, "")

        return episodeList.reversed()
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> = GoogleDriveExtractor(client, headers).videosFromUrl(episode.url.substringAfter("?id="))

    // ============================= Utilities ==============================

    private fun addSinglePage(folderUrl: String): AnimesPage {
        val match = DRIVE_FOLDER_REGEX.matchEntire(folderUrl) ?: throw Exception("Invalid drive url")
        val recurDepth = match.groups["depth"]?.value ?: ""

        val anime = SAnime.create().apply {
            title = match.groups["name"]?.value?.substringAfter("[")?.substringBeforeLast("]")
                ?: "Folder"
            url = LinkData(
                "https://drive.google.com/drive/folders/${match.groups["id"]!!.value}$recurDepth",
                "multi",
            ).toJsonString()
            thumbnail_url = ""
        }
        return AnimesPage(listOf(anime), false)
    }

    private fun createPost(
        document: Document,
        folderId: String,
        pageToken: String?,
        getMultiFormPath: (String, String, String) -> String = { folderIdStr, nextPageTokenStr, keyStr ->
            defaultGetRequest(folderIdStr, nextPageTokenStr, keyStr)
        },
    ): Request {
        val keyScript = document.select("script").firstOrNull { script ->
            KEY_REGEX.find(script.data()) != null
        }?.data() ?: ""
        val key = KEY_REGEX.find(keyScript)?.groupValues?.get(1) ?: ""

        val versionScript = document.select("script").firstOrNull { script ->
            VERSION_REGEX.find(script.data()) != null
        }?.data() ?: ""
        val driveVersion = VERSION_REGEX.find(versionScript)?.groupValues?.get(1) ?: ""

        val sapisid =
            client.cookieJar.loadForRequest("https://drive.google.com".toHttpUrl()).firstOrNull {
                it.name == "SAPISID" || it.name == "__Secure-3PAPISID"
            }?.value ?: ""

        val requestUrl = getMultiFormPath(folderId, pageToken ?: "", key)
        val body = """--$BOUNDARY
                    |content-type: application/http
                    |content-transfer-encoding: binary
                    |
                    |GET $requestUrl
                    |X-Goog-Drive-Client-Version: $driveVersion
                    |authorization: ${generateSapisidhashHeader(sapisid)}
                    |x-goog-authuser: 0
                    |
                    |--$BOUNDARY--""".trimMargin("|")
            .toRequestBody("multipart/mixed; boundary=\"$BOUNDARY\"".toMediaType())

        val postUrl = buildString {
            append("https://clients6.google.com/batch/drive/v2internal")
            append("?${'$'}ct=multipart/mixed; boundary=\"$BOUNDARY\"")
            append("&key=$key")
        }

        val postHeaders = headers.newBuilder().apply {
            add("Content-Type", "text/plain; charset=UTF-8")
            add("Origin", "https://drive.google.com")
            add("Cookie", getCookie("https://drive.google.com"))
        }.build()

        return POST(postUrl, body = body, headers = postHeaders)
    }

    private fun parsePage(
        request: Request,
        page: Int,
        genMultiFormReq: ((String, String, String) -> String)? = null,
    ): AnimesPage {
        val animeList = mutableListOf<SAnime>()

        val recurDepth = request.url.encodedFragment?.let { "#$it" } ?: ""

        val folderId = DRIVE_FOLDER_REGEX.matchEntire(request.url.toString())!!.groups["id"]!!.value

        val driveDocument = try {
            client.newCall(request).execute().asJsoup()
        } catch (a: ProtocolException) {
            throw Exception("Unable to get items, check webview")
        }

        if (driveDocument.selectFirst("title:contains(Error 404 \\(Not found\\))") != null) {
            return AnimesPage(emptyList(), false)
        }

        if (page == 1) nextPageToken = ""
        val post = if (genMultiFormReq == null) {
            createPost(driveDocument, folderId, nextPageToken)
        } else {
            createPost(driveDocument, folderId, nextPageToken, genMultiFormReq)
        }
        val response = client.newCall(post).execute()

        val parsed = response.parseAs<PostResponse> {
            JSON_REGEX.find(it)!!.groupValues[1]
        }

        if (parsed.items == null) throw Exception("Failed to load items, please log in through webview")
        parsed.items.forEachIndexed { index, it ->
            val isVideo = it.mimeType.startsWith("video") ||
                (it.mimeType == "application/vnd.google-apps.shortcut" && it.shortcutDetails?.targetMimeType?.startsWith("video") == true)
            val itemId = if (it.mimeType == "application/vnd.google-apps.shortcut") {
                it.shortcutDetails?.targetId ?: it.id
            } else {
                it.id
            }

            if (isVideo) {
                animeList.add(
                    SAnime.create().apply {
                        title = it.title
                        url = LinkData(
                            "https://drive.google.com/uc?id=$itemId",
                            "single",
                            LinkDataInfo(
                                it.title,
                                it.fileSize?.toLongOrNull()?.let { formatBytes(it) } ?: "",
                            ),
                        ).toJsonString()
                        thumbnail_url = ""
                    },
                )
            }

            val isFolder = it.mimeType.endsWith(".folder") ||
                (it.mimeType == "application/vnd.google-apps.shortcut" && it.shortcutDetails?.targetMimeType?.endsWith(".folder") == true)
            val itemFolderId = if (it.mimeType == "application/vnd.google-apps.shortcut") {
                it.shortcutDetails?.targetId ?: it.id
            } else {
                it.id
            }

            if (isFolder) {
                animeList.add(
                    SAnime.create().apply {
                        title = it.title
                        url = LinkData(
                            "https://drive.google.com/drive/folders/$itemFolderId$recurDepth",
                            "multi",
                        ).toJsonString()
                        thumbnail_url = ""
                    },
                )
            }
        }

        nextPageToken = parsed.nextPageToken

        return AnimesPage(animeList, nextPageToken != null)
    }

    private fun generateSapisidhashHeader(
        SAPISID: String,
        origin: String = "https://drive.google.com",
    ): String {
        val timeNow = System.currentTimeMillis() / 1000
        val sapisidhash = MessageDigest
            .getInstance("SHA-1")
            .digest("$timeNow $SAPISID $origin".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${timeNow}_$sapisidhash"
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.2f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.2f KB".format(bytes / 1_000.0)
        bytes > 1 -> "$bytes bytes"
        bytes == 1L -> "$bytes byte"
        else -> ""
    }

    private fun getCookie(url: String): String {
        val cookieList = client.cookieJar.loadForRequest(url.toHttpUrl())
        return if (cookieList.isNotEmpty()) {
            cookieList.joinToString("; ") { "${it.name}=${it.value}" }
        } else {
            ""
        }
    }

    private fun LinkData.toJsonString(): String = json.encodeToString(this)

    companion object {
        private val DRIVE_FOLDER_REGEX = Regex(
            """(?<name>\[[^\[\];]+\])?https?:\/\/(?:docs|drive)\.google\.com\/drive(?:\/[^\/]+)*?\/folders\/(?<id>[\w-]{28,})(?:\?[^;#]+)?(?<depth>#\d+(?<range>,\d+,\d+)?)?${'$'}""",
        )
        private val KEY_REGEX = Regex(""""(\w{39})"""")
        private val VERSION_REGEX = Regex(""""([^"]+web-frontend[^"]+)"""")
        private val JSON_REGEX = Regex("""(?:)\s*(\{(.+)\})\s*(?:)""", RegexOption.DOT_MATCHES_ALL)
        private const val BOUNDARY = "=====vc17a3rwnndj====="
        private val ITEM_NUMBER_REGEX = Regex(""" - (?:S\d+E)?(\d+)\b""")
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = GoogleDrivePreferences.PREF_SHOW_FILENAME
            title = "Mostrar nombre del archivo"
            summary = "Activado: muestra el nombre real del archivo.\nDesactivado: muestra \"Episodio 1\", \"Episodio 2\"…"
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)

        val folderListPref = EditTextPreference(screen.context).apply {
            key = "googledrive_folder_list"
            title = "Enlaces guardados"
            summary = "Toca para editar tus enlaces guardados"
            dialogTitle = "Enlaces guardados"
            setDialogMessage("Una entrada por línea.\n\nEjemplo:\nNombre::URL de Google Drive\n\nPara eliminar una entrada, borra la línea completa.\n\nPara ver los cambios reflejados en el catálogo, cierra y vuelve a abrir la extensión.")
            setDefaultValue("")
            setOnPreferenceChangeListener { _, _ ->
                cachedAnimes = null
                true
            }
        }.also(screen::addPreference)

        // FIX: campo para eliminar un enlace fácilmente
        lateinit var removeEntryPref: EditTextPreference
        removeEntryPref = EditTextPreference(screen.context).apply {
            key = "googledrive_remove_entry"
            title = "Eliminar enlace"
            summary = "Pega aquí la línea Nombre::URL que aparece en la descripción de la carpeta eliminada"
            dialogTitle = "Eliminar enlace"
            setDialogMessage("Copia la línea Nombre::URL desde la descripción de la entrada con error y pégala aquí para eliminarla de tus enlaces guardados.\n\nPara ver los cambios reflejados en el catálogo, cierra y vuelve a abrir la extensión.")
            setDefaultValue("")
            setOnPreferenceChangeListener { preference, newValue ->
                val lineToRemove = (newValue as String).trim()
                if (lineToRemove.isNotBlank()) {
                    val current = preferences.getString("googledrive_folder_list", "") ?: ""
                    val updated = current.lines()
                        .filter { it.trim() != lineToRemove }
                        .joinToString("\n")
                    preferences.edit().putString("googledrive_folder_list", updated).apply()
                    cachedAnimes = null

                    // FIX: actualizar también el EditTextPreference de "Enlaces guardados" para que refleje el cambio
                    folderListPref.text = updated

                    // FIX: limpiar el campo de "Eliminar enlace" tanto en preferences como en el objeto en pantalla,
                    // usando post() para que se aplique después de que termine el ciclo actual del diálogo
                    preferences.edit().putString("googledrive_remove_entry", "").commit()
                    screen.context.let {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            removeEntryPref.text = ""
                        }
                    }
                }
                true
            }
        }
        screen.addPreference(removeEntryPref)
    }
}
