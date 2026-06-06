package eu.kanade.tachiyomi.animeextension.all.googledrive

import android.content.SharedPreferences

object GoogleDrivePreferences {

    data class Entry(val name: String, val url: String)

    private const val PREF_KEY = "googledrive_folder_list"

    fun getEntries(prefs: SharedPreferences): List<Entry> {
        val raw = prefs.getString(PREF_KEY, "") ?: return emptyList()
        return raw.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                if ("::" !in line) return@mapNotNull null
                val idx = line.indexOf("::")
                val name = line.substring(0, idx).trim()
                val url = line.substring(idx + 2).trim()
                if (name.isBlank() || url.isBlank()) return@mapNotNull null
                Entry(name, url)
            }
    }

    fun addEntry(prefs: SharedPreferences, name: String, url: String) {
        val current = prefs.getString(PREF_KEY, "") ?: ""
        val newLine = "$name::$url"
        val updated = if (current.isBlank()) newLine else "$current\n$newLine"
        prefs.edit().putString(PREF_KEY, updated).apply()
    }

    fun removeEntry(prefs: SharedPreferences, url: String) {
        val current = prefs.getString(PREF_KEY, "") ?: return
        val updated = current.lines()
            .filter { url !in it }
            .joinToString("\n")
        prefs.edit().putString(PREF_KEY, updated).apply()
    }

    fun updateEntryName(prefs: SharedPreferences, url: String, newName: String) {
        val current = prefs.getString(PREF_KEY, "") ?: return
        val updated = current.lines().map { line ->
            if (url in line) "$newName::$url" else line
        }.joinToString("\n")
        prefs.edit().putString(PREF_KEY, updated).apply()
    }

    // ── Preferencia: nombre de episodio ──────────────────────────────────────

    const val PREF_SHOW_FILENAME = "show_filename"
    private const val PREF_SHOW_FILENAME_DEFAULT = false

    fun showFilename(prefs: SharedPreferences): Boolean = prefs.getBoolean(PREF_SHOW_FILENAME, PREF_SHOW_FILENAME_DEFAULT)
}
