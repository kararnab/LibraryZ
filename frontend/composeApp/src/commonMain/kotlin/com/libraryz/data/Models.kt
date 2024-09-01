package com.libraryz.data

data class Work(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val authors: String? = null,
    val description: String? = null,
    val language: String? = null,
    val publicationYear: Int? = null,
    val isbn: String? = null,
    val openlibraryId: String? = null,
    val editions: List<Edition> = emptyList(),
)

data class Edition(
    val id: String,
    val workId: String,
    val format: String,
    val language: String? = null,
    val sizeBytes: Long,
    val sha256: String,
)

val Edition.sizeMb: String
    get() {
        val mb = sizeBytes / 1_048_576.0
        val rounded = (mb * 10).toLong() / 10.0
        return "$rounded MB"
    }

val Edition.isPdf: Boolean
    get() = format.equals("pdf", ignoreCase = true)
