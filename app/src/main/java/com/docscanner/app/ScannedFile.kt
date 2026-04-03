package com.docscanner.app

import java.io.File

enum class FileType { PDF, IMAGE }

data class ScannedFile(
    val name: String,
    val file: File,
    val pageCount: Int,
    val createdAt: Long,
    val type: FileType
) {
    val fileSizeKb: Long get() = file.length() / 1024
    val fileSizeMb: String get() = if (fileSizeKb > 1024)
        "%.1f MB".format(fileSizeKb / 1024f) else "$fileSizeKb KB"
}
