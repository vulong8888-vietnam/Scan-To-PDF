package com.example.scantopdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/** Mức chất lượng PDF: giới hạn cạnh dài nhất của ảnh (px) và độ nén JPEG. */
enum class PdfQuality(
    val label: String,
    val maxDim: Int,
    val jpegQuality: Int,
    val hint: String
) {
    LOW("Thấp", 1100, 60, "File nhỏ nhẹ, hợp để gửi qua Zalo, Messenger"),
    MEDIUM("Vừa", 1700, 78, "Cân bằng giữa dung lượng và độ nét"),
    HIGH("Cao", 2500, 92, "Rất nét, hợp để in, file lớn hơn")
}

/** Đọc ảnh và thu nhỏ nếu cạnh dài hơn [maxDim]. */
fun loadScaled(context: Context, uri: Uri, maxDim: Int): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    while (longest / (sample * 2) >= maxDim) sample *= 2

    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val bmp = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, opts)
    } ?: error("Không đọc được ảnh")

    val cur = maxOf(bmp.width, bmp.height)
    if (cur <= maxDim) return bmp
    val f = maxDim.toFloat() / cur
    val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt(), (bmp.height * f).toInt(), true)
    if (scaled !== bmp) bmp.recycle()
    return scaled
}

/**
 * Ghép các ảnh thành 1 file PDF, mỗi trang là 1 ảnh JPEG nén theo [quality].
 * Tự ghi PDF để kiểm soát được dung lượng file.
 */
suspend fun buildJpegPdf(context: Context, pages: List<Uri>, quality: PdfQuality, out: File) =
    withContext(Dispatchers.Default) {
        val n = pages.size
        require(n > 0) { "Không có trang nào" }
        val totalObjs = 2 + 3 * n
        val offsets = LongArray(totalObjs + 1)
        var pos = 0L

        BufferedOutputStream(FileOutputStream(out)).use { os ->
            fun w(b: ByteArray) { os.write(b); pos += b.size }
            fun ws(s: String) = w(s.toByteArray(Charsets.ISO_8859_1))

            ws("%PDF-1.4\n")
            offsets[1] = pos
            ws("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")

            val kids = (0 until n).joinToString(" ") { "${3 + 3 * it} 0 R" }
            offsets[2] = pos
            ws("2 0 obj\n<< /Type /Pages /Kids [$kids] /Count $n >>\nendobj\n")

            pages.forEachIndexed { i, uri ->
                val bmp = loadScaled(context, uri, quality.maxDim)
                val jpg = ByteArrayOutputStream().also {
                    bmp.compress(Bitmap.CompressFormat.JPEG, quality.jpegQuality, it)
                }.toByteArray()
                val pw = 595
                val ph = (595f * bmp.height / bmp.width).toInt()
                val iw = bmp.width
                val ih = bmp.height
                bmp.recycle()

                val pageNo = 3 + 3 * i
                val contentNo = pageNo + 1
                val imageNo = pageNo + 2
                val content = "q $pw 0 0 $ph 0 0 cm /Im0 Do Q"

                offsets[pageNo] = pos
                ws("$pageNo 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $pw $ph] " +
                    "/Resources << /XObject << /Im0 $imageNo 0 R >> >> /Contents $contentNo 0 R >>\nendobj\n")

                offsets[contentNo] = pos
                ws("$contentNo 0 obj\n<< /Length ${content.length} >>\nstream\n$content\nendstream\nendobj\n")

                offsets[imageNo] = pos
                ws("$imageNo 0 obj\n<< /Type /XObject /Subtype /Image /Width $iw /Height $ih " +
                    "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpg.size} >>\nstream\n")
                w(jpg)
                ws("\nendstream\nendobj\n")
            }

            val xrefPos = pos
            ws("xref\n0 ${totalObjs + 1}\n")
            ws("0000000000 65535 f \n")
            for (k in 1..totalObjs) ws(String.format("%010d 00000 n \n", offsets[k]))
            ws("trailer\n<< /Size ${totalObjs + 1} /Root 1 0 R >>\nstartxref\n$xrefPos\n%%EOF\n")
        }
    }
