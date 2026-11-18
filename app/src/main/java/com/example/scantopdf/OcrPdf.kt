package com.example.scantopdf

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private const val PAGE_WIDTH_PT = 595f // A4 ~ 595pt

/**
 * Tạo PDF có thể tìm kiếm/copy chữ: vẽ ảnh trang, rồi chồng một lớp chữ gần như
 * trong suốt tại đúng vị trí mà OCR nhận dạng được.
 */
suspend fun buildSearchablePdf(
    context: Context, pages: List<Uri>, out: File, quality: PdfQuality, boost: Boost = Boost.OFF
) =
    withContext(Dispatchers.Default) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val doc = PdfDocument()
        try {
            pages.forEachIndexed { index, uri ->
                val bmp = loadScaled(context, uri, quality.maxDim, boost)

                val text = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()

                val scale = PAGE_WIDTH_PT / bmp.width
                val pageH = (bmp.height * scale).toInt()
                val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH_PT.toInt(), pageH, index + 1).create()
                val page = doc.startPage(info)
                val canvas = page.canvas
                canvas.scale(scale, scale)
                canvas.drawBitmap(bmp, 0f, 0f, null)

                val paint = Paint().apply {
                    // alpha = 1/255: gần như vô hình nhưng vẫn được ghi vào PDF
                    color = Color.argb(1, 0, 0, 0)
                    isAntiAlias = true
                }
                for (block in text.textBlocks) {
                    for (line in block.lines) {
                        val box = line.boundingBox ?: continue
                        if (line.text.isBlank() || box.width() <= 0 || box.height() <= 0) continue
                        paint.textSize = box.height() * 0.85f
                        paint.textScaleX = 1f
                        val measured = paint.measureText(line.text)
                        if (measured > 0f) paint.textScaleX = box.width() / measured
                        canvas.drawText(line.text, box.left.toFloat(), box.bottom.toFloat() - box.height() * 0.15f, paint)
                    }
                }
                doc.finishPage(page)
                bmp.recycle()
            }
            FileOutputStream(out).use { doc.writeTo(it) }
        } finally {
            doc.close()
            recognizer.close()
        }
    }
