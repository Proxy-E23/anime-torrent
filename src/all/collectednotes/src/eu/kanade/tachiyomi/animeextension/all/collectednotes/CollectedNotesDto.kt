package eu.kanade.tachiyomi.animeextension.all.collectednotes

import kotlinx.serialization.Serializable

@Serializable
data class CNSiteResponse(
    val site: CNSite = CNSite(),
    val total_notes: Int = 0,
    val notes: List<CNNote> = emptyList(),
)

@Serializable
data class CNSite(
    val id: Int = 0,
    val site_path: String = "",
    val name: String = "",
)

@Serializable
data class CNNote(
    val id: Int = 0,
    val path: String = "",
    val title: String = "",
    val body: String = "",
    val url: String = "",
    val updated_at: String = "",
)

@Serializable
data class CNNoteResponse(
    val note: CNNote,
)
