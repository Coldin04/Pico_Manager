package com.cold04.inkreadermgr

import android.net.Uri
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.app.Activity
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cold04.inkreadermgr.ui.theme.PicoManageTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import uniffi.inkreaderlink_uniffi.SdkFileEntry
import uniffi.inkreaderlink_uniffi.SdkOperationException
import java.util.Locale

class WallpaperManagementActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceSessions.initialize(applicationContext)
        enableEdgeToEdge()
        setContent { PicoManageTheme { WallpaperManagementPage(onBack = ::finish) } }
    }
}

private data class WallpaperSource(
    val file: File,
    val name: String,
    val mimeType: String?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WallpaperManagementPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val deviceState by DeviceSessions.state.collectAsState()
    val activeDevice = deviceState.active
    val capabilities = activeDevice?.profile?.capabilities?.toSet().orEmpty()
    val extensions = activeDevice?.profile?.fileFormats?.wallpaperUploadExtensions
        ?.map { it.trim().removePrefix(".").lowercase(Locale.ROOT) }
        ?.filter(String::isNotBlank)
        .orEmpty()
    val canList = "wallpapers.manage" in capabilities
    val canUpload = "wallpapers.upload" in capabilities && extensions.isNotEmpty()
    val canDelete = "wallpapers.delete" in capabilities
    val scope = rememberCoroutineScope()
    var wallpapers by remember(activeDevice?.saved?.id) { mutableStateOf<List<SdkFileEntry>?>(null) }
    var loading by remember(activeDevice?.saved?.id) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reconnectRequired by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf<WallpaperSource?>(null) }
    var croppedFile by remember { mutableStateOf<File?>(null) }
    var preparingImage by remember { mutableStateOf(false) }
    var cropOutput by remember { mutableStateOf<File?>(null) }
    var applyToLockScreen by remember { mutableStateOf(false) }
    var overwriteExisting by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SdkFileEntry?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            source?.file?.delete()
            croppedFile?.delete()
            cropOutput?.delete()
        }
    }

    fun refresh() {
        if (!canList || loading || busy) return
        loading = true
        error = null
        reconnectRequired = false
        scope.launch {
            try {
                wallpapers = DeviceSessions.listWallpapers()
            } catch (cause: Exception) {
                error = managerError(cause)
                reconnectRequired = DeviceSessions.isConnectionFailure(cause)
            } finally {
                loading = false
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                    ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank)
                    ?: "未命名图片"
                val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                val mimeType = context.contentResolver.getType(uri)
                if (extension !in extensions || mimeType?.startsWith("image/") == false) {
                    error = "请选择支持的壁纸图片：${extensions.joinToString { ".$it" }}"
                } else {
                    source?.file?.delete()
                    source = null
                    croppedFile?.delete()
                    croppedFile = null
                    error = null
                    preparingImage = true
                    scope.launch {
                        try {
                            val cacheCopy = withContext(Dispatchers.IO) {
                                val directory = File(context.cacheDir, "wallpaper-editing")
                                check(directory.isDirectory || directory.mkdirs()) { "无法创建图片缓存" }
                                val copy = File.createTempFile("source-", ".$extension", directory)
                                try {
                                    val input = context.contentResolver.openInputStream(uri)
                                        ?: throw IllegalStateException("文件选择器无法打开这张图片")
                                    input.buffered().use { sourceStream ->
                                        copy.outputStream().buffered().use { outputStream ->
                                            sourceStream.copyTo(outputStream, 64 * 1024)
                                        }
                                    }
                                    check(copy.length() > 0L) { "选择的图片为空" }
                                    copy
                                } catch (cause: Exception) {
                                    copy.delete()
                                    throw cause
                                }
                            }
                            source = WallpaperSource(cacheCopy, name, mimeType)
                            applyToLockScreen = false
                            overwriteExisting = false
                        } catch (cause: Exception) {
                            error = cause.message ?: "无法复制所选图片到缓存"
                        } finally {
                            preparingImage = false
                        }
                    }
                }
            } catch (cause: Exception) {
                error = managerError(cause)
                reconnectRequired = DeviceSessions.isConnectionFailure(cause)
            }
        }
    }

    val cropLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val output = cropOutput
        cropOutput = null
        if (output != null) {
            if (result.resultCode == Activity.RESULT_OK && output.isFile && output.length() > 0L) {
                croppedFile?.delete()
                croppedFile = output
            } else {
                output.delete()
            }
        }
    }

    LaunchedEffect(activeDevice?.saved?.id, canList) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("壁纸管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (canUpload) IconButton(
                        onClick = { filePicker.launch(arrayOf("image/*")) },
                        enabled = !busy && !preparingImage,
                    ) { Icon(Icons.Default.Add, contentDescription = "上传壁纸") }
                },
            )
        },
    ) { innerPadding ->
        when {
            activeDevice == null -> Box(
                Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) { Text("设备未连接", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            !canList -> Box(
                Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) { Text("设备不支持壁纸列表", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            loading && wallpapers == null -> Box(
                Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            else -> LazyColumn(Modifier.fillMaxSize().padding(innerPadding)) {
                val wallpaperEntries = wallpapers.orEmpty()
                if (wallpaperEntries.isEmpty() && error == null) item {
                    Text(
                        "没有已上传壁纸",
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(wallpaperEntries, key = { it.path }) { wallpaper ->
                    ListItem(
                        headlineContent = { Text(wallpaper.name) },
                        supportingContent = { Text(formatWallpaperSize(wallpaper.size)) },
                        trailingContent = {
                            if (canDelete) IconButton(
                                onClick = { deleting = wallpaper },
                                enabled = !busy,
                            ) { Icon(Icons.Default.Delete, contentDescription = "删除 ${wallpaper.name}") }
                        },
                    )
                }
            }
        }
    }

    source?.let { selected ->
        AlertDialog(
            onDismissRequest = {
                if (!busy) {
                    source = null
                    selected.file.delete()
                    croppedFile?.delete()
                    croppedFile = null
                }
            },
            title = { Text(if (overwriteExisting) "替换壁纸" else "上传壁纸") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(selected.name, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        buildString {
                            append((selected.mimeType?.substringAfter('/')?.uppercase(Locale.ROOT)
                                ?: selected.name.substringAfterLast('.', "图片").uppercase(Locale.ROOT)))
                            croppedFile?.let { append(" · 已裁剪") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val cropExtension = selected.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    val cropSupported = cropExtension in setOf("jpg", "jpeg", "png", "webp")
                    if (activeDevice?.profile?.displayResolution != null && cropSupported) {
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                val resolution = activeDevice.profile.displayResolution ?: return@TextButton
                                try {
                                    val directory = File(context.cacheDir, "wallpaper-editing").apply { mkdirs() }
                                    val output = File.createTempFile("cropped-", ".${cropExtension}", directory)
                                    cropOutput = output
                                    val inputUri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        selected.file,
                                    )
                                    val outputUri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        output,
                                    )
                                    val width = resolution.width.toInt()
                                    val height = resolution.height.toInt()
                                    val divisor = greatestCommonDivisor(width, height)
                                    val intent = Intent("com.android.camera.action.CROP").apply {
                                        setDataAndType(inputUri, "image/*")
                                        putExtra("crop", "true")
                                        putExtra("aspectX", width / divisor)
                                        putExtra("aspectY", height / divisor)
                                        putExtra("scale", true)
                                        putExtra("return-data", false)
                                        putExtra(MediaStore.EXTRA_OUTPUT, outputUri)
                                        clipData = ClipData.newRawUri("input", inputUri)
                                        clipData?.addItem(ClipData.Item(outputUri))
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                    }
                                    val grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                    context.packageManager.queryIntentActivities(intent, 0).forEach { resolveInfo ->
                                        resolveInfo.activityInfo.packageName.let { packageName ->
                                            context.grantUriPermission(packageName, inputUri, grantFlags)
                                            context.grantUriPermission(packageName, outputUri, grantFlags)
                                        }
                                    }
                                    cropLauncher.launch(intent)
                                } catch (cause: ActivityNotFoundException) {
                                    cropOutput?.delete()
                                    cropOutput = null
                                    error = "系统没有可用的图片裁剪器"
                                } catch (cause: Exception) {
                                    cropOutput?.delete()
                                    cropOutput = null
                                    error = cause.message ?: "无法打开图片裁剪器"
                                }
                            },
                        ) {
                            Text(if (croppedFile == null) "裁剪图片" else "重新裁剪")
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = applyToLockScreen,
                            onCheckedChange = { applyToLockScreen = it },
                            enabled = !busy,
                        )
                        Text("同时设为锁屏壁纸")
                    }
                    if (applyToLockScreen) {
                        Text(
                            "锁屏壁纸图片不能超过 2 MiB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        if (busy) return@TextButton
                        busy = true
                        scope.launch {
                            try {
                                val result = DeviceSessions.uploadWallpaper(
                                    context = context,
                                    uri = Uri.fromFile(croppedFile ?: selected.file),
                                    fileName = selected.name,
                                    overwrite = overwriteExisting,
                                    applyToLockScreen = applyToLockScreen,
                                )
                                source = null
                                selected.file.delete()
                                croppedFile?.delete()
                                croppedFile = null
                                overwriteExisting = false
                                error = null
                                Toast.makeText(
                                    context,
                                    if (result.appliedToLockScreen) "壁纸已上传并设为锁屏壁纸" else "壁纸已上传",
                                    Toast.LENGTH_SHORT,
                                ).show()
                                if (canList) {
                                    try {
                                        wallpapers = DeviceSessions.listWallpapers()
                                    } catch (refreshCause: Exception) {
                                        error = managerError(refreshCause)
                                        reconnectRequired = DeviceSessions.isConnectionFailure(refreshCause)
                                    }
                                }
                            } catch (cause: SdkOperationException.Conflict) {
                                overwriteExisting = true
                            } catch (cause: Exception) {
                                error = managerError(cause)
                                reconnectRequired = DeviceSessions.isConnectionFailure(cause)
                                if (cause is SdkOperationException.CommittedWithWarning) {
                                    source = null
                                    selected.file.delete()
                                    croppedFile?.delete()
                                    croppedFile = null
                                    if (canList) {
                                        try {
                                            wallpapers = DeviceSessions.listWallpapers()
                                        } catch (_: Exception) {
                                            // Keep the original warning visible; a later page entry can refresh the list.
                                        }
                                    }
                                }
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text(if (overwriteExisting) "替换" else "上传") }
            },
            dismissButton = {
                TextButton(onClick = {
                    source = null
                    selected.file.delete()
                    croppedFile?.delete()
                    croppedFile = null
                    overwriteExisting = false
                }, enabled = !busy) {
                    Text("取消")
                }
            },
        )
    }

    if (preparingImage) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("准备图片") },
            text = { CircularProgressIndicator() },
            confirmButton = {},
        )
    }

    deleting?.let { wallpaper ->
        AlertDialog(
            onDismissRequest = { if (!busy) deleting = null },
            title = { Text("删除壁纸？") },
            text = { Text(wallpaper.name) },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                DeviceSessions.deleteWallpaper(wallpaper.name)
                                deleting = null
                                error = null
                                Toast.makeText(context, "壁纸已删除", Toast.LENGTH_SHORT).show()
                                try {
                                    wallpapers = DeviceSessions.listWallpapers()
                                } catch (refreshCause: Exception) {
                                    error = managerError(refreshCause)
                                    reconnectRequired = DeviceSessions.isConnectionFailure(refreshCause)
                                }
                            } catch (cause: Exception) {
                                error = managerError(cause)
                                reconnectRequired = DeviceSessions.isConnectionFailure(cause)
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }, enabled = !busy) { Text("取消") } },
        )
    }

    OperationErrorDialog(
        error,
        onDismiss = { error = null },
        title = "壁纸管理失败",
        reconnectToDevice = reconnectRequired,
    )
}

private fun formatWallpaperSize(size: ULong): String {
    if (size < 1024u) return "$size B"
    val kibibytes = size.toDouble() / 1024.0
    if (kibibytes < 1024.0) return "%.1f KiB".format(Locale.getDefault(), kibibytes)
    return "%.1f MiB".format(Locale.getDefault(), kibibytes / 1024.0)
}

private fun greatestCommonDivisor(first: Int, second: Int): Int {
    var a = first
    var b = second
    while (b != 0) {
        val remainder = a % b
        a = b
        b = remainder
    }
    return a.coerceAtLeast(1)
}
