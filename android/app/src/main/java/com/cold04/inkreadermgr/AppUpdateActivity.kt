package com.cold04.inkreadermgr

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.cold04.inkreadermgr.ui.theme.PicoManageTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class AppUpdateActivity : ComponentActivity() {
    companion object {
        const val EXTRA_AUTOMATIC = "automatic_update_check"
        const val EXTRA_VERSION = "release_version"
        const val EXTRA_RELEASE_PAGE = "release_page"
        const val EXTRA_RELEASE_NOTES = "release_notes"
        const val EXTRA_APK_URL = "apk_url"
        const val EXTRA_APK_SIZE = "apk_size"
        const val EXTRA_APK_ARCHITECTURE = "apk_architecture"
        const val EXTRA_APK_SHA256 = "apk_sha256"
    }

    private var release by mutableStateOf<AppRelease?>(null)
    private var cachedUpdate by mutableStateOf<AppUpdateInstaller.CachedUpdate?>(null)
    private var checking by mutableStateOf(true)
    private var downloading by mutableStateOf(false)
    private var statusMessage by mutableStateOf<String?>(null)
    private var permissionPrompt by mutableStateOf(false)
    private var automaticallyOpened = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        automaticallyOpened = intent.getBooleanExtra(EXTRA_AUTOMATIC, false)
        setContent {
            PicoManageTheme {
                AppUpdatePage(
                    release = release,
                    cachedVersion = cachedUpdate?.versionName,
                    checking = checking,
                    downloading = downloading,
                    statusMessage = statusMessage,
                    permissionPrompt = permissionPrompt,
                    onBack = ::finish,
                    onDownloadOrInstall = ::downloadOrInstall,
                    onDismissPermission = { permissionPrompt = false },
                    onOpenPermissionSettings = ::openPermissionSettings,
                    onOpenRelease = {
                        release?.releasePage?.let { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(it))) }
                    },
                )
            }
        }
        lifecycleScope.launch { loadUpdate() }
    }

    override fun onResume() {
        super.onResume()
        AppUpdateInstaller.clearCacheIfInstalled(this)
        when (AppUpdateInstaller.takeInstallResult(this)) {
            "success" -> finish()
            "not_completed" -> Toast.makeText(this, "安装未完成", Toast.LENGTH_SHORT).show()
        }
        if (!AppUpdateInstaller.consumeSourcePermissionReturn(this)) return
        lifecycleScope.launch {
            val cached = AppUpdateInstaller.cachedUpdate(this@AppUpdateActivity)
            cachedUpdate = cached
            if (AppUpdateInstaller.canInstallPackages(this@AppUpdateActivity)) {
                cached?.let { startInstall(it.apkFile) }
            } else {
                permissionPrompt = true
            }
        }
    }

    private suspend fun loadUpdate() {
        checking = true
        try {
            cachedUpdate = AppUpdateInstaller.cachedUpdate(this)
            val fromIntent = intentRelease()
            if (fromIntent != null) {
                if (cachedUpdate == null ||
                    AppUpdateChecker.compareVersions(cachedUpdate!!.versionName, fromIntent.versionName) != 0
                ) {
                    cachedUpdate = null
                    release = fromIntent
                }
                return
            }

            val prefs = getSharedPreferences("software_settings", MODE_PRIVATE)
            val includePreviews = prefs.getBoolean("include_preview_releases", false)
            val result = AppUpdateChecker.check(BuildConfig.VERSION_NAME, includePreviews, AppUpdateSource.load(prefs))
            if (!result.updateAvailable || result.latestRelease == null) {
                statusMessage = "已是最新版本"
                if (automaticallyOpened) finish()
                return
            }
            if (cachedUpdate == null ||
                AppUpdateChecker.compareVersions(cachedUpdate!!.versionName, result.latestRelease.versionName) != 0
            ) {
                cachedUpdate = null
                release = result.latestRelease
            }
        } catch (cause: CancellationException) {
            throw cause
        } catch (_: Exception) {
            statusMessage = "暂时无法获取更新信息"
            if (automaticallyOpened) finish()
        } finally {
            checking = false
        }
    }

    private fun intentRelease(): AppRelease? {
        val version = intent.getStringExtra(EXTRA_VERSION) ?: return null
        val page = intent.getStringExtra(EXTRA_RELEASE_PAGE) ?: return null
        val apkUrl = intent.getStringExtra(EXTRA_APK_URL) ?: return null
        return AppRelease(
            versionName = version,
            releasePage = page,
            notes = intent.getStringExtra(EXTRA_RELEASE_NOTES).orEmpty(),
            apkDownloadUrl = apkUrl,
            apkSizeBytes = intent.getLongExtra(EXTRA_APK_SIZE, 0L).takeIf { it > 0L },
            apkArchitecture = intent.getStringExtra(EXTRA_APK_ARCHITECTURE) ?: "universal",
            apkSha256 = intent.getStringExtra(EXTRA_APK_SHA256) ?: return null,
        )
    }

    private fun downloadOrInstall() {
        val cached = cachedUpdate
        if (cached != null) {
            startInstall(cached.apkFile)
            return
        }
        val selectedRelease = release ?: return
        lifecycleScope.launch {
            downloading = true
            statusMessage = null
            try {
                val file = AppUpdateInstaller.download(applicationContext, selectedRelease)
                cachedUpdate = AppUpdateInstaller.cachedUpdate(this@AppUpdateActivity)
                downloading = false
                startInstall(file)
            } catch (cause: CancellationException) {
                throw cause
            } catch (_: Exception) {
                statusMessage = "下载失败，请稍后重试"
            } finally {
                downloading = false
            }
        }
    }

    private fun startInstall(apk: java.io.File) {
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
private fun AppUpdatePage(
    release: AppRelease?,
    cachedVersion: String?,
    checking: Boolean,
    downloading: Boolean,
    statusMessage: String?,
    permissionPrompt: Boolean,
    onBack: () -> Unit,
    onDownloadOrInstall: () -> Unit,
    onDismissPermission: () -> Unit,
    onOpenPermissionSettings: () -> Unit,
    onOpenRelease: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("软件更新") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when {
                checking -> CircularProgressIndicator()
                cachedVersion != null -> {
                    Text("已下载 Android-v$cachedVersion")
                }
                release != null -> {
                    Text("发现新版本 Android-v${release.versionName}")
                    if (release.notes.isNotBlank()) {
                        Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                            ReleaseMarkdown(release.notes)
                        }
                    }
                    ListItem(
                        headlineContent = { Text("发布详情") },
                        supportingContent = { Text("查看完整说明和文件") },
                        trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenRelease),
                    )
                }
            }
            statusMessage?.let { Text(it) }
            if (downloading) {
                CircularProgressIndicator()
            }
            if (cachedVersion != null || release != null) {
                Button(
                    onClick = onDownloadOrInstall,
                    enabled = !checking && !downloading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (cachedVersion != null) "安装已下载版本" else if (downloading) "正在下载…" else "下载并安装")
                }
            }
        }
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
