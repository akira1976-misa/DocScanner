package com.docscanner.app

import java.io.File

enum class FileType { PDF, IMAGE }

data class ScannedFile(
    val name: String,
    val file: File,                               // 대표 파일 (첫 페이지)
    val allPageFiles: List<File> = listOf(file),  // 모든 페이지 파일
    val pageCount: Int,
    val createdAt: Long,
    val type: FileType
) {
    // 전체 페이지 합산 용량
    val fileSizeKb: Long get() = allPageFiles.sumOf { it.length() } / 1024
    val fileSizeMb: String get() = if (fileSizeKb > 1024)
        "%.1f MB".format(fileSizeKb / 1024f) else "$fileSizeKb KB"
}
