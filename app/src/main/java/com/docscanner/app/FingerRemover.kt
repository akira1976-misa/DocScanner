package com.docscanner.app

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object FingerRemover {

    /**
     * 스캔 이미지에서 손가락·도구를 감지하고 주변 색으로 채워 제거합니다.
     * 1) 피부색 감지 (RGB 규칙 기반)
     * 2) 마스크 확장 (경계 잔여물 제거)
     * 3) 인페인팅 (주변 비-피부 픽셀로 채우기)
     */
    fun removeFingers(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height

        // 원본 픽셀 배열 복사
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        // ── 1. 피부색 마스크 생성 ──
        val skinMask = BooleanArray(w * h) { isSkin(pixels[it]) }

        // ── 2. 마스크 팽창 (손가락 경계 제거용, 12픽셀) ──
        val dilated = dilateMask(skinMask, w, h, radius = 12)

        // ── 3. 인페인팅 ──
        val result = inpaint(pixels.copyOf(), dilated, w, h)

        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            it.setPixels(result, 0, w, 0, 0, w, h)
        }
    }

    // ── 피부색 감지 (RGB 기반) ─────────────────────────────────────
    private fun isSkin(pixel: Int): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)

        // 너무 어둡거나 흰색(용지)에 가까운 픽셀은 제외
        if (r + g + b < 80) return false          // 너무 어두움
        if (r > 230 && g > 220 && b > 210) return false  // 흰 용지
        if (abs(r - g) < 10 && abs(g - b) < 10)  return false  // 회색 계열

        // 피부색 규칙 (Kovac 알고리즘 기반)
        val rule1 = r > 95 && g > 40 && b > 20 &&
            (max(r, max(g, b)) - min(r, min(g, b))) > 15 &&
            abs(r - g) > 15 && r > g && r > b

        val rule2 = r > 200 && g > 140 && b > 100 &&
            r > g && r > b

        // HSV 기반 보조 판별
        val maxC = max(r, max(g, b)).toFloat()
        val minC = min(r, min(g, b)).toFloat()
        val delta = maxC - minC
        val hue = when {
            delta < 1f -> 0f
            maxC == r.toFloat() -> 60f * (((g - b) / delta) % 6)
            maxC == g.toFloat() -> 60f * ((b - r) / delta + 2)
            else                -> 60f * ((r - g) / delta + 4)
        }.let { if (it < 0) it + 360f else it }

        val sat = if (maxC == 0f) 0f else delta / maxC
        val rule3 = hue in 0f..35f && sat in 0.15f..0.85f && maxC / 255f > 0.25f

        return rule1 || rule2 || rule3
    }

    // ── 마스크 팽창 ────────────────────────────────────────────────
    private fun dilateMask(mask: BooleanArray, w: Int, h: Int, radius: Int): BooleanArray {
        val result = BooleanArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!mask[y * w + x]) continue
                // 주변 radius 범위의 픽셀도 마스크 처리
                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        if (dx * dx + dy * dy > radius * radius) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx in 0 until w && ny in 0 until h)
                            result[ny * w + nx] = true
                    }
                }
            }
        }
        return result
    }

    // ── 인페인팅: 마스크 영역을 가장 가까운 비-마스크 픽셀로 채움 ──
    private fun inpaint(pixels: IntArray, mask: BooleanArray, w: Int, h: Int): IntArray {

        // 문서 배경색 추정 (가장자리 비-피부 픽셀 평균)
        var rSum = 0L; var gSum = 0L; var bSum = 0L; var cnt = 0
        val edgeRange = (w * 0.05).toInt().coerceAtLeast(5)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val inEdge = x < edgeRange || x > w - edgeRange ||
                    y < edgeRange || y > h - edgeRange
                if (inEdge && !mask[y * w + x]) {
                    rSum += Color.red(pixels[y * w + x])
                    gSum += Color.green(pixels[y * w + x])
                    bSum += Color.blue(pixels[y * w + x])
                    cnt++
                }
            }
        }
        val bgColor = if (cnt > 0) {
            Color.rgb((rSum / cnt).toInt(), (gSum / cnt).toInt(), (bSum / cnt).toInt())
        } else Color.WHITE

        // 1차: 배경색으로 일단 채움
        for (i in pixels.indices) {
            if (mask[i]) pixels[i] = bgColor
        }

        // 2차: 마스크 경계에서 안쪽으로 실제 주변 픽셀 블렌딩 (여러 번 반복)
        val temp = pixels.copyOf()
        repeat(3) {
            for (y in 1 until h - 1) {
                for (x in 1 until w - 1) {
                    if (!mask[y * w + x]) continue
                    // 4방향 이웃 중 비-마스크 픽셀 평균
                    var r = 0; var g = 0; var b = 0; var n = 0
                    listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1,
                           -1 to -1, 1 to -1, -1 to 1, 1 to 1).forEach { (dx, dy) ->
                        val nx = x + dx; val ny = y + dy
                        if (nx in 0 until w && ny in 0 until h) {
                            val nb = temp[ny * w + nx]
                            r += Color.red(nb); g += Color.green(nb)
                            b += Color.blue(nb); n++
                        }
                    }
                    if (n > 0) pixels[y * w + x] = Color.rgb(r / n, g / n, b / n)
                }
            }
            pixels.copyInto(temp)
        }
        return pixels
    }
}
