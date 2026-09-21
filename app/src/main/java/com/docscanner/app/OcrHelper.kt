package com.docscanner.app

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object OcrHelper {

    // 4개 인식기 병렬 실행 (한국어, 영어/기호, 한자, 일본어)
    suspend fun extractText(bitmap: Bitmap): String = coroutineScope {
        val recognizers = listOf(
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
            TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        )

        // async로 모두 동시에 시작
        val results = recognizers.map { recognizer ->
            async {
                try { runRecognizer(bitmap, recognizer) } catch (e: Exception) { null }
            }
        }.awaitAll().filterNotNull()

        if (results.isEmpty()) return@coroutineScope ""

        val mergedBlocks = mergeResults(results, bitmap.width, bitmap.height)

        if (mergedBlocks.isNotEmpty()) {
            reconstructLayout(mergedBlocks, bitmap.width, bitmap.height)
        } else {
            val best = results.maxByOrNull { countMeaningfulChars(it.text) }
                ?: return@coroutineScope ""
            reconstructLayout(best.textBlocks, bitmap.width, bitmap.height)
        }
    }

    private suspend fun runRecognizer(bitmap: Bitmap, recognizer: TextRecognizer): Text =
        suspendCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }

    private fun countMeaningfulChars(text: String): Int =
        text.count { !it.isWhitespace() }

    private fun mergeResults(results: List<Text>, imgW: Int, imgH: Int): List<Text.TextBlock> {
        val allBlocks = mutableListOf<Text.TextBlock>()
        val usedAreas = mutableListOf<Rect>()

        val sorted = results.sortedByDescending { countMeaningfulChars(it.text) }

        for (result in sorted) {
            for (block in result.textBlocks) {
                val bbox = block.boundingBox ?: continue
                if (block.text.isBlank()) continue
                val isDuplicate = usedAreas.any { existing ->
                    overlapRatio(bbox, existing) > 0.7f
                }
                if (!isDuplicate) {
                    allBlocks.add(block)
                    usedAreas.add(bbox)
                }
            }
        }
        return allBlocks
    }

    private fun overlapRatio(a: Rect, b: Rect): Float {
        val interLeft   = max(a.left, b.left)
        val interTop    = max(a.top, b.top)
        val interRight  = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        if (interRight <= interLeft || interBottom <= interTop) return 0f
        val interArea = (interRight - interLeft).toFloat() * (interBottom - interTop)
        val aArea = a.width().toFloat() * a.height()
        val bArea = b.width().toFloat() * b.height()
        return interArea / min(aArea, bArea)
    }

    private fun reconstructLayout(blocks: List<Text.TextBlock>, imgW: Int, imgH: Int): String {
        if (blocks.isEmpty()) return ""

        data class LineInfo(
            val text: String,
            val top: Int, val bottom: Int,
            val left: Int, val right: Int,
            val centerY: Int = (top + bottom) / 2
        )

        val allLines = mutableListOf<LineInfo>()
        for (block in blocks) {
            for (line in block.lines) {
                val bbox = line.boundingBox ?: continue
                val text = line.text.trim()
                if (text.isBlank()) continue
                allLines.add(LineInfo(text, bbox.top, bbox.bottom, bbox.left, bbox.right))
            }
        }
        if (allLines.isEmpty()) return ""

        allLines.sortBy { it.centerY }

        val avgLineHeight = allLines.map { it.bottom - it.top }.average().toInt()
        val rowThreshold = (avgLineHeight * 0.6).toInt().coerceAtLeast(10)

        val rows = mutableListOf<MutableList<LineInfo>>()
        for (line in allLines) {
            val lastRow = rows.lastOrNull()
            val lastCenterY = lastRow?.map { it.centerY }?.average()?.toInt() ?: Int.MIN_VALUE
            if (lastRow == null || abs(line.centerY - lastCenterY) > rowThreshold) {
                rows.add(mutableListOf(line))
            } else {
                lastRow.add(line)
            }
        }

        rows.forEach { row -> row.sortBy { it.left } }

        val sb = StringBuilder()
        var prevRowBottom = -1

        for (row in rows) {
            val rowTop = row.minOf { it.top }
            if (prevRowBottom >= 0) {
                val gap = rowTop - prevRowBottom
                val emptyLines = when {
                    gap > avgLineHeight * 2.5 -> 2
                    gap > avgLineHeight * 1.5 -> 1
                    else -> 0
                }
                repeat(emptyLines) { sb.append("\n") }
            }
            sb.append(buildRowText(row.map { it.text to it.left }, imgW))
            sb.append("\n")
            prevRowBottom = row.maxOf { it.bottom }
        }

        return sb.toString().trimEnd()
    }

    private fun buildRowText(items: List<Pair<String, Int>>, imgW: Int): String {
        if (items.size == 1) return items[0].first
        if (items.isEmpty()) return ""
        val sb = StringBuilder()
        for ((idx, item) in items.withIndex()) {
            sb.append(item.first)
            if (idx < items.size - 1) {
                val nextLeft = items[idx + 1].second
                val currentLeft = item.second
                val gap = nextLeft - (currentLeft + item.first.length * 8)
                val spaces = when {
                    gap > imgW * 0.15 -> "        "
                    gap > imgW * 0.05 -> "    "
                    gap > imgW * 0.02 -> "  "
                    else              -> " "
                }
                sb.append(spaces)
            }
        }
        return sb.toString()
    }
}
