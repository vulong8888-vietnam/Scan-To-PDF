package com.example.scantopdf

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ScanTheme { ScanApp() } }
    }
}

/** Ngôn ngữ trong app: "vi" hoặc "en". Chưa chọn thì theo ngôn ngữ của điện thoại. */
object LocaleHelper {
    private fun saved(ctx: Context): String? =
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("lang", null)

    fun wrap(ctx: Context): Context {
        val lang = saved(ctx) ?: return ctx
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val cfg = Configuration(ctx.resources.configuration)
        cfg.setLocale(locale)
        return ctx.createConfigurationContext(cfg)
    }

    fun current(ctx: Context): String =
        saved(ctx) ?: if (Locale.getDefault().language == "vi") "vi" else "en"

    fun set(activity: Activity, lang: String) {
        activity.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putString("lang", lang).apply()
        activity.recreate()
    }
}

@Composable
fun ScanTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
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

private fun prefs(context: Context) =
    context.getSharedPreferences("settings", Context.MODE_PRIVATE)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val files = remember { mutableStateListOf<File>().apply { addAll(listScans(context)) } }

    var quality by remember {
        mutableStateOf(PdfQuality.entries[prefs(context).getInt("quality", 1).coerceIn(0, 2)])
    }
    var boost by remember {
        mutableStateOf(Boost.entries[prefs(context).getInt("boost", 2).coerceIn(0, 3)])
    }
    var ocr by remember { mutableStateOf(prefs(context).getBoolean("ocr", false)) }
    var idScale by remember { mutableStateOf(prefs(context).getFloat("idScale", 1f)) }
    var busy by remember { mutableStateOf(false) }
    var idMode by remember { mutableStateOf(false) }

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
        val uris = result.pages?.map { it.imageUri } ?: emptyList()

        val name = (if (idMode) "CCCD_" else "Scan_") +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + ".pdf"
        val out = File(scansDir(context), name)

        scope.launch {
            busy = true
            try {
                if (idMode) {
                    if (uris.size < 2) error(context.getString(R.string.need_two))
                    buildIdCardPdf(context, uris[0], uris[1], out, idScale, quality, boost)
                } else if (ocr) {
                    buildSearchablePdf(context, uris, out, quality, boost)
                } else {
                    buildJpegPdf(context, uris, quality, out, boost)
                }
                refresh()
                Toast.makeText(context, context.getString(R.string.saved, out.nameWithoutExtension), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                out.delete()
                Toast.makeText(context, context.getString(R.string.error_fmt, e.message ?: ""), Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

    fun startScan(forIdCard: Boolean) {
        idMode = forIdCard
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(if (forIdCard) 2 else 50)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(context as Activity)
            .addOnSuccessListener { sender ->
                scanLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener {
                Toast.makeText(context, context.getString(R.string.scanner_fail, it.message ?: ""), Toast.LENGTH_LONG).show()
            }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Scan to PDF", fontWeight = FontWeight.Bold) },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (busy) {
                item {
                    Column {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.creating), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // Hai nút hành động chính
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionCard(
                        title = stringResource(R.string.scan_document),
                        subtitle = stringResource(R.string.scan_document_sub),
                        container = MaterialTheme.colorScheme.primaryContainer,
                        content = MaterialTheme.colorScheme.onPrimaryContainer,
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) { startScan(false) }
                    ActionCard(
                        title = stringResource(R.string.scan_id),
                        subtitle = stringResource(R.string.scan_id_sub),
                        container = MaterialTheme.colorScheme.tertiaryContainer,
                        content = MaterialTheme.colorScheme.onTertiaryContainer,
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) { startScan(true) }
                }
            }

            // Cài đặt
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Column {
                            Text(stringResource(R.string.language), style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(8.dp))
                            val currentLang = LocaleHelper.current(context)
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                listOf("vi" to "Tiếng Việt", "en" to "English").forEachIndexed { i, (code, name) ->
                                    SegmentedButton(
                                        selected = currentLang == code,
                                        onClick = {
                                            if (currentLang != code) LocaleHelper.set(context as Activity, code)
                                        },
                                        shape = SegmentedButtonDefaults.itemShape(i, 2)
                                    ) { Text(name) }
                                }
                            }
                        }

                        Column {
                            Text(stringResource(R.string.pdf_quality), style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(8.dp))
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                PdfQuality.entries.forEachIndexed { i, q ->
                                    SegmentedButton(
                                        selected = quality == q,
                                        onClick = {
                                            quality = q
                                            prefs(context).edit().putInt("quality", i).apply()
                                        },
                                        shape = SegmentedButtonDefaults.itemShape(i, PdfQuality.entries.size)
                                    ) { Text(stringResource(q.label)) }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(quality.hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Column {
                            Text(stringResource(R.string.brighten), style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(8.dp))
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                Boost.entries.forEachIndexed { i, b ->
                                    SegmentedButton(
                                        selected = boost == b,
                                        onClick = {
                                            boost = b
                                            prefs(context).edit().putInt("boost", i).apply()
                                        },
                                        shape = SegmentedButtonDefaults.itemShape(i, Boost.entries.size)
                                    ) { Text(stringResource(b.label)) }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(boost.hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.ocr_title), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    stringResource(R.string.ocr_sub),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = ocr, onCheckedChange = {
                                ocr = it
                                prefs(context).edit().putBoolean("ocr", it).apply()
                            })
                        }

                        Column {
                            Row {
                                Text(
                                    stringResource(R.string.id_size),
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "${(idScale * 100).toInt()}%",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Slider(
                                value = idScale,
                                onValueChange = { idScale = Math.round(it * 20) / 20f },
                                onValueChangeFinished = {
                                    prefs(context).edit().putFloat("idScale", idScale).apply()
                                },
                                valueRange = 0.5f..2f
                            )
                            Text(
                                stringResource(R.string.id_size_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    stringResource(R.string.recent),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (files.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
            }

            items(files, key = { it.absolutePath }) { file ->
                FileItem(file, scope) { refresh() }
            }
        }
    }
}

@Composable
private fun ActionCard(
    title: String,
    subtitle: String,
    container: Color,
    content: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content)
    ) {
        Column(Modifier.padding(18.dp).height(64.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun FileItem(file: File, scope: kotlinx.coroutines.CoroutineScope, onChanged: () -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val isId = file.name.startsWith("CCCD_")

    fun shareImages(pkg: String?) {
        scope.launch {
            val imgs = withContext(Dispatchers.IO) { pdfToImages(file) }
            sendFiles(context, imgs, "image/jpeg", pkg)
        }
    }

    Card(
        onClick = { openPdf(context, file) },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    (if (isId) stringResource(R.string.id_label) else stringResource(R.string.doc_label)) + " · " +
                        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(file.lastModified())),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1
                )
                Text(
                    sizeText(file.length()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { sendFiles(context, listOf(file), "application/pdf", null) }) {
                Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share_pdf))
            }
            Column {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.share_jpg)) }, onClick = {
                        menu = false; shareImages(null)
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.send_zalo)) }, onClick = {
                        menu = false; sendFiles(context, listOf(file), "application/pdf", "com.zing.zalo")
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.send_messenger)) }, onClick = {
                        menu = false; sendFiles(context, listOf(file), "application/pdf", "com.facebook.orca")
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.post_facebook)) }, onClick = {
                        menu = false; shareImages("com.facebook.katana")
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.send_bluetooth)) }, onClick = {
                        menu = false; sendFiles(context, listOf(file), "application/pdf", "com.android.bluetooth")
                    })
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; confirmDelete = true }
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(stringResource(R.string.delete_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    file.parentFile?.listFiles { f ->
                        f.name.startsWith(file.nameWithoutExtension)
                    }?.forEach { it.delete() }
                    onChanged()
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

private fun sizeText(bytes: Long): String =
    if (bytes >= 1024 * 1024) String.format(Locale.US, "%.1f MB", bytes / 1048576f)
    else "${bytes / 1024} KB"

private fun openPdf(context: Context, file: File) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(fileUri(context, file), "application/pdf")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.no_pdf_viewer), Toast.LENGTH_SHORT).show()
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
            Toast.makeText(context, context.getString(R.string.app_not_installed), Toast.LENGTH_SHORT).show()
        }
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.share)))
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
