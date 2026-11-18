package com.example.scantopdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.StringRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/** Mức chất lượng PDF: giới hạn cạnh dài nhất của ảnh (px) và độ nén JPEG. */
enum class PdfQuality(
    @StringRes val label: Int,
    val maxDim: Int,
    val jpegQuality: Int,
    @StringRes val hint: Int
) {
    LOW(R.string.q_low, 1100, 60, R.string.q_low_hint),
    MEDIUM(R.string.q_medium, 1700, 78, R.string.q_medium_hint),
    HIGH(R.string.q_high, 2500, 92, R.string.q_high_hint)
}

/** Mức làm sáng ảnh: tự cân bằng sáng tối (auto-levels) rồi nâng sáng vùng tối (gamma). */
enum class Boost(@StringRes val label: Int, val gamma: Float, @StringRes val hint: Int) {
    OFF(R.string.b_off, 1f, R.string.b_off_hint),
    LIGHT(R.string.b_light, 0.85f, R.string.b_light_hint),
    MEDIUM(R.string.b_medium, 0.7f, R.string.b_medium_hint),
    STRONG(R.string.b_strong, 0.55f, R.string.b_strong_hint)
}

/** Làm sáng ảnh: kéo giãn dải sáng theo histogram rồi nâng gamma. */
fun enhance(src: Bitmap, boost: Boost): Bitmap {
    if (boost == Boost.OFF) return src
    val w = src.width
    val h = src.height
    val px = IntArray(w * h)
    src.getPixels(px, 0, w, 0, 0, w, h)

    // Histogram độ sáng (lấy mẫu mỗi 3 điểm ảnh cho nhanh)
    val hist = IntArray(256)
    var count = 0
    var i = 0
    while (i < px.size) {
        val c = px[i]
        val lum = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        hist[lum]++
        count++
        i += 3
    }
    fun percentile(p: Float): Int {
        val target = (count * p).toInt()
        var acc = 0
        for (v in 0..255) {
            acc += hist[v]
            if (acc >= target) return v
        }
        return 255
    }
    var lo = percentile(0.01f)
    var hi = percentile(0.985f)
    if (hi - lo < 40) { lo = 0; hi = 255 }

    val lut = IntArray(256) { v ->
        val x = ((v - lo).toFloat() / (hi - lo)).coerceIn(0f, 1f)
        (Math.pow(x.toDouble(), boost.gamma.toDouble()) * 255.0).toInt().coerceIn(0, 255)
    }
    for (k in px.indices) {
        val c = px[k]
        val r = lut[(c shr 16) and 0xFF]
        val g = lut[(c shr 8) and 0xFF]
        val b = lut[c and 0xFF]
        px[k] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(px, 0, w, 0, 0, w, h)
    src.recycle()
    return out
}

/** Đọc ảnh, thu nhỏ nếu cạnh dài hơn [maxDim], rồi làm sáng theo [boost]. */
fun loadScaled(context: Context, uri: Uri, maxDim: Int, boost: Boost = Boost.OFF): Bitmap =
    enhance(loadScaledRaw(context, uri, maxDim), boost)

private fun loadScaledRaw(context: Context, uri: Uri, maxDim: Int): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    while (longest / (sample * 2) >= maxDim) sample *= 2

    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val bmp = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, opts)
    } ?: error(context.getString(R.string.cant_read_image))

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
suspend fun buildJpegPdf(
    context: Context, pages: List<Uri>, quality: PdfQuality, out: File, boost: Boost = Boost.OFF
) =
    withContext(Dispatchers.Default) {
        val n = pages.size
        require(n > 0) { context.getString(R.string.no_pages) }
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
                val bmp = loadScaled(context, uri, quality.maxDim, boost)
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
