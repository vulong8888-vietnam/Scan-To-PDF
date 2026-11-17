package com.example.scantopdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

// A4 = 595 x 842 pt. Thẻ CCCD 85.6 x 53.98 mm ~ 242.6 x 153 pt (kích thước thật)
private const val A4_W = 595
private const val A4_H = 842
private const val CARD_W = 242.6f
private const val CARD_H = 153f
private const val GAP = 40f

/** Ghép mặt trước (trên) và mặt sau (dưới) của căn cước vào 1 trang A4, đúng kích thước thật. */
suspend fun buildIdCardPdf(
    context: Context, front: Uri, back: Uri, out: File,
    scale: Float = 1f, quality: PdfQuality = PdfQuality.MEDIUM
) =
    withContext(Dispatchers.Default) {
        val doc = PdfDocument()
        try {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(A4_W, A4_H, 1).create())
            val canvas = page.canvas
            canvas.drawColor(Color.WHITE)

            val cw = CARD_W * scale
            val ch = CARD_H * scale
            val gap = GAP * scale
            val left = (A4_W - cw) / 2f
            val top1 = A4_H / 2f - gap / 2f - ch
            val top2 = A4_H / 2f + gap / 2f

            drawCard(context, canvas, front, RectF(left, top1, left + cw, top1 + ch), quality.maxDim)
            drawCard(context, canvas, back, RectF(left, top2, left + cw, top2 + ch), quality.maxDim)

            doc.finishPage(page)
            FileOutputStream(out).use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }

private fun drawCard(context: Context, canvas: android.graphics.Canvas, uri: Uri, box: RectF, maxDim: Int) {
    var bmp: Bitmap = loadScaled(context, uri, maxDim)

    // Ảnh dọc -> xoay ngang cho vừa khung thẻ
    if (bmp.height > bmp.width) {
        val m = Matrix().apply { postRotate(90f) }
        bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    // Vừa khung, giữ đúng tỉ lệ ảnh
    val scale = minOf(box.width() / bmp.width, box.height() / bmp.height)
    val w = bmp.width * scale
    val h = bmp.height * scale
    val dst = RectF(
        box.centerX() - w / 2f, box.centerY() - h / 2f,
        box.centerX() + w / 2f, box.centerY() + h / 2f
    )
    canvas.drawBitmap(bmp, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))

    val border = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.5f
        color = Color.LTGRAY
    }
    canvas.drawRect(dst, border)
    bmp.recycle()
}
