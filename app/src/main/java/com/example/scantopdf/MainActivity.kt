package com.example.scantopdf

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { ScanApp() }
        }
    }
}

private fun scansDir(context: Context): File =
    File(context.filesDir, "scans").apply { mkdirs() }

private fun listScans(context: Context): List<File> =
    scansDir(context).listFiles { f -> f.extension == "pdf" }
        ?.sortedByDescending { it.lastModified() }
        ?: emptyList()

private fun fileUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScanApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val files = remember { mutableStateListOf<File>().apply { addAll(listScans(context)) } }
    var ocr by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var idMode by remember { mutableStateOf(false) }
    var idScale by remember { mutableStateOf(1f) }

    fun refresh() {
        files.clear()
        files.addAll(listScans(context))
    }

    val scanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        if (res.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val result = GmsDocumentScanningResult.fromActivityResultIntent(res.data)
            ?: return@rememberLauncherForActivityResult

        val name = (if (idMode) "CCCD_" else "Scan_") + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + ".pdf"
        val out = File(scansDir(context), name)

        scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    if (idMode) {
                        val uris = result.pages?.map { it.imageUri } ?: emptyList()
                        if (uris.size < 2) error("Cần đủ 2 ảnh: mặt trước và mặt sau")
                        buildIdCardPdf(context, uris[0], uris[1], out, idScale)
                    } else if (ocr) {
                        val pageUris = result.pages?.map { it.imageUri } ?: emptyList()
                        buildSearchablePdf(context, pageUris, out)
                    } else {
                        val pdfUri = result.pdf?.uri ?: error("Không có file PDF")
                        context.contentResolver.openInputStream(pdfUri)!!.use { input ->
                            out.outputStream().use { input.copyTo(it) }
                        }
                    }
                }
                refresh()
                Toast.makeText(context, "Đã lưu $name", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Lỗi: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

    fun startScan(forIdCard: Boolean = false) {
        idMode = forIdCard
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(if (forIdCard) 2 else 50)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF
            )
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(context as Activity)
            .addOnSuccessListener { sender ->
                scanLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener {
                Toast.makeText(context, "Không mở được máy quét: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Scan to PDF") }) },
        floatingActionButton = {
            if (!busy) Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ExtendedFloatingActionButton(onClick = { startScan(forIdCard = true) }) {
                    Text("Quét CCCD (2 mặt)")
                }
                ExtendedFloatingActionButton(onClick = { startScan() }) {
                    Text("Quét tài liệu")
                }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Nhận dạng chữ (OCR)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "PDF có thể tìm kiếm và copy chữ (xử lý lâu hơn)",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(checked = ocr, onCheckedChange = { ocr = it })
            }

            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Text(
                    "Kích thước CCCD trên giấy: ${(idScale * 100).toInt()}% (100% = cỡ thật)",
                    style = MaterialTheme.typography.titleSmall
                )
                Slider(
                    value = idScale,
                    onValueChange = { idScale = (Math.round(it * 20) / 20f) },
                    valueRange = 0.5f..2f
                )
            }

            if (busy) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator()
                    Text("Đang tạo PDF…")
                }
            }

            if (files.isEmpty() && !busy) {
                Text(
                    "Chưa có tài liệu nào. Bấm \"Quét tài liệu\" để bắt đầu.",
                    modifier = Modifier.padding(top = 24.dp)
                )
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(files, key = { it.absolutePath }) { file ->
                    Card(Modifier.fillMaxWidth().clickable { openPdf(context, file) }) {
                        Column(Modifier.padding(12.dp)) {
                            Text(file.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                                    .format(Date(file.lastModified())) +
                                    " • " + (file.length() / 1024) + " KB",
                                style = MaterialTheme.typography.bodySmall
                            )
                            FlowRow {
                                TextButton(onClick = { sendFiles(context, listOf(file), "application/pdf", null) }) {
                                    Text("Chia sẻ PDF")
                                }
                                TextButton(onClick = {
                                    scope.launch {
                                        val imgs = withContext(Dispatchers.IO) { pdfToImages(file) }
                                        sendFiles(context, imgs, "image/jpeg", null)
                                    }
                                }) { Text("Chia sẻ ảnh") }
                                TextButton(onClick = {
                                    sendFiles(context, listOf(file), "application/pdf", "com.zing.zalo")
                                }) { Text("Zalo") }
                                TextButton(onClick = {
                                    sendFiles(context, listOf(file), "application/pdf", "com.facebook.orca")
                                }) { Text("Messenger") }
                                TextButton(onClick = {
                                    scope.launch {
                                        val imgs = withContext(Dispatchers.IO) { pdfToImages(file) }
                                        sendFiles(context, imgs, "image/jpeg", "com.facebook.katana")
                                    }
                                }) { Text("Facebook") }
                                TextButton(onClick = {
                                    sendFiles(context, listOf(file), "application/pdf", "com.android.bluetooth")
                                }) { Text("Bluetooth") }
                                TextButton(onClick = {
                                    file.parentFile?.listFiles { f ->
                                        f.name.startsWith(file.nameWithoutExtension)
                                    }?.forEach { it.delete() }
                                    refresh()
                                }) { Text("Xóa") }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun openPdf(context: Context, file: File) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(fileUri(context, file), "application/pdf")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "Chưa có app đọc PDF", Toast.LENGTH_SHORT).show()
    }
}

/** Gửi file; nếu có [pkg] thì gửi thẳng tới app đó, không có app thì mở bảng chia sẻ chung. */
private fun sendFiles(context: Context, files: List<File>, mime: String, pkg: String?) {
    val uris = ArrayList(files.map { fileUri(context, it) })
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    }
    intent.type = mime
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    if (pkg != null) {
        try {
            context.startActivity(Intent(intent).setPackage(pkg))
            return
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "Chưa cài app này, mở danh sách chia sẻ chung", Toast.LENGTH_SHORT).show()
        }
    }
    context.startActivity(Intent.createChooser(intent, "Chia sẻ"))
}

/** Chuyển từng trang PDF thành ảnh JPG (để gửi qua Facebook, Zalo... dạng ảnh). */
private fun pdfToImages(pdf: File): List<File> {
    val result = mutableListOf<File>()
    android.os.ParcelFileDescriptor.open(pdf, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
        android.graphics.pdf.PdfRenderer(pfd).use { renderer ->
            val count = renderer.pageCount
            for (i in 0 until count) {
                renderer.openPage(i).use { page ->
                    val bmp = android.graphics.Bitmap.createBitmap(
                        page.width * 3, page.height * 3, android.graphics.Bitmap.Config.ARGB_8888
                    )
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val suffix = if (count > 1) "_${i + 1}" else ""
                    val img = File(pdf.parentFile, pdf.nameWithoutExtension + suffix + ".jpg")
                    img.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
                    bmp.recycle()
                    result += img
                }
            }
        }
    }
    return result
}
