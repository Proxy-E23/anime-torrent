package eu.kanade.tachiyomi.animeextension.all.collectednotes

import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
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
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.ProtocolException
import org.jsoup.nodes.Document
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale

class CollectedNotesSrc :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Collected Notes"
    override val baseUrl = "https://collectednotes.com"
    override val lang = "all"
    override val supportsLatest = false

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val preferences by getPreferencesLazy()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

    // Caché en memoria — vive mientras el proceso de la app esté activo
    private var cachedAnimes: List<SAnime>? = null

    // Headers para peticiones a Drive
    private val driveHeaders get() = headers.newBuilder().apply {
        add("Accept", "*/*")
        add("Connection", "keep-alive")
        add("Cookie", getCookie("https://drive.google.com"))
        add("Host", "drive.google.com")
    }.build()

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        cachedAnimes?.let { return AnimesPage(it, false) }

        val entries = CollectedNotesPreferences.getEntries(preferences)
        if (entries.isEmpty()) return AnimesPage(emptyList(), false)

        val animeList = mutableListOf<SAnime>()

        entries.forEach { entry ->
            val notes = fetchAllNotes(entry.sitePath)
            notes.forEach { note ->
                val hasValidLinks = DRIVE_LINK_REGEX.findAll(note.body)
                    .any { it.groupValues[2].isNotBlank() }
                if (!hasValidLinks) return@forEach

                animeList.add(
                    SAnime.create().apply {
                        title = note.title
                        url = "${entry.sitePath}::${note.path}"
                        thumbnail_url = BODY_IMAGE_REGEX.find(note.body)?.groupValues?.get(1) ?: ""
                        description = extractDescription(note.body)
                        status = if (note.body.contains("[Drive]()")) {
                            SAnime.ONGOING
                        } else {
                            SAnime.COMPLETED
                        }
                        initialized = true
                    },
                )
            }
        }

        cachedAnimes = animeList
        return AnimesPage(animeList, false)
    }

    // ============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // ============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()
    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val urlFilter = filterList.find { it is AddUrlFilter } as AddUrlFilter
        val nameFilter = filterList.find { it is AddNameFilter } as AddNameFilter
        val siteFilter = filterList.find { it is SiteFilter } as SiteFilter

        val rawUrl = urlFilter.state.trim()

        if (rawUrl.isNotBlank()) {
            val sitePath = rawUrl
                .trimEnd('/')
                .removePrefix("https://collectednotes.com/")
                .removePrefix("http://collectednotes.com/")
                .removeSuffix(".json")
                .trim()
            val resolvedName = nameFilter.state.trim().takeIf { it.isNotBlank() } ?: sitePath
            CollectedNotesPreferences.addEntry(preferences, resolvedName, "https://collectednotes.com/$sitePath")
            cachedAnimes = null
            return getPopularAnime(page)
        }

        val all = getPopularAnime(page)

        val filteredBySite = if (siteFilter.state > 0) {
            val selectedSite = CollectedNotesPreferences.getEntries(preferences)
                .getOrNull(siteFilter.state - 1)
            if (selectedSite != null) {
                all.animes.filter { it.url.startsWith("${selectedSite.sitePath}::") }
            } else {
                all.animes
            }
        } else {
            all.animes
        }

        val results = if (query.isNotBlank()) {
            filteredBySite.filter { it.title.contains(query, ignoreCase = true) }
        } else {
            filteredBySite
        }

        return AnimesPage(results, false)
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList {
        val entries = CollectedNotesPreferences.getEntries(preferences)
        val siteOptions = arrayOf("Todos los fansubs") + entries.map { it.name }.toTypedArray()

        return AnimeFilterList(
            AnimeFilter.Header("Filtrar por fansub"),
            SiteFilter(siteOptions),
            AnimeFilter.Separator(),
            AnimeFilter.Header("Agregar fansub de Collected Notes"),
            AddUrlFilter(),
            AddNameFilter(),
        )
    }

    private class SiteFilter(options: Array<String>) : AnimeFilter.Select<String>("Fansub", options)

    private class AddUrlFilter : AnimeFilter.Text("URL del sitio (collectednotes.com/usuario)")

    private class AddNameFilter : AnimeFilter.Text("Nombre del fansub (opcional)")

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        val parts = anime.url.split("::", limit = 2)
        val sitePath = parts[0]
        val notePath = parts[1]
        return GET("$baseUrl/$sitePath/$notePath")
    }
    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()
    override suspend fun getAnimeDetails(anime: SAnime): SAnime = anime

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = throw UnsupportedOperationException()
    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val parts = anime.url.split("::", limit = 2)
        val sitePath = parts[0]
        val notePath = parts[1]

        val responseBody = client.newCall(GET("$baseUrl/$sitePath/$notePath.json")).execute().body.string()
        val note = json.decodeFromString<CNNoteResponse>(responseBody).note

        val episodes = mutableListOf<SEpisode>()
        var counter = 1

        DRIVE_LINK_REGEX.findAll(note.body).forEach { match ->
            val epName = match.groupValues[1].trim()
            val driveUrl = match.groupValues[2].trim()

            if (driveUrl.isBlank()) return@forEach

            val isFolderUrl = "drive/folders" in driveUrl

            if (isFolderUrl) {
                // Carpeta — listar videos dentro y crear un episodio por cada uno
                val folderEpisodes = episodesFromFolder(driveUrl, epName)
                episodes.addAll(folderEpisodes)
                counter += folderEpisodes.size
            } else {
                // Archivo directo
                val epNumber = EP_NUMBER_REGEX.find(epName)?.groupValues?.get(1)?.toFloatOrNull()
                    ?: counter.toFloat()

                val realName = if (showFilename) {
                    val fileId = DRIVE_FILE_ID_REGEX.find(driveUrl)?.groupValues?.get(1)
                    if (fileId != null) getDriveFileName(fileId) ?: epName else epName
                } else {
                    "Episodio $counter"
                }
                episodes.add(
                    SEpisode.create().apply {
                        name = realName
                        url = driveUrl
                        episode_number = epNumber
                        date_upload = runCatching {
                            dateFormat.parse(note.updated_at)?.time ?: 0L
                        }.getOrElse { 0L }
                    },
                )
                counter++
            }
        }

        return episodes.reversed()
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request = throw UnsupportedOperationException()
    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val fileId = DRIVE_FILE_ID_REGEX.find(episode.url)?.groupValues?.get(1)
            ?: DRIVE_UC_ID_REGEX.find(episode.url)?.groupValues?.get(1)
            ?: throw Exception("No se pudo extraer el ID del archivo de Drive")
        return GoogleDriveExtractor(client, headers).videosFromUrl(fileId)
    }

    // ===================== Drive file name resolver ======================

    private fun getDriveFileName(fileId: String): String? = try {
        val url = "https://drive.google.com/file/d/$fileId/view"
        val doc = client.newCall(GET(url, driveHeaders)).execute().asJsoup()
        doc.selectFirst("title")?.text()
            ?.removeSuffix(" - Google Drive")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    // ==================== Drive folder listing (temporal) =================
    // TODO: mover a lib/googledriveextractor o lib compartida cuando se cree

    private fun episodesFromFolder(folderUrl: String, folderLabel: String): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val folderId = DRIVE_FOLDER_ID_REGEX.find(folderUrl)?.groupValues?.get(1) ?: return episodes

        val driveDocument = try {
            client.newCall(GET(folderUrl, headers = driveHeaders)).execute().asJsoup()
        } catch (a: ProtocolException) {
            return episodes
        }

        if (driveDocument.selectFirst("title:contains(Error 404 \\(Not found\\))") != null) return episodes

        var pageToken: String? = ""
        var counter = 1

        while (pageToken != null) {
            val response = client.newCall(
                createDrivePost(driveDocument, folderId, pageToken),
            ).execute()

            val parsed = response.parseAs<DrivePostResponse> {
                DRIVE_JSON_REGEX.find(it)!!.groupValues[1]
            }

            if (parsed.items == null) break

            parsed.items.forEach { item ->
                val isVideo = item.mimeType.startsWith("video") ||
                    (
                        item.mimeType == "application/vnd.google-apps.shortcut" &&
                            item.shortcutDetails?.targetMimeType?.startsWith("video") == true
                        )

                if (isVideo) {
                    val itemId = if (item.mimeType == "application/vnd.google-apps.shortcut") {
                        item.shortcutDetails?.targetId ?: item.id
                    } else {
                        item.id
                    }
                    val size = item.fileSize?.toLongOrNull()?.let { formatBytes(it) } ?: ""
                    episodes.add(
                        SEpisode.create().apply {
                            name = if (showFilename) item.title else "Episodio $counter"
                            url = "https://drive.google.com/uc?id=$itemId"
                            episode_number = EP_NUMBER_REGEX.find(item.title)
                                ?.groupValues?.get(1)?.toFloatOrNull() ?: counter.toFloat()
                            scanlator = size
                            date_upload = -1L
                        },
                    )
                    counter++
                }
            }

            pageToken = parsed.nextPageToken
        }

        return episodes
    }

    private fun createDrivePost(
        document: Document,
        folderId: String,
        pageToken: String?,
    ): Request {
        val keyScript = document.select("script").first { script ->
            DRIVE_KEY_REGEX.find(script.data()) != null
        }.data()
        val key = DRIVE_KEY_REGEX.find(keyScript)?.groupValues?.get(1) ?: ""

        val versionScript = document.select("script").first { script ->
            DRIVE_VERSION_REGEX.find(script.data()) != null
        }.data()
        val driveVersion = DRIVE_VERSION_REGEX.find(versionScript)?.groupValues?.get(1) ?: ""

        val sapisid = client.cookieJar.loadForRequest("https://drive.google.com".toHttpUrl())
            .firstOrNull { it.name == "SAPISID" || it.name == "__Secure-3PAPISID" }?.value ?: ""

        val requestUrl = defaultDriveGetRequest(folderId, pageToken ?: "", key)
        val body = """--$DRIVE_BOUNDARY
                    |content-type: application/http
                    |content-transfer-encoding: binary
                    |
                    |GET $requestUrl
                    |X-Goog-Drive-Client-Version: $driveVersion
                    |authorization: ${generateSapisidhashHeader(sapisid)}
                    |x-goog-authuser: 0
                    |
                    |--$DRIVE_BOUNDARY--""".trimMargin("|")
            .toRequestBody("multipart/mixed; boundary=\"$DRIVE_BOUNDARY\"".toMediaType())

        val postUrl = buildString {
            append("https://clients6.google.com/batch/drive/v2internal")
            append("?${'$'}ct=multipart/mixed; boundary=\"$DRIVE_BOUNDARY\"")
            append("&key=$key")
        }

        val postHeaders = headers.newBuilder().apply {
            add("Content-Type", "text/plain; charset=UTF-8")
            add("Origin", "https://drive.google.com")
            add("Cookie", getCookie("https://drive.google.com"))
        }.build()

        return POST(postUrl, body = body, headers = postHeaders)
    }

    private fun generateSapisidhashHeader(
        sapisid: String,
        origin: String = "https://drive.google.com",
    ): String {
        val timeNow = System.currentTimeMillis() / 1000
        val hash = MessageDigest.getInstance("SHA-1")
            .digest("$timeNow $sapisid $origin".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${timeNow}_$hash"
    }

    private fun getCookie(url: String): String {
        val cookieList = client.cookieJar.loadForRequest(url.toHttpUrl())
        return if (cookieList.isNotEmpty()) {
            cookieList.joinToString("; ") { "${it.name}=${it.value}" }
        } else {
            ""
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.2f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.2f KB".format(bytes / 1_000.0)
        bytes > 1 -> "$bytes bytes"
        bytes == 1L -> "$bytes byte"
        else -> ""
    }

    private fun defaultDriveGetRequest(folderId: String, nextPageToken: String, key: String): String = "/drive/v2internal/files?openDrive=false&reason=102&syncType=0&errorRecovery=false" +
        "&q=trashed%20%3D%20false%20and%20'$folderId'%20in%20parents" +
        "&fields=kind%2CnextPageToken%2Citems(kind%2CmodifiedDate%2CfileSize%2Ctitle%2Cid%2CmimeType%2CshortcutDetails(targetId%2CtargetMimeType))" +
        "&appDataFilter=NO_APP_DATA&spaces=drive&pageToken=$nextPageToken" +
        "&maxResults=100&supportsTeamDrives=true&includeItemsFromAllDrives=true" +
        "&corpora=default&orderBy=folder%2Ctitle_natural%20asc&retryCount=0&key=$key HTTP/1.1"

    // DTOs temporales para Drive (hasta que se cree la librería compartida)
    @Serializable
    data class DrivePostResponse(
        val nextPageToken: String? = null,
        val items: List<DriveItem>? = null,
    ) {
        @Serializable
        data class DriveItem(
            val id: String,
            val title: String,
            val mimeType: String,
            val fileSize: String? = null,
            val modifiedDate: String? = null,
            val shortcutDetails: ShortcutDetails? = null,
        )

        @Serializable
        data class ShortcutDetails(
            val targetId: String = "",
            val targetMimeType: String = "",
        )
    }

    // ============================= Utilities ==============================

    private fun fetchAllNotes(sitePath: String): List<CNNote> {
        val allNotes = mutableListOf<CNNote>()
        val seenIds = mutableSetOf<Int>()
        var page = 1

        while (true) {
            val url = if (page == 1) "$baseUrl/$sitePath.json" else "$baseUrl/$sitePath.json?page=$page"
            val responseBody = client.newCall(GET(url)).execute().body.string()
            val siteResponse = json.decodeFromString<CNSiteResponse>(responseBody)

            val newNotes = siteResponse.notes.filter { it.id !in seenIds }
            if (newNotes.isEmpty()) break

            allNotes.addAll(newNotes)
            seenIds.addAll(newNotes.map { it.id })

            if (allNotes.size >= siteResponse.total_notes) break
            page++
        }

        return allNotes
    }

    private fun extractDescription(body: String): String {
        var text = body
        text = text.replace(Regex("^#+.+$", RegexOption.MULTILINE), "").trim()
        text = text.replace(Regex("!\\[.*?\\]\\([^)]+\\)"), "").trim()
        val specIdx = text.indexOf("Especificaciones")
        if (specIdx != -1) text = text.substring(0, specIdx)
        return text.trim()
    }

    private val showFilename: Boolean
        get() = preferences.getBoolean(PREF_SHOW_FILENAME, true)

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = CollectedNotesPreferences.PREF_KEY
            title = "Fansubs guardados"
            summary = "Toca para editar tus fansubs guardados"
            dialogTitle = "Fansubs guardados"
            setDialogMessage(
                "Una entrada por línea.\n\nEjemplo:\nNombre::URL de Collected Notes\n\nPara eliminar una entrada, borra la línea completa.",
            )
            setDefaultValue("")
        }.also(screen::addPreference)

        androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_FILENAME
            title = "Mostrar nombre del archivo"
            summary = "Activado: muestra el nombre real del episodio.\nDesactivado: muestra \"Episodio 1\", \"Episodio 2\"…"
            setDefaultValue(true)
        }.also(screen::addPreference)
    }

    companion object {
        // Regex para links de Drive en el body del fansub
        private val DRIVE_LINK_REGEX = Regex(
            """[-*]\s*[-*]?\s*(.+?)\s*\[(?:Drive|drive)\]\((https://drive\.google\.com/[^)]*)\)""",
            RegexOption.IGNORE_CASE,
        )

        // Extraer file ID de URL de archivo
        private val DRIVE_FILE_ID_REGEX = Regex("""drive\.google\.com/file/d/([^/?]+)""")
        private val DRIVE_UC_ID_REGEX = Regex("""drive\.google\.com/uc\?id=([^&/]+)""")

        // Extraer folder ID de URL de carpeta
        private val DRIVE_FOLDER_ID_REGEX = Regex("""drive\.google\.com/drive/folders/([^/?]+)""")

        // Número de episodio
        private val EP_NUMBER_REGEX = Regex(
            """(?:Episodio|Capitulo|Episode|Cap[ií]tulo)\s+(\d+)""",
            RegexOption.IGNORE_CASE,
        )

        // Imagen del body
        private val BODY_IMAGE_REGEX = Regex("""!\[.*?\]\(([^)]+)\)""")

        // Regex para parsear respuesta de Drive
        private val DRIVE_JSON_REGEX = Regex("""(?:)\s*(\{(.+)\})\s*(?:)""", RegexOption.DOT_MATCHES_ALL)
        private val DRIVE_KEY_REGEX = Regex(""""(\w{39})"""")
        private val DRIVE_VERSION_REGEX = Regex(""""([^"]+web-frontend[^"]+)"""")
        private const val DRIVE_BOUNDARY = "=====vc17a3rwnndj====="
        private const val PREF_SHOW_FILENAME = "show_filename"
    }
}
