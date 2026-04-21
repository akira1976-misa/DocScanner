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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object OcrHelper {

    /**
     * 다중 인식기로 텍스트 추출 후 원본 레이아웃을 최대한 재현합니다.
     * - 한국어, 영어/기호, 한자/중국어, 일본어 동시 인식
     * - 바운딩박스 좌표 기반으로 줄/열 위치 복원
     */
    suspend fun extractText(bitmap: Bitmap): String {
        // 4개 인식기 병렬 실행
        val results = listOf(
            runRecognizer(bitmap, TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)),
            runRecognizer(bitmap, TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())),
            runRecognizer(bitmap, TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())),
            runRecognizer(bitmap, TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()))
        )

        // 가장 많은 텍스트를 인식한 결과 선택
        val bestResult = results.maxByOrNull { countMeaningfulChars(it.text) } ?: results[0]

        // 여러 인식기 결과를 병합 (서로 다른 문자 영역 보완)
        val mergedBlocks = mergeResults(results, bitmap.width, bitmap.height)

        return if (mergedBlocks.isNotEmpty()) {
            reconstructLayout(mergedBlocks, bitmap.width, bitmap.height)
        } else {
            reconstructLayout(bestResult.textBlocks, bitmap.width, bitmap.height)
        }
    }

    // ── 단일 인식기 실행 ──────────────────────────────────────────
    private suspend fun runRecognizer(bitmap: Bitmap, recognizer: TextRecognizer): Text =
        suspendCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }

    // ── 의미있는 문자 수 계산 (공백/줄바꿈 제외) ─────────────────
    private fun countMeaningfulChars(text: String): Int =
        text.count { !it.isWhitespace() }

    // ── 여러 인식기 결과 병합 ─────────────────────────────────────
    // 각 인식기가 잘 인식한 영역을 합쳐서 빈 영역을 보완
    private fun mergeResults(results: List<Text>, imgW: Int, imgH: Int): List<Text.TextBlock> {
        val allBlocks = mutableListOf<Text.TextBlock>()
        val usedAreas = mutableListOf<Rect>()

        // 인식된 텍스트가 많은 순으로 정렬
        val sorted = results.sortedByDescending { countMeaningfulChars(it.text) }

        for (result in sorted) {
            for (block in result.textBlocks) {
                val bbox = block.boundingBox ?: continue
                if (block.text.isBlank()) continue

                // 이미 추가된 영역과 70% 이상 겹치면 스킵 (중복 방지)
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

    // ── 두 영역의 겹침 비율 계산 ─────────────────────────────────
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

    // ── 원본 레이아웃 재현 ────────────────────────────────────────
    // 바운딩박스 Y좌표 기준으로 행을 그룹화하고, X좌표로 열 위치를 재현
    private fun reconstructLayout(
        blocks: List<Text.TextBlock>,
        imgW: Int,
        imgH: Int
    ): String {
        if (blocks.isEmpty()) return ""

        // 모든 라인을 펼쳐서 위치 정보와 함께 수집
        data class LineInfo(
            val text: String,
            val top: Int,
            val bottom: Int,
            val left: Int,
            val right: Int,
            val centerY: Int = (top + bottom) / 2
        )

        val allLines = mutableListOf<LineInfo>()
        for (block in blocks) {
            for (line in block.lines) {
                val bbox = line.boundingBox ?: continue
                val text = line.text.trim()
                if (text.isBlank()) continue
                allLines.add(LineInfo(
                    text   = text,
                    top    = bbox.top,
                    bottom = bbox.bottom,
                    left   = bbox.left,
                    right  = bbox.right
                ))
            }
        }

        if (allLines.isEmpty()) return ""

        // Y좌표 기준 정렬
        allLines.sortBy { it.centerY }

        // 같은 행으로 묶기 (centerY 차이가 평균 줄높이의 50% 이내면 같은 행)
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

        // 각 행 내에서 X좌표 정렬
        rows.forEach { row -> row.sortBy { it.left } }

        // 행 간 빈 줄 삽입 (줄 간격이 평균의 1.8배 이상이면 빈 줄 추가)
        val sb = StringBuilder()
        var prevRowBottom = -1

        for ((rowIdx, row) in rows.withIndex()) {
            val rowTop = row.minOf { it.top }

            // 이전 행과의 간격으로 빈 줄 결정
            if (prevRowBottom >= 0) {
                val gap = rowTop - prevRowBottom
                val emptyLines = when {
                    gap > avgLineHeight * 2.5 -> 2
                    gap > avgLineHeight * 1.5 -> 1
                    else -> 0
                }
                repeat(emptyLines) { sb.append("\n") }
            }

            // 같은 행의 텍스트들을 X 간격에 따라 공백으로 연결
            val rowText = buildRowText(row.map { it.text to it.left }, imgW)
            sb.append(rowText)
            sb.append("\n")

            prevRowBottom = row.maxOf { it.bottom }
        }

        return sb.toString().trimEnd()
    }

    // ── 같은 행의 텍스트를 X 간격에 맞게 조합 ────────────────────
    private fun buildRowText(items: List<Pair<String, Int>>, imgW: Int): String {
        if (items.size == 1) return items[0].first
        if (items.isEmpty()) return ""

        val sb = StringBuilder()
        for ((idx, item) in items.withIndex()) {
            sb.append(item.first)
            if (idx < items.size - 1) {
                val currentRight = item.first.length  // 근사값
                val nextLeft = items[idx + 1].second
                val currentLeft = item.second
                val gap = nextLeft - (currentLeft + currentRight * 8) // 평균 글자폭 8px 가정

                // 간격에 따라 공백 수 결정
                val spaces = when {
                    gap > imgW * 0.15 -> "        "  // 넓은 간격 (탭 수준)
                    gap > imgW * 0.05 -> "    "       // 중간 간격
                    gap > imgW * 0.02 -> "  "         // 좁은 간격
                    else              -> " "           // 붙어있음
                }
                sb.append(spaces)
            }
        }
        return sb.toString()
    }
}
