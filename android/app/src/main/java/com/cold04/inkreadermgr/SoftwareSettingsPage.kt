package com.cold04.inkreadermgr

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.cold04.inkreadermgr.ui.theme.PicoManageTheme
import io.noties.markwon.Markwon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class SoftwareSettingsActivity : ComponentActivity() {
    private var updateSubtitle by mutableStateOf("点击检测")
    private var checking by mutableStateOf(false)
    private var release by mutableStateOf<AppRelease?>(null)
    private var downloading by mutableStateOf(false)
    private var downloadReceived by mutableStateOf(0L)
    private var downloadTotal by mutableStateOf<Long?>(null)
    private var downloadFailed by mutableStateOf(false)
    private var permissionPrompt by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PicoManageTheme {
                SoftwareSettingsPage(
                    updateSubtitle = updateSubtitle,
                    checking = checking,
                    release = release,
                    downloading = downloading,
                    downloadReceived = downloadReceived,
                    downloadTotal = downloadTotal,
                    downloadFailed = downloadFailed,
                    permissionPrompt = permissionPrompt,
                    onBack = ::finish,
                    onCheckUpdate = ::checkUpdate,
                    onDismissRelease = { release = null },
                    onDownload = ::downloadUpdate,
                    onDismissDownloadError = { downloadFailed = false },
                    onDismissPermission = { permissionPrompt = false },
                    onOpenPermissionSettings = ::openPermissionSettings,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppUpdateInstaller.clearCacheIfInstalled(this)
        when (AppUpdateInstaller.takeInstallResult(this)) {
            "success" -> updateSubtitle = "已是最新版本"
            "not_completed" -> {
                updateSubtitle = "点击重试安装"
                Toast.makeText(this, "安装未完成", Toast.LENGTH_SHORT).show()
            }
        }
        if (AppUpdateInstaller.consumeSourcePermissionReturn(this)) {
            if (AppUpdateInstaller.canInstallPackages(this)) {
                lifecycleScope.launch {
                    val hadCache = AppUpdateInstaller.hasCachedUpdateRecord(this@SoftwareSettingsActivity)
                    val cached = AppUpdateInstaller.cachedUpdate(this@SoftwareSettingsActivity)
                    if (cached != null) startInstall(cached.apkFile)
                    else if (hadCache) Toast.makeText(
                        this@SoftwareSettingsActivity, "更新包校验失败，请重新下载", Toast.LENGTH_SHORT,
                    ).show()
                }
            } else {
                permissionPrompt = true
            }
        }
    }

    private fun checkUpdate() {
        if (checking || downloading) return
        checking = true
        updateSubtitle = "检测中"
        lifecycleScope.launch {
            var cached: AppUpdateInstaller.CachedUpdate? = null
            try {
                val hadCache = AppUpdateInstaller.hasCachedUpdateRecord(this@SoftwareSettingsActivity)
                cached = AppUpdateInstaller.cachedUpdate(this@SoftwareSettingsActivity)
                if (hadCache && cached == null) {
                    Toast.makeText(this@SoftwareSettingsActivity, "更新包校验失败，请重新下载", Toast.LENGTH_SHORT).show()
                }
                val includePreviews = getSharedPreferences("software_settings", MODE_PRIVATE)
                    .getBoolean("include_preview_releases", false)
                val result = AppUpdateChecker.check(BuildConfig.VERSION_NAME, includePreviews)
                if (result.updateAvailable && result.latestRelease != null) {
                    val latest = result.latestRelease
                    if (cached != null && AppUpdateChecker.compareVersions(cached.versionName, latest.versionName) == 0) {
                        updateSubtitle = "已下载 Android-v${cached.versionName}"
                        startInstall(cached.apkFile)
                    } else {
                        updateSubtitle = "发现新版本 Android-v${latest.versionName}"
                        release = latest
                    }
                } else {
                    updateSubtitle = "已是最新版本"
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (_: Exception) {
                Toast.makeText(this@SoftwareSettingsActivity, "暂时无法获取更新信息", Toast.LENGTH_SHORT).show()
                if (cached != null) {
                    updateSubtitle = "已下载 Android-v${cached.versionName}"
                    startInstall(cached.apkFile)
                } else {
                    updateSubtitle = "点击重试"
                }
            } finally {
                checking = false
            }
        }
    }

    private fun downloadUpdate() {
        val selected = release ?: return
        release = null
        downloading = true
        downloadFailed = false
        downloadReceived = 0L
        downloadTotal = null
        lifecycleScope.launch {
            try {
                val file = AppUpdateInstaller.download(applicationContext, selected) { received, total ->
                    runOnUiThread {
                        downloadReceived = received
                        downloadTotal = total
                    }
                }
                downloading = false
                startInstall(file)
            } catch (cause: CancellationException) {
                throw cause
            } catch (_: Exception) {
                downloading = false
                downloadFailed = true
                updateSubtitle = "点击重试"
            }
        }
    }

    private fun startInstall(apk: File) {
        if (!AppUpdateInstaller.canInstallPackages(this)) {
            permissionPrompt = true
            return
        }
        try {
            AppUpdateInstaller.submitInstall(this, apk)
        } catch (_: Exception) {
            Toast.makeText(this, "无法打开安装器，请稍后重试", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openPermissionSettings() {
        try {
            AppUpdateInstaller.beginWaitingForSourcePermission(this)
            permissionPrompt = false
            startActivity(AppUpdateInstaller.unknownSourcesSettingsIntent(this))
        } catch (_: ActivityNotFoundException) {
            AppUpdateInstaller.consumeSourcePermissionReturn(this)
            Toast.makeText(this, "无法打开安装权限设置", Toast.LENGTH_SHORT).show()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SoftwareSettingsPage(
    updateSubtitle: String,
    checking: Boolean,
    release: AppRelease?,
    downloading: Boolean,
    downloadReceived: Long,
    downloadTotal: Long?,
    downloadFailed: Boolean,
    permissionPrompt: Boolean,
    onBack: () -> Unit,
    onCheckUpdate: () -> Unit,
    onDismissRelease: () -> Unit,
    onDownload: () -> Unit,
    onDismissDownloadError: () -> Unit,
    onDismissPermission: () -> Unit,
    onOpenPermissionSettings: () -> Unit,
) {
    val context = LocalContext.current
    val compiledAt = rememberBuildTime()
    val preferences = remember(context) {
        context.getSharedPreferences("software_settings", Context.MODE_PRIVATE)
    }
    var includePreviews by remember(preferences) {
        mutableStateOf(preferences.getBoolean("include_preview_releases", false))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("软件设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(Modifier.fillMaxSize().padding(innerPadding)) {
            item { SoftwareInformationRow("Android 版本", "Android-v${BuildConfig.VERSION_NAME}") }
            item { SoftwareInformationRow("InkReaderLink 版本", BuildConfig.INKREADERLINK_SDK_VERSION) }
            item { SoftwareInformationRow("编译时间", compiledAt) }
            item {
                ListItem(
                    headlineContent = { Text("检测更新") },
                    supportingContent = { Text(updateSubtitle) },
                    trailingContent = {
                        if (checking) CircularProgressIndicator()
                        else Icon(Icons.Default.ChevronRight, contentDescription = null)
                    },
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !checking && !downloading, onClick = onCheckUpdate),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("检查预览版") },
                    trailingContent = { Switch(checked = includePreviews, onCheckedChange = null) },
                    modifier = Modifier.fillMaxWidth().toggleable(
                        value = includePreviews,
                        role = Role.Switch,
                    ) {
                        includePreviews = it
                        preferences.edit().putBoolean("include_preview_releases", it).apply()
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("GitHub") },
                    supportingContent = { Text("项目地址") },
                    leadingContent = { Icon(Icons.Default.Code, contentDescription = null) },
                    trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                    modifier = Modifier.clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Coldin04/Pico_Manager")))
                    },
                )
            }
        }
    }

    release?.let { selected ->
        AlertDialog(
            onDismissRequest = onDismissRelease,
            title = { Text("发现新版本") },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    Text("Android-v${selected.versionName}")
                    if (selected.notes.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        ReleaseMarkdown(selected.notes)
                    }
                }
            },
            dismissButton = { TextButton(onClick = onDismissRelease) { Text("取消") } },
            confirmButton = { TextButton(onClick = onDownload) { Text("立即更新") } },
        )
    }
    if (downloading || downloadFailed) {
        AlertDialog(
            onDismissRequest = { if (downloadFailed) onDismissDownloadError() },
            title = { Text(if (downloadFailed) "下载失败" else "正在下载更新") },
            text = {
                if (downloadFailed) {
                    Text("暂时无法下载更新，请稍后重试")
                } else {
                    val progress = downloadTotal?.takeIf { it > 0L }
                    Column {
                        if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else LinearProgressIndicator(
                            progress = { (downloadReceived.toFloat() / progress).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(if (progress == null) formatBytes(downloadReceived) else "${formatBytes(downloadReceived)} / ${formatBytes(progress)}")
                    }
                }
            },
            confirmButton = {
                if (downloadFailed) TextButton(onClick = onDismissDownloadError) { Text("关闭") }
            },
        )
    }
    if (permissionPrompt) {
        AlertDialog(
            onDismissRequest = onDismissPermission,
            title = { Text("允许安装应用") },
            text = { Text("Android 需要先允许 Pico Manager 安装应用。开启后返回 App 会继续安装。") },
            dismissButton = { TextButton(onClick = onDismissPermission) { Text("取消") } },
            confirmButton = { TextButton(onClick = onOpenPermissionSettings) { Text("去设置") } },
        )
    }
}

@Composable
internal fun ReleaseMarkdown(markdown: String) {
    val context = LocalContext.current
    val markwon = remember(context) { Markwon.create(context) }
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    AndroidView(
        factory = { TextView(it).apply { setTextColor(textColor); textSize = 14f } },
        update = { view ->
            view.setTextColor(textColor)
            markwon.setMarkdown(view, markdown)
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SoftwareInformationRow(label: String, value: String) {
    ListItem(headlineContent = { Text(label) }, supportingContent = { Text(value) })
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(Locale.getDefault(), bytes / 1048576.0)
    bytes >= 1024L -> "%.1f KB".format(Locale.getDefault(), bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
private fun rememberBuildTime(): String = remember {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.getDefault())
        .withZone(ZoneId.systemDefault())
    formatter.format(Instant.parse(BuildConfig.BUILD_TIME))
}
