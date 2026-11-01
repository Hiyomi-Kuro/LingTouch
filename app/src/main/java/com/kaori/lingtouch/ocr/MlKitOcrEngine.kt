package com.kaori.lingtouch.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device ML Kit OCR (bundled Chinese model, also recognizes Latin text).
 * Images never leave the device for recognition.
 */
class MlKitOcrEngine : OcrEngine {
    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    override suspend fun recognize(bitmap: Bitmap): OcrResult = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { text ->
                val lines = ArrayList<OcrLine>()
                text.textBlocks.forEach { block ->
                    block.lines.forEach { line ->
                        val box = line.boundingBox
                        lines.add(
                            OcrLine(
                                text = line.text,
                                left = box?.left ?: 0,
                                top = box?.top ?: 0,
                                right = box?.right ?: bitmap.width,
                                bottom = box?.bottom ?: bitmap.height
                            )
                        )
                    }
                }
                if (cont.isActive) cont.resume(OcrResult(lines, text.text, bitmap.width, bitmap.height))
            }
            .addOnFailureListener { error ->
                if (cont.isActive) cont.resumeWithException(error)
            }
    }

    override fun close() {
        runCatching { recognizer.close() }
    }
}
