package com.docscanner.app

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

object OcrHelper {

    // 이미지에서 텍스트 추출 (한국어 + 영어 동시 인식)
    suspend fun extractText(bitmap: Bitmap): String = suspendCoroutine { cont ->
        val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        val image = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(image)
            .addOnSuccessListener { result ->
                // 블록 단위로 줄바꿈 유지하며 텍스트 조합
                val text = result.textBlocks.joinToString("\n\n") { block ->
                    block.lines.joinToString("\n") { line ->
                        line.text
                    }
                }
                cont.resume(text.trim())
            }
            .addOnFailureListener { e ->
                cont.resumeWithException(e)
            }
    }
}
