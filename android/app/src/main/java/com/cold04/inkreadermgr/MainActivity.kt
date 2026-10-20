package com.cold04.inkreadermgr

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.FontDownload
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.lifecycleScope
import com.cold04.inkreadermgr.ui.theme.PicoManageTheme
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var automaticUpdateCheckRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceSessions.initialize(applicationContext)
        enableEdgeToEdge()
        setContent {
            PicoManageTheme {
                val devices by DeviceSessions.state.collectAsState()
                MainShell(
                    activeDevice = devices.active,
                    onDevicesClick = { startActivity(Intent(this, DeviceActivity::class.java)) },
                    onWifiClick = { startActivity(Intent(this, WifiManagementActivity::class.java)) },
                    onSettingsClick = { startActivity(Intent(this, SettingsManagementActivity::class.java)) },
                    onDeviceInfoClick = { startActivity(Intent(this, DeviceInformationActivity::class.java)) },
                    onFontsClick = { startActivity(Intent(this, FontManagementActivity::class.java)) },
                    onOpdsClick = { startActivity(Intent(this, OpdsManagementActivity::class.java)) },
                    onSoftwareSettingsClick = { startActivity(Intent(this, SoftwareSettingsActivity::class.java)) },
                    onWallpapersClick = { startActivity(Intent(this, WallpaperManagementActivity::class.java)) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppUpdateInstaller.clearCacheIfInstalled(this)
        if (automaticUpdateCheckRunning) return

        val preferences = getSharedPreferences("app_update_schedule", MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastCheck = preferences.getLong("last_check_at", 0L)
        if (now - lastCheck < BuildConfig.APP_UPDATE_CHECK_INTERVAL_MILLIS) return

        preferences.edit().putLong("last_check_at", now).apply()
        automaticUpdateCheckRunning = true
        lifecycleScope.launch {
            try {
                val settings = getSharedPreferences("software_settings", MODE_PRIVATE)
                val includePreviews = settings.getBoolean("include_preview_releases", false)
                val check = AppUpdateChecker.check(BuildConfig.VERSION_NAME, includePreviews, AppUpdateSource.load(settings))
                if (check.updateAvailable) {
                    val update = check.latestRelease ?: return@launch
                    startActivity(Intent(this@MainActivity, AppUpdateActivity::class.java).apply {
                        putExtra(AppUpdateActivity.EXTRA_AUTOMATIC, true)
                        putExtra(AppUpdateActivity.EXTRA_VERSION, update.versionName)
                        putExtra(AppUpdateActivity.EXTRA_RELEASE_PAGE, update.releasePage)
                        putExtra(AppUpdateActivity.EXTRA_RELEASE_NOTES, update.notes)
                        putExtra(AppUpdateActivity.EXTRA_APK_URL, update.apkDownloadUrl)
                        putExtra(AppUpdateActivity.EXTRA_APK_SIZE, update.apkSizeBytes ?: 0L)
                        putExtra(AppUpdateActivity.EXTRA_APK_ARCHITECTURE, update.apkArchitecture)
                        putExtra(AppUpdateActivity.EXTRA_APK_SHA256, update.apkSha256)
                    })
                }
            } catch (_: Exception) {
                // Background update checks are silent; the user can retry from Software Settings.
            } finally {
                automaticUpdateCheckRunning = false
            }
        }
    }

}

@OptIn(ExperimentalMaterial3Api::class)
class ShareActivity : ComponentActivity() {
    private val incomingFiles = IncomingFilesInbox<List<BookUploadFile>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceSessions.initialize(applicationContext)
        receiveShareIntent(intent)
        enableEdgeToEdge()
        setContent {
            PicoManageTheme {
                val devices by DeviceSessions.state.collectAsState()
                val incomingEvent by incomingFiles.state.collectAsState()
                androidx.compose.material3.Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("推书") },
                            navigationIcon = {
                                IconButton(onClick = ::finish) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                                }
                            },
                        )
                    },
                ) { insets ->
                    SendPage(
                        activeDevice = devices.active,
                        onDevicesClick = { startActivity(Intent(this, DeviceActivity::class.java)) },
                        incomingEvent = incomingEvent,
                        onIncomingFilesConsumed = incomingFiles::consume,
                        modifier = Modifier.padding(insets),
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveShareIntent(intent)
    }

    private fun receiveShareIntent(intent: Intent?) {
        val files = IncomingShareFiles.receive(this, intent)
        if (files.isNotEmpty()) incomingFiles.publish(files)
    }
}

class DeviceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceSessions.initialize(applicationContext)
        enableEdgeToEdge()
        setContent { PicoManageTheme { DevicePage(onBack = ::finish) } }
    }
}

private enum class MainDestination(val label: String) {
    SEND("推书"),
    FILES("文件管理"),
    FEATURES("功能"),
}

private enum class SendFileCategory(val label: String) {
    BOOK("图书"),
    FONT("字体"),
}

@Composable
private fun MainShell(
    activeDevice: ActiveDevice?,
    onDevicesClick: () -> Unit,
    onWifiClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onDeviceInfoClick: () -> Unit,
    onFontsClick: () -> Unit,
    onOpdsClick: () -> Unit,
    onSoftwareSettingsClick: () -> Unit,
    onWallpapersClick: () -> Unit,
) {
    val destinations = MainDestination.entries
    val pagerState = rememberPagerState(pageCount = { destinations.size })
    val coroutineScope = rememberCoroutineScope()
    val connectedDeviceCount = if (activeDevice == null) 0 else 1

    fun returnToSend() {
        coroutineScope.launch { pagerState.animateScrollToPage(destinations.indexOf(MainDestination.SEND)) }
    }

    val currentDestination = destinations.getOrElse(pagerState.currentPage) { MainDestination.SEND }
    BackHandler(enabled = currentDestination == MainDestination.FEATURES) {
        returnToSend()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
            key = { destinations[it] },
        ) { page ->
            val destination = destinations[page]
            MainPage(
                destination = destination,
                connectedDeviceCount = connectedDeviceCount,
                onDevicesClick = onDevicesClick,
            ) {
                when (destination) {
                    MainDestination.SEND -> SendPage(activeDevice, onDevicesClick)
                    MainDestination.FILES -> FileManagementPage(
                        connectedDeviceCount = connectedDeviceCount,
                        onDevicesClick = onDevicesClick,
                        isVisible = pagerState.currentPage == page,
                        onBackFromRoot = ::returnToSend,
                    )
                    MainDestination.FEATURES -> FeatureListPage(
                        activeDevice = activeDevice,
                        onWifiClick = onWifiClick,
                        onSettingsClick = onSettingsClick,
                        onDeviceInfoClick = onDeviceInfoClick,
                        onFontsClick = onFontsClick,
                        onOpdsClick = onOpdsClick,
                        onSoftwareSettingsClick = onSoftwareSettingsClick,
                        onWallpapersClick = onWallpapersClick,
                    )
                }
            }
        }
        MainNavigation(
            destinations = destinations,
            selectedDestination = currentDestination,
            onDestinationClick = { destination ->
                coroutineScope.launch { pagerState.animateScrollToPage(destinations.indexOf(destination)) }
            },
        )
    }
}

@Composable
private fun MainPage(
    destination: MainDestination,
    connectedDeviceCount: Int,
    onDevicesClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            if (destination != MainDestination.FILES) {
                MainTopBar(destination, connectedDeviceCount, onDevicesClick)
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) { content() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTopBar(
    destination: MainDestination,
    connectedDeviceCount: Int,
    onDevicesClick: () -> Unit,
) {
    when (destination) {
        MainDestination.SEND -> TopAppBar(
            title = { Text("推书") },
            actions = { DeviceConnectionControl(connectedDeviceCount = connectedDeviceCount, onClick = onDevicesClick) },
        )
        MainDestination.FILES -> TopAppBar(
            title = { Text("文件管理") },
            actions = { DeviceConnectionControl(connectedDeviceCount = connectedDeviceCount, onClick = onDevicesClick) },
        )
        MainDestination.FEATURES -> TopAppBar(
            title = { Text("功能") },
            actions = { DeviceConnectionControl(connectedDeviceCount = connectedDeviceCount, onClick = onDevicesClick) },
        )
    }
}

@Composable
internal fun DeviceConnectionControl(connectedDeviceCount: Int, onClick: () -> Unit) {
    val description = if (connectedDeviceCount > 0) "$connectedDeviceCount 台设备已连接" else "未连接设备"
    IconButton(onClick = onClick) {
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_mobile_2),
                contentDescription = description,
                modifier = Modifier.size(24.dp),
            )
            Badge(
                modifier = Modifier.align(Alignment.BottomEnd).size(8.dp),
                containerColor = if (connectedDeviceCount > 0) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun MainNavigation(
    destinations: List<MainDestination>,
    selectedDestination: MainDestination,
    onDestinationClick: (MainDestination) -> Unit,
) {
    NavigationBar {
        destinations.forEach { destination ->
            NavigationBarItem(
                selected = selectedDestination == destination,
                onClick = { onDestinationClick(destination) },
                icon = {
                    when (destination) {
                        MainDestination.SEND -> Icon(Icons.AutoMirrored.Filled.Send, null)
                        MainDestination.FILES -> Icon(Icons.Default.Folder, null)
                        MainDestination.FEATURES -> Icon(Icons.Default.MoreHoriz, null)
                    }
                },
                label = { Text(destination.label) },
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun SendPage(
    activeDevice: ActiveDevice?,
    onDevicesClick: () -> Unit,
    incomingEvent: IncomingFilesEvent<List<BookUploadFile>>? = null,
    onIncomingFilesConsumed: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val uploadProgress by BookUploadQueue.state.collectAsState()
    var selectedFiles by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<List<BookUploadFile>>(emptyList())
    }
    var fontFamilyName by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var fontUploadRunning by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var fontProgress by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Pair<ULong, ULong>?>(null) }
    var pendingFontOverwrite by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<BookUploadFile?>(null) }
    var operationError by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var reconnectRequired by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var category by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(SendFileCategory.BOOK)
    }
    var selectedDirectory by androidx.compose.runtime.remember(activeDevice?.saved?.id) {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }
    var pendingReview by androidx.compose.runtime.remember(activeDevice?.saved?.id) {
        androidx.compose.runtime.mutableStateOf<BookUploadReview?>(null)
    }
    val directoryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            selectedDirectory = result.data?.getStringExtra(UploadDirectoryActivity.EXTRA_DIRECTORY)
        }
    }
    val canChooseDirectory = activeDevice?.profile?.let { profile ->
        profile.capabilities.contains("upload.target-directory") &&
            profile.constraints.canChooseUploadDirectory && profile.constraints.canListDirectories &&
            profile.capabilities.contains("files.list")
    } == true
    val fontExtensions = activeDevice?.profile?.fileFormats?.fontUploadExtensions
        ?.map { it.trim().removePrefix(".").lowercase(Locale.ROOT) }
        ?.filter(String::isNotBlank)
        .orEmpty()
    val canUploadFonts = activeDevice?.profile?.let { "fonts.upload" in it.capabilities } == true && fontExtensions.isNotEmpty()
    val fontFamilyRequired = activeDevice?.profile?.capabilities?.contains("fonts.upload.family") == true
    val fontProgressSupported = activeDevice?.profile?.capabilities?.contains("fonts.upload.progress") == true

    androidx.compose.runtime.LaunchedEffect(incomingEvent?.id, activeDevice?.saved?.id) {
        val incomingFiles = incomingEvent?.value.orEmpty()
        if (incomingFiles.isNotEmpty()) {
            selectedFiles = incomingFiles
            val extension = incomingFiles.singleOrNull()?.name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)
            category = when {
                incomingFiles.size == 1 && canUploadFonts && extension in fontExtensions -> SendFileCategory.FONT
                else -> SendFileCategory.BOOK
            }
            incomingEvent?.id?.let(onIncomingFilesConsumed)
        }
    }

    fun startUpload(review: BookUploadReview) {
        if (review.accepted.isEmpty()) return
        pendingReview = null
        val location = if (canChooseDirectory) {
            selectedDirectory?.let(uniffi.inkreaderlink_uniffi.SdkFileLocation::Directory)
                ?: uniffi.inkreaderlink_uniffi.SdkFileLocation.Root
        } else {
            uniffi.inkreaderlink_uniffi.SdkFileLocation.Root
        }
        BookUploadQueue.start(context, review.accepted, location, review.unsupportedNames)
    }

    fun uploadFont(file: BookUploadFile, overwrite: Boolean) {
        fontUploadRunning = true
        fontProgress = null
        operationError = null
        scope.launch {
            try {
                DeviceSessions.uploadFont(
                    context,
                    fontFamilyName.trim(),
                    file.uri,
                    file.name,
                    overwrite = overwrite,
                    onProgress = if (fontProgressSupported) { sent, total -> fontProgress = sent to total } else null,
                )
                pendingFontOverwrite = null
                android.widget.Toast.makeText(context, "字体上传完成", android.widget.Toast.LENGTH_LONG).show()
            } catch (cause: Exception) {
                if (!overwrite && cause is uniffi.inkreaderlink_uniffi.SdkOperationException.Conflict) {
                    pendingFontOverwrite = file
                } else {
                    operationError = managerError(cause)
                    reconnectRequired = DeviceSessions.isConnectionFailure(cause)
                }
            } finally {
                fontUploadRunning = false
            }
        }
    }

    val chooseBooks = rememberBookFilePicker { files ->
        if (files.isNotEmpty()) {
            selectedFiles = files
            val extension = files.singleOrNull()?.name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)
            category = when {
                files.size == 1 && canUploadFonts && extension in fontExtensions -> SendFileCategory.FONT
                else -> SendFileCategory.BOOK
            }
        }
    }
    val fontCandidate = selectedFiles.size == 1 && canUploadFonts &&
        selectedFiles.single().name.substringAfterLast('.', "").lowercase(Locale.ROOT) in fontExtensions
    val unreadableCount = selectedFiles.count { file ->
        val extension = file.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        activeDevice?.profile?.fileFormats?.readableExtensions?.none {
            it.trim().removePrefix(".").lowercase(Locale.ROOT) == extension
        } == true
    }
    val bookReview = activeDevice?.profile?.let { reviewBookFiles(selectedFiles, it) }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            SendSelectionContent(
                icon = Icons.Default.Smartphone,
                title = "设备",
                value = activeDevice?.saved?.let { DeviceSessions.addressFor(it) } ?: "连接设备",
                modifier = Modifier.fillMaxWidth().clickable(onClick = onDevicesClick).padding(16.dp),
            )
            Spacer(Modifier.height(20.dp))
                SendSelectionContent(
                    icon = Icons.Default.Description,
                    title = "选择发送的文件",
                    value = when (selectedFiles.size) {
                        0 -> null
                        1 -> selectedFiles.single().name
                        else -> "${selectedFiles.size} 个文件"
                    },
                    modifier = Modifier.fillMaxWidth().clickable(
                        enabled = !uploadProgress.running && !fontUploadRunning,
                        onClick = chooseBooks,
                    ).padding(16.dp),
                )
                if (selectedFiles.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    val categories = buildList {
                        add(SendFileCategory.BOOK)
                        if (canUploadFonts) add(SendFileCategory.FONT)
                    }
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        categories.forEachIndexed { index, choice ->
                            SegmentedButton(
                                selected = category == choice,
                                onClick = { category = choice },
                                enabled = !fontUploadRunning && when (choice) {
                                    SendFileCategory.BOOK -> true
                                    SendFileCategory.FONT -> true
                                },
                                shape = SegmentedButtonDefaults.itemShape(index, categories.size),
                                label = { Text(choice.label) },
                            )
                        }
                    }
                    if (category == SendFileCategory.FONT && canUploadFonts) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = fontFamilyName,
                            onValueChange = { fontFamilyName = it },
                            label = { Text(if (fontFamilyRequired) "字体族" else "字体族（可选）") },
                            singleLine = true,
                            enabled = !fontUploadRunning,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (category == SendFileCategory.BOOK) {
                        val warning = when {
                            bookReview?.unsupportedNames?.isNotEmpty() == true -> "设备不支持上传所选文件格式"
                            unreadableCount == 1 -> "此文件不在设备可阅读格式中"
                            unreadableCount > 1 -> "$unreadableCount 个文件不在设备可阅读格式中"
                            else -> null
                        }
                        if (warning != null) Text(
                            warning,
                            modifier = Modifier.padding(top = 12.dp, start = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (category == SendFileCategory.FONT && !fontCandidate) {
                        Text(
                            "请选择设备支持的字体文件",
                            modifier = Modifier.padding(top = 12.dp, start = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (category == SendFileCategory.BOOK && canChooseDirectory) {
                    Spacer(Modifier.height(20.dp))
                    SendSelectionContent(
                        icon = Icons.Default.Folder,
                        title = "接收目录",
                        value = selectedDirectory?.substringAfterLast('/')?.ifEmpty { "根目录" } ?: "默认目录",
                        modifier = Modifier.fillMaxWidth().clickable(
                            enabled = !uploadProgress.running,
                            onClick = { directoryLauncher.launch(Intent(context, UploadDirectoryActivity::class.java)) },
                        ).padding(16.dp),
                    )
                }
        }
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            if (fontUploadRunning) {
                val progress = fontProgress?.takeIf { it.second > 0uL }
                if (progress == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 8.dp))
                } else {
                    LinearProgressIndicator(
                        progress = { (progress.first.toDouble() / progress.second.toDouble()).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                }
                val progressLabel = when {
                    progress == null -> "正在上传字体"
                    progress.first >= progress.second -> "设备正在校验并保存字体"
                    else -> "正在上传字体 · ${(progress.first.toDouble() * 100 / progress.second.toDouble()).toInt()}%"
                }
                Text(progressLabel, modifier = Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodyMedium)
            } else {
                BookUploadStatus(Modifier.fillMaxWidth().padding(bottom = 8.dp))
            }
            Button(
                onClick = {
                    when (category) {
                        SendFileCategory.BOOK -> {
                            val review = bookReview ?: return@Button
                            if (review.unsupportedNames.isNotEmpty() || review.mayNotBeReadableNames.isNotEmpty()) {
                                pendingReview = review
                            } else {
                                startUpload(review)
                            }
                        }
                        SendFileCategory.FONT -> {
                            val selected = selectedFiles.singleOrNull() ?: return@Button
                            uploadFont(selected, overwrite = false)
                        }
                    }
                },
                enabled = selectedFiles.isNotEmpty() && !uploadProgress.running && !fontUploadRunning && when (category) {
                    SendFileCategory.BOOK -> activeDevice?.profile?.capabilities?.contains("files.upload") == true
                    SendFileCategory.FONT -> fontCandidate && (!fontFamilyRequired || fontFamilyName.isNotBlank())
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(when (category) {
                    SendFileCategory.FONT -> "上传字体"
                    SendFileCategory.BOOK -> "发送"
                })
            }
        }
    }

    pendingReview?.let { review ->
        BookUploadReviewDialog(
            review = review,
            onCancel = { pendingReview = null },
            onContinue = { startUpload(review) },
        )
    }
    pendingFontOverwrite?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingFontOverwrite = null },
            title = { Text("替换现有字体？") },
            text = { Text(file.name) },
            confirmButton = {
                TextButton(
                    enabled = !fontUploadRunning,
                    onClick = {
                        pendingFontOverwrite = null
                        uploadFont(file, overwrite = true)
                    },
                ) { Text("替换") }
            },
            dismissButton = {
                TextButton(
                    enabled = !fontUploadRunning,
                    onClick = { pendingFontOverwrite = null },
                ) { Text("取消") }
            },
        )
    }
    OperationErrorDialog(
        operationError,
        onDismiss = { operationError = null },
        title = "字体上传失败",
        reconnectToDevice = reconnectRequired,
    )
}

@Composable
private fun SendSelectionContent(
    icon: ImageVector,
    title: String,
    value: String?,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (value != null) {
                Text(
                    value,
                    modifier = Modifier.widthIn(max = 280.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.size(8.dp))
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private data class FeatureEntry(
    val title: String,
    val description: String? = null,
    val requiredCapabilities: Set<String> = emptySet(),
    val anyOfCapabilities: Set<String> = emptySet(),
    val availableWithoutDevice: Boolean = false,
    val icon: @Composable () -> Unit,
    val onClick: () -> Unit,
)

@Composable
private fun FeatureListPage(
    activeDevice: ActiveDevice?,
    onWifiClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onDeviceInfoClick: () -> Unit,
    onFontsClick: () -> Unit,
    onOpdsClick: () -> Unit,
    onSoftwareSettingsClick: () -> Unit,
    onWallpapersClick: () -> Unit,
) {
    val entries = listOf(
        FeatureEntry(
            title = "Wi-Fi 管理",
            description = "管理设备已保存的 Wi-Fi 网络",
            requiredCapabilities = setOf("wifi.list"),
            icon = { Icon(Icons.Default.Wifi, contentDescription = null) },
            onClick = onWifiClick,
        ),
        FeatureEntry(
            title = "设备设置",
            description = "查看和修改设备设置",
            requiredCapabilities = setOf("settings.list"),
            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
            onClick = onSettingsClick,
        ),
        FeatureEntry(
            title = "字体管理",
            description = "管理设备字体族",
            requiredCapabilities = setOf("fonts.list"),
            icon = { Icon(Icons.Default.FontDownload, contentDescription = null) },
            onClick = onFontsClick,
        ),
        FeatureEntry(
            title = "OPDS 管理",
            description = "管理 OPDS 书库",
            requiredCapabilities = setOf("opds.list"),
            icon = { Icon(Icons.Default.RssFeed, contentDescription = null) },
            onClick = onOpdsClick,
        ),
        FeatureEntry(
            title = "设备信息",
            description = "查看设备与固件信息",
            requiredCapabilities = setOf("device.info"),
            icon = { Icon(Icons.Default.Info, contentDescription = null) },
            onClick = onDeviceInfoClick,
        ),
        FeatureEntry(
            title = "壁纸管理",
            description = "查看和上传设备壁纸",
            anyOfCapabilities = setOf("wallpapers.upload", "wallpapers.manage"),
            icon = { Icon(Icons.Default.Image, contentDescription = null) },
            onClick = onWallpapersClick,
        ),
        FeatureEntry(
            title = "软件设置",
            availableWithoutDevice = true,
            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
            onClick = onSoftwareSettingsClick,
        ),
    )
    val declaredCapabilities = activeDevice?.profile?.capabilities?.toSet().orEmpty()
    val visibleEntries = entries.filter { entry ->
        entry.availableWithoutDevice ||
            (activeDevice != null &&
                declaredCapabilities.containsAll(entry.requiredCapabilities) &&
                (entry.anyOfCapabilities.isEmpty() || declaredCapabilities.any(entry.anyOfCapabilities::contains)))
    }

    LazyColumn {
        items(visibleEntries) { entry ->
            androidx.compose.material3.ListItem(
                headlineContent = { Text(entry.title) },
                supportingContent = entry.description?.let { description -> { Text(description) } },
                leadingContent = entry.icon,
                modifier = Modifier.fillMaxWidth().clickable(onClick = entry.onClick),
            )
        }
    }
}

class WifiManagementActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        DeviceSessions.initialize(applicationContext)
        setContent { PicoManageTheme { WifiManagementPage(onBack = ::finish) } }
    }
}

@Preview(showBackground = true)
@Composable
private fun PicoManagerPreview() {
    PicoManageTheme {
        MainShell(
            activeDevice = null,
            onDevicesClick = {},
            onWifiClick = {},
            onSettingsClick = {},
            onDeviceInfoClick = {},
            onFontsClick = {},
            onOpdsClick = {},
            onSoftwareSettingsClick = {},
            onWallpapersClick = {},
        )
    }
}
