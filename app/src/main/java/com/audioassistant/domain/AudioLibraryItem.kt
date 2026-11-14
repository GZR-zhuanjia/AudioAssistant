package com.audioassistant.domain

import kotlinx.serialization.Serializable

@Serializable
enum class LibraryOrigin { VIDEO_CONVERSION, PROJECT_EXPORT, AUDIO_IMPORT, LEGACY_OUTPUT }

/** Metadata for an app-owned audio file, independent of any project's edits. */
@Serializable
data class AudioLibraryItem(
    val id: String,
    val source: SourceAudio,
    val createdAtMs: Long,
    val origin: LibraryOrigin = LibraryOrigin.VIDEO_CONVERSION,
    val lastSavedUri: String? = null,
    val lastSavedAtMs: Long? = null,
    val lastSavedFolderName: String? = null,
)
