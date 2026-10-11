package com.cold04.inkreadermgr

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cold04.inkreadermgr.ui.theme.PicoManageTheme
import kotlinx.coroutines.launch
import uniffi.inkreaderlink_uniffi.SdkConnectionField
import uniffi.inkreaderlink_uniffi.SdkConnectionFieldKind
import uniffi.inkreaderlink_uniffi.SdkConnectionFieldRetention
import uniffi.inkreaderlink_uniffi.SdkOperationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.util.concurrent.atomic.AtomicLong

class DeviceConnectionActivity : ComponentActivity() {
    companion object {
        const val EXTRA_DEVICE_ID = "device_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceSessions.initialize(applicationContext)
        enableEdgeToEdge()
        setContent {
            PicoManageTheme {
                DeviceConnectionPage(
                    existingId = intent.getStringExtra(EXTRA_DEVICE_ID),
                    onBack = ::finish,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicePage(onBack: () -> Unit) {
    val context = LocalContext.current
    val deviceState by DeviceSessions.state.collectAsState()
    val deviceNames = remember { DeviceSessions.supportedDevices().associate { it.deviceType to it.displayName } }
    val scope = rememberCoroutineScope()
    var connectingId by remember { mutableStateOf<String?>(null) }
    var operationFailure by remember { mutableStateOf<String?>(null) }
    var connectionFailure by remember { mutableStateOf<Pair<SavedDevice, Exception>?>(null) }
    var deleting by remember { mutableStateOf<SavedDevice?>(null) }
    var sessionSecretPrompt by remember { mutableStateOf<SessionSecretPrompt?>(null) }

    fun openEditor(saved: SavedDevice? = null) {
        context.startActivity(
            Intent(context, DeviceConnectionActivity::class.java).apply {
                saved?.let { putExtra(DeviceConnectionActivity.EXTRA_DEVICE_ID, it.id) }
            },
        )
    }

    fun connectDevice(saved: SavedDevice) {
        if (connectingId != null) return
        connectingId = saved.id
        scope.launch {
            try {
                connectWithSessionCredentials(saved) { sessionSecretPrompt = it }
            } catch (cause: Exception) {
                connectionFailure = saved to cause
            } finally {
                connectingId = null
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设备") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { openEditor() }) {
                Icon(Icons.Default.Add, contentDescription = "添加设备")
            }
        },
    ) { innerPadding ->
        if (deviceState.saved.isEmpty()) {
            Text(
                text = "尚未保存设备",
                modifier = Modifier.padding(innerPadding).padding(horizontal = 24.dp, vertical = 20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(innerPadding)) {
                items(deviceState.saved, key = { it.id }) { saved ->
                    val isActive = deviceState.active?.saved?.id == saved.id
                    ListItem(
                        headlineContent = { Text(DeviceSessions.addressFor(saved).ifBlank { "设备地址未设置" }) },
                        supportingContent = {
                            Column {
                                Text("固件：${deviceNames[saved.deviceType] ?: saved.deviceType}")
                                if (isActive) Text("已连接")
                                if (connectingId == saved.id) {
                                    Text("正在连接…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        },
                        trailingContent = {
                            Row {
                                IconButton(
                                    onClick = {
                                        if (isActive) {
                                            scope.launch {
                                                try {
                                                    DeviceSessions.disconnect()
                                                } catch (cause: Exception) {
                                                    operationFailure = connectionError(cause)
                                                }
                                            }
                                        } else connectDevice(saved)
                                    },
                                    enabled = connectingId == null || connectingId == saved.id,
                                ) {
                                    Icon(
                                        if (isActive) Icons.Default.LinkOff else Icons.Default.Link,
                                        contentDescription = if (isActive) "断开设备" else "连接设备",
                                    )
                                }
                                IconButton(onClick = { openEditor(saved) }) {
                                    Icon(Icons.Default.Edit, contentDescription = "编辑设备")
                                }
                                IconButton(onClick = { deleting = saved }) {
                                    Icon(Icons.Default.Delete, contentDescription = "移除设备")
                                }
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    deleting?.let { saved ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("移除设备？") },
            text = { Text(DeviceSessions.addressFor(saved)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        try {
                            DeviceSessions.remove(saved.id)
                        } catch (cause: Exception) {
                            operationFailure = connectionError(cause)
                        }
                    }
                }) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }

    connectionFailure?.let { (saved, cause) ->
        AlertDialog(
            onDismissRequest = { connectionFailure = null },
            title = { Text(connectionErrorTitle(cause)) },
            text = { Text("${connectionError(cause)}\n${DeviceSessions.addressFor(saved)}") },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        connectionFailure = null
                        connectDevice(saved)
                    }) { Text("重试") }
                    TextButton(onClick = {
                        connectionFailure = null
                        openEditor(saved)
                    }) { Text("编辑") }
                }
            },
            dismissButton = { TextButton(onClick = { connectionFailure = null }) { Text("关闭") } },
        )
    }

    OperationErrorDialog(message = operationFailure, onDismiss = { operationFailure = null })
    SessionSecretDialog(
        prompt = sessionSecretPrompt,
        onResult = { sessionSecretPrompt?.complete(it) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceConnectionPage(existingId: String?, onBack: () -> Unit) {
    val devices = remember { DeviceSessions.supportedDevices() }
    val savedDevices by DeviceSessions.state.collectAsState()
    val existing = savedDevices.saved.firstOrNull { it.id == existingId }
    var deviceType by rememberSaveable(existingId) {
        mutableStateOf(existing?.deviceType ?: devices.firstOrNull()?.deviceType.orEmpty())
    }
    val values = remember(existingId) {
        mutableStateMapOf<String, String>().apply { putAll(existing?.connectionValues.orEmpty()) }
    }
    val scope = rememberCoroutineScope()
    var typeMenuExpanded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var sessionSecretPrompt by remember { mutableStateOf<SessionSecretPrompt?>(null) }
    val definition = devices.firstOrNull { it.deviceType == deviceType }

    fun linkDevice() {
        if (busy || definition == null) return
        val missing = definition.connectionFields.firstOrNull { field ->
            field.required && field.kind != SdkConnectionFieldKind.Toggle && values[field.key].isNullOrBlank()
        }
        if (missing != null) {
            error = "请输入${missing.label}"
            return
        }
        val configuredValues = definition.connectionFields.mapNotNull { field ->
            val value = values[field.key] ?: if (field.kind == SdkConnectionFieldKind.Toggle) "false" else null
            value?.let { field.key to it }
        }.toMap()
        busy = true
        scope.launch {
            try {
                val saved = if (existingId == null) {
                    DeviceSessions.save(deviceType, configuredValues)
                } else {
                    DeviceSessions.update(existingId, deviceType, configuredValues)
                }
                if (connectWithSessionCredentials(saved) { sessionSecretPrompt = it } == null) return@launch
                onBack()
            } catch (cause: Exception) {
                error = connectionError(cause)
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existingId == null) "添加设备" else "编辑设备") },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = ::linkDevice, enabled = !busy && definition != null) {
                        Icon(Icons.Default.Link, contentDescription = "保存并连接")
                    }
                },
            )
        },
    ) { innerPadding ->
        if (devices.isEmpty()) {
            Text("SDK 未声明支持的设备", Modifier.padding(innerPadding).padding(24.dp))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (devices.size > 1) item(key = "device-type") {
                    ExposedDropdownMenuBox(
                        expanded = typeMenuExpanded,
                        onExpandedChange = { typeMenuExpanded = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        OutlinedTextField(
                            value = devices.firstOrNull { it.deviceType == deviceType }?.displayName.orEmpty(),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("固件类型") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeMenuExpanded) },
                            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                        )
                        ExposedDropdownMenu(
                            expanded = typeMenuExpanded,
                            onDismissRequest = { typeMenuExpanded = false },
                        ) {
                            devices.forEach { device ->
                                DropdownMenuItem(
                                    text = { Text(device.displayName) },
                                    onClick = {
                                        deviceType = device.deviceType
                                        typeMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
                if (definition != null) {
                    items(definition.connectionFields, key = { it.key }) { field ->
                        ConnectionFieldEditor(
                            field = field,
                            value = values[field.key].orEmpty(),
                            enabled = !busy,
                            onValueChange = { values[field.key] = it },
                        )
                    }
                }
            }
        }
        if (busy) CircularProgressIndicator(Modifier.padding(innerPadding).padding(20.dp))
    }

    OperationErrorDialog(error, onDismiss = { error = null }, title = "设备连接失败")
    SessionSecretDialog(
        prompt = sessionSecretPrompt,
        onResult = { sessionSecretPrompt?.complete(it) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionFieldEditor(
    field: SdkConnectionField,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    when (val kind = field.kind) {
        SdkConnectionFieldKind.Text, SdkConnectionFieldKind.Address -> {
            val context = LocalContext.current
            val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    result.data?.getStringExtra(ScanActivity.EXTRA_ADDRESS)?.let { onValueChange(it) }
                }
            }
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                label = { Text(field.label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (field.kind == SdkConnectionFieldKind.Address) KeyboardType.Uri else KeyboardType.Text,
                ),
                trailingIcon = if (field.kind == SdkConnectionFieldKind.Address) {
                    {
                        IconButton(onClick = {
                            scanLauncher.launch(Intent(context, ScanActivity::class.java))
                        }, enabled = enabled) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = "扫描二维码")
                        }
                    }
                } else null,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        is SdkConnectionFieldKind.Choice -> {
            var expanded by remember(field.key) { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                OutlinedTextField(
                    value = value.toIntOrNull()?.let(kind.options::getOrNull).orEmpty(),
                    onValueChange = {},
                    enabled = enabled,
                    readOnly = true,
                    label = { Text(field.label) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    kind.options.forEachIndexed { index, option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                onValueChange(index.toString())
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
        SdkConnectionFieldKind.Toggle -> {
            ListItem(
                headlineContent = { Text(field.label) },
                trailingContent = {
                    Switch(
                        checked = value.toBoolean(),
                        onCheckedChange = { onValueChange(it.toString()) },
                        enabled = enabled,
                    )
                },
            )
        }
        SdkConnectionFieldKind.Secret -> Unit
    }
}

private suspend fun connectWithSessionCredentials(
    saved: SavedDevice,
    updatePrompt: (SessionSecretPrompt?) -> Unit,
): ActiveDevice? {
    var credentials = emptyMap<String, String>()
    var rejectedCredential = false
    val fields = DeviceSessions.supportedDevices()
        .firstOrNull { it.deviceType == saved.deviceType }
        ?.connectionFields
        .orEmpty()

    while (true) {
        try {
            return DeviceSessions.connect(saved.id, credentials)
        } catch (cause: Exception) {
            val sdkError = generateSequence<Throwable>(cause) { it.cause }
                .filterIsInstance<SdkOperationException>()
                .firstOrNull()
            val key = when (sdkError) {
                is SdkOperationException.CredentialRequired -> {
                    rejectedCredential = false
                    sdkError.key
                }
                is SdkOperationException.CredentialRejected -> {
                    rejectedCredential = true
                    sdkError.key
                }
                else -> throw cause
            }
            val field = fields.firstOrNull {
                it.key == key && it.kind == SdkConnectionFieldKind.Secret &&
                    it.retention == SdkConnectionFieldRetention.SESSION
            } ?: throw cause
            val value = requestSessionSecret(field.label, rejectedCredential, updatePrompt) ?: return null
            credentials = credentials + (key to value)
        }
    }
}

private data class SessionSecretPrompt(
    val id: Long,
    val fieldLabel: String,
    val rejected: Boolean,
    val complete: (String?) -> Unit,
)

@Composable
private fun SessionSecretDialog(
    prompt: SessionSecretPrompt?,
    onResult: (String?) -> Unit,
) {
    var value by rememberSaveable(prompt?.id) { mutableStateOf("") }
    prompt ?: return

    AlertDialog(
        onDismissRequest = { onResult(null) },
        title = { Text(if (prompt.rejected) "访问码无效" else "需要设备访问码") },
        text = {
            Column {
                Text(if (prompt.rejected) "请检查设备上显示的临时访问码并重试。" else "请输入设备当前显示的临时访问码。")
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(prompt.fieldLabel) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onResult(value.trim()) }, enabled = value.isNotBlank()) {
                Text("连接")
            }
        },
        dismissButton = { TextButton(onClick = { onResult(null) }) { Text("取消") } },
    )
}

private suspend fun requestSessionSecret(
    fieldLabel: String,
    rejected: Boolean,
    updatePrompt: (SessionSecretPrompt?) -> Unit,
): String? = suspendCancellableCoroutine { continuation ->
    val prompt = SessionSecretPrompt(
        id = sessionSecretPromptIds.incrementAndGet(),
        fieldLabel = fieldLabel,
        rejected = rejected,
    ) { value ->
        if (continuation.isActive) continuation.resume(value)
        updatePrompt(null)
    }
    continuation.invokeOnCancellation { updatePrompt(null) }
    updatePrompt(prompt)
}

private val sessionSecretPromptIds = AtomicLong()

internal fun connectionError(cause: Throwable): String {
    val sdkError = generateSequence(cause) { it.cause }
        .filterIsInstance<SdkOperationException>()
        .firstOrNull()
    return when (sdkError) {
        is SdkOperationException.CredentialRequired -> "设备要求输入访问凭据：${sdkError.key}"
        is SdkOperationException.CredentialRejected -> "设备拒绝了访问凭据：${sdkError.key}"
        is SdkOperationException.InvalidArgument -> sdkError.detail
        is SdkOperationException.Unsupported -> sdkError.detail
        is SdkOperationException.Conflict -> sdkError.detail
        is SdkOperationException.RemoteFailure -> sdkError.detail
        is SdkOperationException.CommittedWithWarning -> sdkError.detail
        is SdkOperationException.CommittedButCleanupFailed -> sdkError.detail
        is SdkOperationException.RecoveryFailed -> sdkError.detail
        is SdkOperationException.Unreachable -> "无法连接设备，请检查地址和网络"
        is SdkOperationException.Timeout -> "连接超时，请确认设备处于传书模式"
        is SdkOperationException.InsufficientStorage -> "设备存储空间不足"
        null -> when (cause) {
            is IllegalArgumentException -> cause.message ?: "输入无效"
            else -> cause.message?.takeIf(String::isNotBlank)
                ?: generateSequence(cause.cause) { it.cause }
                    .mapNotNull { it.message?.takeIf(String::isNotBlank) }
                    .firstOrNull()
                ?: "连接失败，请重试"
        }
    }
}

private fun connectionErrorTitle(cause: Exception): String {
    val sdkError = generateSequence<Throwable>(cause) { it.cause }
        .filterIsInstance<SdkOperationException>()
        .firstOrNull()
    return if (sdkError is SdkOperationException.Timeout) "连接超时" else "连接失败"
}
