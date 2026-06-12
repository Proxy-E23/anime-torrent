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
                val value = line.substring(idx + 2).trim()
                if (name.isBlank() || value.isBlank()) return@mapNotNull null
                // Soporta tanto URL completa como solo sitePath
                val sitePath = value
                    .removePrefix("https://collectednotes.com/")
                    .removePrefix("http://collectednotes.com/")
                    .trim()
                Entry(name, sitePath)
            }
    }

    fun addEntry(prefs: SharedPreferences, name: String, url: String) {
        val current = prefs.getString(PREF_KEY, "") ?: ""
        // Evitar duplicados comparando el sitePath
        val newSitePath = url
            .removePrefix("https://collectednotes.com/")
            .removePrefix("http://collectednotes.com/")
            .trim()
        val alreadyExists = current.lines().any { line ->
            val value = line.substringAfter("::", "")
            val existingPath = value
                .removePrefix("https://collectednotes.com/")
                .removePrefix("http://collectednotes.com/")
                .trim()
            existingPath == newSitePath
        }
        if (alreadyExists) return
        val newLine = "$name::$url"
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
