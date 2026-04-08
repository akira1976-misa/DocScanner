package com.docscanner.app

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object FingerRemover {

    fun removeFingers(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        // ── 1. 피부색 마스크 생성 ──
        val skinMask = BooleanArray(w * h) { isSkin(pixels[it]) }

        // ── 2. 문서 컨텐츠 보호 마스크 생성 ──
        // 채도 높은 색상(문서의 색깔 영역)과 어두운 색상(검정 텍스트, 헤더)은 보호
        val protectMask = BooleanArray(w * h) { isDocumentContent(pixels[it]) }

        // ── 3. 보호 영역과 피부 마스크 합성: 보호 픽셀은 피부 마스크에서 제외 ──
        for (i in pixels.indices) {
            if (protectMask[i]) skinMask[i] = false
        }

        // ── 4. 연결된 피부 영역 중 문서 가장자리에 있는 것만 제거 ──
        // (문서 중앙의 살색 그림이나 얼굴 사진은 보존)
        val edgeSkinMask = filterEdgeSkin(skinMask, w, h)

        // ── 5. 마스크 팽창 (경계 잔여물 제거) ──
        val dilated = dilateMask(edgeSkinMask, w, h, radius = 8)

        // ── 6. 보호 마스크 재적용 (팽창으로 인한 문서 내용 침범 방지) ──
        for (i in pixels.indices) {
            if (protectMask[i]) dilated[i] = false
        }

        // ── 7. 인페인팅 ──
        val result = inpaint(pixels.copyOf(), dilated, w, h)

        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            it.setPixels(result, 0, w, 0, 0, w, h)
        }
    }

    // ── 피부색 판별 (더 엄격한 기준) ─────────────────────────────
    private fun isSkin(pixel: Int): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)

        // 밝기 범위 필터 (너무 어둡거나 너무 밝으면 피부 아님)
        val brightness = (r + g + b) / 3
        if (brightness < 60 || brightness > 230) return false

        // 흰색/회색 계열 제외 (용지, 텍스트 배경)
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val saturation = if (maxC == 0) 0f else (maxC - minC).toFloat() / maxC
        if (saturation < 0.08f) return false  // 무채색 제외

        // 채도 높은 색상 제외 (문서의 파란색, 빨간색 헤더 등)
        if (saturation > 0.65f) return false

        // R이 지배적이어야 함 (피부색의 핵심 조건)
        if (r <= g || r <= b) return false
        if (r - g < 10) return false

        // HSV 색상각 기반 피부색 범위 (0~25도 = 살구/주황/갈색 계열)
        val hue = getHue(r, g, b)
        if (hue < 0f || hue > 28f) return false

        // 피부색 RGB 비율 조건 (Kovac 기반, 더 엄격)
        val rule1 = r > 95 && g > 40 && b > 20 &&
            (maxC - minC) > 15 &&
            abs(r - g) > 15 && r > g && r > b &&
            r.toFloat() / g > 1.1f

        val rule2 = r in 170..255 && g in 100..180 && b in 50..130 &&
            r > g && g > b

        return rule1 || rule2
    }

    // ── 문서 컨텐츠 보호 판별 ─────────────────────────────────────
    // 어두운 색, 채도 높은 색, 짙은 텍스트 등은 문서 내용으로 보호
    private fun isDocumentContent(pixel: Int): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)

        val brightness = (r + g + b) / 3
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val saturation = if (maxC == 0) 0f else (maxC - minC).toFloat() / maxC

        // 어두운 색상 (검정, 짙은 회색, 어두운 헤더 배경) 보호
        if (brightness < 80) return true

        // 채도 높은 색상 (파란색 표, 빨간색 헤더 등) 보호
        if (saturation > 0.35f) {
            val hue = getHue(r, g, b)
            // 피부색(0~28도) 범위는 제외하고 나머지 채도 높은 색은 보호
            if (hue < 0f || hue > 30f) return true
        }

        // 거의 흰색 (용지)은 보호 대상에서 제외 (인페인팅 대상)
        if (brightness > 200 && saturation < 0.1f) return false

        return false
    }

    // ── 가장자리에 위치한 피부 영역만 필터링 ─────────────────────
    // 이미지 가장자리 20% 안에 포함된 피부 덩어리만 제거 대상으로 설정
    private fun filterEdgeSkin(mask: BooleanArray, w: Int, h: Int): BooleanArray {
        val result = BooleanArray(w * h)
        val edgeW = (w * 0.22).toInt()
        val edgeH = (h * 0.22).toInt()

        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!mask[y * w + x]) continue
                val inEdge = x < edgeW || x > w - edgeW ||
                    y < edgeH || y > h - edgeH
                if (inEdge) result[y * w + x] = true
            }
        }

        // 가장자리 피부와 연결된 내부 피부도 포함
        val visited = BooleanArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (result[y * w + x] && !visited[y * w + x]) {
                    floodFill(mask, result, visited, x, y, w, h)
                }
            }
        }
        return result
    }

    private fun floodFill(
        mask: BooleanArray, result: BooleanArray, visited: BooleanArray,
        startX: Int, startY: Int, w: Int, h: Int
    ) {
        val queue = ArrayDeque<Pair<Int, Int>>()
        queue.add(startX to startY)
        while (queue.isNotEmpty()) {
            val (x, y) = queue.removeFirst()
            val idx = y * w + x
            if (x < 0 || x >= w || y < 0 || y >= h) continue
            if (visited[idx] || !mask[idx]) continue
            visited[idx] = true
            result[idx] = true
            queue.add((x - 1) to y)
            queue.add((x + 1) to y)
            queue.add(x to (y - 1))
            queue.add(x to (y + 1))
        }
    }

    // ── 마스크 팽창 ────────────────────────────────────────────────
    private fun dilateMask(mask: BooleanArray, w: Int, h: Int, radius: Int): BooleanArray {
        val result = BooleanArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!mask[y * w + x]) continue
                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        if (dx * dx + dy * dy > radius * radius) continue
                        val nx = x + dx; val ny = y + dy
                        if (nx in 0 until w && ny in 0 until h)
                            result[ny * w + nx] = true
                    }
                }
            }
        }
        return result
    }

    // ── 인페인팅 ──────────────────────────────────────────────────
    private fun inpaint(pixels: IntArray, mask: BooleanArray, w: Int, h: Int): IntArray {
        // 배경색 추정 (가장자리 비-마스크 픽셀 평균)
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
        val bgColor = if (cnt > 0)
            Color.rgb((rSum / cnt).toInt(), (gSum / cnt).toInt(), (bSum / cnt).toInt())
        else Color.WHITE

        for (i in pixels.indices) { if (mask[i]) pixels[i] = bgColor }

        val temp = pixels.copyOf()
        repeat(4) {
            for (y in 1 until h - 1) {
                for (x in 1 until w - 1) {
                    if (!mask[y * w + x]) continue
                    var r = 0; var g = 0; var b = 0; var n = 0
                    listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1,
                        -1 to -1, 1 to -1, -1 to 1, 1 to 1).forEach { (dx, dy) ->
                        val nb = temp[(y + dy) * w + (x + dx)]
                        r += Color.red(nb); g += Color.green(nb)
                        b += Color.blue(nb); n++
                    }
                    if (n > 0) pixels[y * w + x] = Color.rgb(r / n, g / n, b / n)
                }
            }
            pixels.copyInto(temp)
        }
        return pixels
    }

    private fun getHue(r: Int, g: Int, b: Int): Float {
        val maxC = max(r, max(g, b)).toFloat()
        val minC = min(r, min(g, b)).toFloat()
        val delta = maxC - minC
        if (delta < 1f) return -1f
        val hue = when {
            maxC == r.toFloat() -> 60f * (((g - b) / delta) % 6)
            maxC == g.toFloat() -> 60f * ((b - r) / delta + 2)
            else                -> 60f * ((r - g) / delta + 4)
        }
        return if (hue < 0) hue + 360f else hue
    }
}
