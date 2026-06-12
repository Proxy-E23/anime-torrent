package eu.kanade.tachiyomi.animeextension.all.collectednotes

import android.content.SharedPreferences

object CollectedNotesPreferences {

    data class Entry(val name: String, val sitePath: String)

    const val PREF_KEY = "collectednotes_site_list"

    fun getEntries(prefs: SharedPreferences): List<Entry> {
        val raw = prefs.getString(PREF_KEY, "") ?: return emptyList()
        return raw.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                if ("::" !in line) return@mapNotNull null
                val idx = line.indexOf("::")
                val name = line.substring(0, idx).trim()
                val sitePath = line.substring(idx + 2).trim()
                if (name.isBlank() || sitePath.isBlank()) return@mapNotNull null
                Entry(name, sitePath)
            }
    }

    fun addEntry(prefs: SharedPreferences, name: String, sitePath: String) {
        val current = prefs.getString(PREF_KEY, "") ?: ""
        // Evitar duplicados
        val alreadyExists = current.lines().any { it.contains("::$sitePath") }
        if (alreadyExists) return
        val newLine = "$name::$sitePath"
        val updated = if (current.isBlank()) newLine else "$current\n$newLine"
        prefs.edit().putString(PREF_KEY, updated).apply()
    }

    fun removeEntry(prefs: SharedPreferences, sitePath: String) {
        val current = prefs.getString(PREF_KEY, "") ?: return
        val updated = current.lines()
            .filter { sitePath !in it }
            .joinToString("\n")
        prefs.edit().putString(PREF_KEY, updated).apply()
    }
}
