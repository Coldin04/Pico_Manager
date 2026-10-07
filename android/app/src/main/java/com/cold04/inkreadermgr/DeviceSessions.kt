package com.cold04.inkreadermgr

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.File
import uniffi.inkreaderlink_uniffi.SdkDeviceClient
import uniffi.inkreaderlink_uniffi.SdkDeviceInfoField
import uniffi.inkreaderlink_uniffi.SdkDeviceProfile
import uniffi.inkreaderlink_uniffi.SdkFileLocation
import uniffi.inkreaderlink_uniffi.SdkFileEntry
import uniffi.inkreaderlink_uniffi.SdkFileDownload
import uniffi.inkreaderlink_uniffi.SdkConflictPolicy
import uniffi.inkreaderlink_uniffi.SdkUploadOptions
import uniffi.inkreaderlink_uniffi.SdkUploadProgressObserver
import uniffi.inkreaderlink_uniffi.SdkOperationException
import uniffi.inkreaderlink_uniffi.SdkWifiCredential
import uniffi.inkreaderlink_uniffi.SdkWifiNetwork
import uniffi.inkreaderlink_uniffi.SdkFontCatalog
import uniffi.inkreaderlink_uniffi.SdkWallpaperUploadResult
import uniffi.inkreaderlink_uniffi.SdkOpdsCredential
import uniffi.inkreaderlink_uniffi.SdkOpdsServer
import uniffi.inkreaderlink_uniffi.SdkSettingsSnapshot
import uniffi.inkreaderlink_uniffi.SdkSettingChange
import uniffi.inkreaderlink_uniffi.BooksendSdk
import uniffi.inkreaderlink_uniffi.SdkConnectionFieldKind
import uniffi.inkreaderlink_uniffi.SdkConnectionParameter
import uniffi.inkreaderlink_uniffi.SdkConnectionValue
import uniffi.inkreaderlink_uniffi.SdkSupportedDevice
import java.util.UUID

data class SavedDevice(val id: String, val deviceType: String, val connectionValues: Map<String, String>)
data class ActiveDevice(val saved: SavedDevice, val profile: SdkDeviceProfile)
data class DeviceState(val saved: List<SavedDevice> = emptyList(), val active: ActiveDevice? = null)

/** Saved connection fields are persistent; the live SDK client exists only in this process. */
object DeviceSessions {
    private const val PREFS_NAME = "devices"
    private const val SAVED_KEY = "saved"
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(DeviceState())
    val state = mutableState.asStateFlow()
    private var appContext: Context? = null
    private var client: SdkDeviceClient? = null
    private val supportedDevices: List<SdkSupportedDevice> by lazy { BooksendSdk().use { it.supportedDevices() } }

    fun supportedDevices(): List<SdkSupportedDevice> = supportedDevices

    fun addressFor(saved: SavedDevice): String {
        val addressKey = supportedDevices.firstOrNull { it.deviceType == saved.deviceType }
            ?.connectionFields?.firstOrNull { it.kind == SdkConnectionFieldKind.Address }?.key
        return addressKey?.let(saved.connectionValues::get).orEmpty()
    }

    @Synchronized
    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val data = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(SAVED_KEY, "[]")
        val saved = try {
            val array = JSONArray(data)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                val values = item.optJSONObject("connectionValues")?.let { savedValues ->
                    buildMap {
                        val keys = savedValues.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            put(key, savedValues.optString(key))
                        }
                    }
                } ?: mapOf("address" to item.optString("address", ""))
                SavedDevice(item.getString("id"), item.getString("deviceType"), values)
            }
        } catch (_: Exception) {
            emptyList()
        }
        mutableState.value = DeviceState(saved = saved)
    }

    suspend fun save(deviceType: String, values: Map<String, String>): SavedDevice = withContext(Dispatchers.IO) {
        val target = normalizeConnectionValues(deviceType, values)
        mutex.withLock {
            mutableState.value.saved.firstOrNull { it.deviceType == deviceType && it.connectionValues == target }?.let {
                return@withLock it
            }
            val saved = SavedDevice(UUID.randomUUID().toString(), deviceType, target)
            val updated = mutableState.value.saved + saved
            persist(updated)
            mutableState.value = mutableState.value.copy(saved = updated)
            saved
        }
    }

    suspend fun update(id: String, deviceType: String, values: Map<String, String>): SavedDevice = withContext(Dispatchers.IO) {
        val target = normalizeConnectionValues(deviceType, values)
        mutex.withLock {
            val previous = mutableState.value.saved.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("设备不存在")
            if (previous.deviceType == deviceType && previous.connectionValues == target) return@withLock previous
            require(mutableState.value.saved.none {
                it.id != id && it.deviceType == deviceType && it.connectionValues == target
            }) { "该设备已保存" }

            val updatedDevice = previous.copy(deviceType = deviceType, connectionValues = target)
            val updated = mutableState.value.saved.map { if (it.id == id) updatedDevice else it }
            persist(updated)
            val wasActive = mutableState.value.active?.saved?.id == id
            if (wasActive) {
                client?.close()
                client = null
            }
            mutableState.value = mutableState.value.copy(
                saved = updated,
                active = mutableState.value.active?.takeUnless { wasActive },
            )
            updatedDevice
        }
    }

    suspend fun connect(id: String): ActiveDevice = withContext(Dispatchers.IO) {
        mutex.withLock {
            val saved = mutableState.value.saved.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("设备不存在")
            mutableState.value.active?.takeIf { it.saved.id == id }?.let { return@withLock it }

            // One live client at a time, including while switching devices.
            client?.close()
            client = null
            mutableState.value = mutableState.value.copy(active = null)

            val definition = supportedDevices.firstOrNull { it.deviceType == saved.deviceType }
                ?: throw IllegalArgumentException("SDK 未声明此设备类型")
            val parameters = definition.connectionFields.mapNotNull { field ->
                val value = saved.connectionValues[field.key]
                    ?: (if (field.kind == SdkConnectionFieldKind.Toggle) "false" else null)
                    ?: return@mapNotNull null
                val typedValue = when (val kind = field.kind) {
                    SdkConnectionFieldKind.Text -> SdkConnectionValue.Text(value)
                    SdkConnectionFieldKind.Address -> SdkConnectionValue.Address(value)
                    is SdkConnectionFieldKind.Choice -> SdkConnectionValue.Choice(
                        value.toUIntOrNull() ?: throw IllegalArgumentException("请选择${field.label}"),
                    )
                    SdkConnectionFieldKind.Toggle -> SdkConnectionValue.Toggle(
                        value.toBooleanStrictOrNull() ?: throw IllegalArgumentException("${field.label}的值无效"),
                    )
                }
                SdkConnectionParameter(field.key, typedValue)
            }
            val next = SdkDeviceClient.connectAndVerifyWithParameters(
                saved.deviceType,
                parameters,
                5_000uL,
            )
            try {
                val profile = next.profile()
                val active = ActiveDevice(saved, profile)
                client = next
                mutableState.value = mutableState.value.copy(active = active)
                active
            } catch (error: Exception) {
                next.close()
                throw error
            }
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        mutex.withLock {
            client?.close()
            client = null
            mutableState.value = mutableState.value.copy(active = null)
        }
    }

    suspend fun listFiles(location: SdkFileLocation): List<SdkFileEntry> = withContext(Dispatchers.IO) {
        withDeviceClient("files.list") { it.listFiles(location) }
    }

    suspend fun deviceInfo(): List<SdkDeviceInfoField> = withContext(Dispatchers.IO) {
        withDeviceClient("device.info") { it.deviceInfo() }
    }

    suspend fun deleteFile(path: String) = modify("files.delete") { it.delete(path) }

    suspend fun deleteFiles(paths: List<String>) = modify("files.delete") { it.deleteFiles(paths) }

    suspend fun renameFile(path: String, newName: String) = modify("files.rename") { it.rename(path, newName) }

    suspend fun moveFile(path: String, destination: String) = modify("files.move") { it.moveFile(path, destination) }

    suspend fun moveFiles(paths: List<String>, destination: String) = modify("files.move") {
        it.moveFiles(paths, destination)
    }

    suspend fun createDirectory(parent: String, name: String) = modify("directories.create") {
        it.createDirectory(parent, name)
    }

    suspend fun downloadFile(path: String, destination: String) = modify("files.download") {
        it.download(path, destination)
    }

    suspend fun downloadFiles(files: List<SdkFileDownload>) = modify("files.download") {
        it.downloadFiles(files)
    }

    suspend fun listWifiNetworks(): List<SdkWifiNetwork> = withContext(Dispatchers.IO) {
        withDeviceClient("wifi.list") { it.listWifiNetworks() }
    }

    suspend fun saveWifiNetwork(credential: SdkWifiCredential) = modify("wifi.save") {
        it.saveWifiNetwork(credential)
    }

    suspend fun deleteWifiNetwork(index: UInt?) = modify("wifi.delete") {
        it.deleteWifiNetwork(index)
    }

    suspend fun listFonts(): SdkFontCatalog = withContext(Dispatchers.IO) {
        withDeviceClient("fonts.list") { it.listFonts() }
    }

    suspend fun uploadFont(
        context: Context,
        family: String,
        uri: Uri,
        fileName: String,
        overwrite: Boolean = false,
        onProgress: ((ULong, ULong) -> Unit)? = null,
    ) =
        withDeviceClient("fonts.upload") { sdkClient ->
                val profile = mutableState.value.active?.profile ?: throw IOException("设备未连接")
                if (onProgress != null && "fonts.upload.progress" !in profile.capabilities) {
                    throw IOException("设备未声明字体上传进度支持")
                }
                val supportedExtensions = profile.fileFormats.fontUploadExtensions
                    .map { it.trim().removePrefix(".").lowercase(java.util.Locale.ROOT) }
                    .filter(String::isNotBlank)
                    .toSet()
                val extension = fileName.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
                if (extension !in supportedExtensions) {
                    val accepted = supportedExtensions.joinToString { ".$it" }
                    throw IOException(
                        if (accepted.isEmpty()) "设备未声明支持的字体文件类型"
                        else "设备支持的字体文件类型：$accepted",
                    )
                }
                val temporary = File.createTempFile("inkreader-font-", ".$extension", context.cacheDir)
                try {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw IOException("无法读取字体文件")
                    input.buffered().use { source ->
                        temporary.outputStream().buffered().use { destination ->
                            source.copyTo(destination, 64 * 1024)
                        }
                    }
                    if (onProgress == null) {
                        sdkClient.uploadFontWithOverwrite(family, temporary.absolutePath, fileName, overwrite)
                    } else {
                        val observer = object : SdkUploadProgressObserver {
                            override fun onProgress(sentBytes: ULong, totalBytes: ULong) {
                                onProgress(sentBytes, totalBytes)
                            }
                        }
                        sdkClient.uploadFontWithOverwriteAndProgress(
                            family,
                            temporary.absolutePath,
                            fileName,
                            overwrite,
                            observer,
                        )
                    }
                } finally {
                    temporary.delete()
                }
        }

    suspend fun deleteFontFamily(family: String) = modify("fonts.delete") {
        it.deleteFontFamily(family)
    }

    suspend fun listWallpapers(): List<SdkFileEntry> = withContext(Dispatchers.IO) {
        withDeviceClient("wallpapers.manage") { it.listWallpapers() }
    }

    suspend fun uploadWallpaper(
        context: Context,
        uri: Uri,
        fileName: String,
        overwrite: Boolean,
        applyToLockScreen: Boolean,
    ): SdkWallpaperUploadResult = withDeviceClient("wallpapers.upload") { sdkClient ->
        val profile = mutableState.value.active?.profile ?: throw IOException("设备未连接")
        val supportedExtensions = profile.fileFormats.wallpaperUploadExtensions
            .map { it.trim().removePrefix(".").lowercase(java.util.Locale.ROOT) }
            .filter(String::isNotBlank)
            .toSet()
        val extension = fileName.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
        if (extension !in supportedExtensions) {
            val accepted = supportedExtensions.joinToString { ".$it" }
            throw IOException(
                if (accepted.isEmpty()) "设备未声明支持的壁纸图片类型"
                else "设备支持的壁纸图片类型：$accepted",
            )
        }

        val temporary = File.createTempFile("inkreader-wallpaper-", ".$extension", context.cacheDir)
        try {
            val input = if (uri.scheme == "file") {
                uri.path?.let(::File)?.inputStream()
            } else {
                context.contentResolver.openInputStream(uri)
            } ?: throw IOException("无法读取壁纸图片")
            input.buffered().use { source ->
                temporary.outputStream().buffered().use { destination ->
                    source.copyTo(destination, 64 * 1024)
                }
            }
            sdkClient.uploadWallpaper(
                temporary.absolutePath,
                fileName,
                overwrite,
                applyToLockScreen,
            )
        } finally {
            temporary.delete()
        }
    }

    suspend fun deleteWallpaper(fileName: String) = modify("wallpapers.delete") {
        it.deleteWallpaper(fileName)
    }

    suspend fun listOpdsServers(): List<SdkOpdsServer> = withContext(Dispatchers.IO) {
        withDeviceClient("opds.list") { it.listOpdsServers() }
    }

    suspend fun listSettings(): SdkSettingsSnapshot = withContext(Dispatchers.IO) {
        withDeviceClient("settings.list") { it.listSettings() }
    }

    suspend fun applySettings(
        expected: SdkSettingsSnapshot,
        changes: List<SdkSettingChange>,
    ): SdkSettingsSnapshot = withContext(Dispatchers.IO) {
        withDeviceClient("settings.update") { it.applySettings(expected, changes) }
    }

    suspend fun saveOpdsServer(credential: SdkOpdsCredential) = modify("opds.save") {
        it.saveOpdsServer(credential)
    }

    suspend fun deleteOpdsServer(index: UInt) = modify("opds.delete") {
        it.deleteOpdsServer(index)
    }

    suspend fun uploadBatch(
        context: Context,
        files: List<BookUploadFile>,
        location: SdkFileLocation,
        onProgress: (Int, BookUploadFile, ULong?, ULong?) -> Unit,
    ): List<BookUploadOutcome> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val active = mutableState.value.active ?: throw IllegalStateException("设备未连接")
            val profile = active.profile
            check(profile.capabilities.contains("files.upload")) { "设备不支持上传文件" }
            val sdkClient = checkNotNull(client) { "设备未连接" }
            val supportsWebsocket = profile.capabilities.contains("upload.websocket")
            val canChooseDirectory = profile.capabilities.contains("upload.target-directory") &&
                profile.constraints.canChooseUploadDirectory
            val uploadLocation = if (canChooseDirectory) location else SdkFileLocation.Root
            Log.i("InkReaderUpload", "Starting batch: count=${files.size}, location=$uploadLocation, websocket=$supportsWebsocket")
            val acceptedExtensions = profile.fileFormats.uploadExtensions.map {
                it.trim().removePrefix(".").lowercase(java.util.Locale.ROOT)
            }.toSet()
            var connectionFailure = false

            files.mapIndexed { index, file ->
                if (client !== sdkClient) {
                    connectionFailure = true
                    return@mapIndexed BookUploadOutcome(file.name, false, "设备连接已断开", true)
                }
                if (connectionFailure) {
                    return@mapIndexed BookUploadOutcome(file.name, false, "设备连接异常，未继续上传", true)
                }
                onProgress(index, file, null, null)
                val extension = file.name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
                if (!profile.fileFormats.acceptsAnyUploadFormat && extension !in acceptedExtensions) {
                    return@mapIndexed BookUploadOutcome(file.name, false, "设备不支持此文件格式")
                }
                var temporary: File? = null
                var stage = "创建临时缓存"
                try {
                    val cacheFile = File.createTempFile("inkreader-upload-", ".tmp", context.cacheDir)
                    temporary = cacheFile
                    stage = "读取所选文件"
                    val input = context.contentResolver.openInputStream(file.uri)
                        ?: throw IOException("无法读取所选文件")
                    input.buffered().use { source ->
                        cacheFile.outputStream().buffered().use { destination -> source.copyTo(destination, 64 * 1024) }
                    }
                    stage = "SDK 上传"
                    Log.i("InkReaderUpload", "Calling SDK upload: name=${file.name}, bytes=${cacheFile.length()}, location=$uploadLocation")
                    val observer = if (supportsWebsocket) object : SdkUploadProgressObserver {
                        override fun onProgress(sentBytes: ULong, totalBytes: ULong) {
                            onProgress(index, file, sentBytes, totalBytes)
                        }
                    } else null
                    val upload: suspend (SdkConflictPolicy) -> Unit = { conflictPolicy ->
                        sdkClient.upload(
                            cacheFile.absolutePath,
                            file.name,
                            uploadLocation,
                            SdkUploadOptions(
                                conflictPolicy = conflictPolicy,
                                contentType = file.contentType,
                                preferWebsocket = supportsWebsocket,
                            ),
                            observer,
                        )
                    }
                    try {
                        upload(SdkConflictPolicy.OVERWRITE_WHEN_SUPPORTED)
                    } catch (cause: SdkOperationException.Unsupported) {
                        upload(SdkConflictPolicy.REPLACE_WITH_BACKUP)
                    }
                    Log.i("InkReaderUpload", "SDK upload succeeded: name=${file.name}")
                    BookUploadOutcome(file.name, true)
                } catch (cause: CancellationException) {
                    throw cause
                } catch (cause: Exception) {
                    val reconnectRequired = isConnectionFailure(cause)
                    connectionFailure = connectionFailure || reconnectRequired
                    val reason = describeUploadFailure(cause)
                    Log.e("InkReaderUpload", "Upload failed at stage=$stage: name=${file.name}, reason=$reason", cause)
                    val userMessage = if (stage == "SDK 上传") reason else "${stage}失败：$reason"
                    BookUploadOutcome(file.name, false, userMessage, reconnectRequired)
                } finally {
                    temporary?.delete()
                }
            }
        }
    }

    private suspend fun modify(capability: String, operation: suspend (SdkDeviceClient) -> Unit) = withContext(Dispatchers.IO) {
        withDeviceClient(capability, operation)
    }

    private suspend fun <T> withDeviceClient(
        capability: String,
        operation: suspend (SdkDeviceClient) -> T,
    ): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            checkCapability(capability)
            val current = checkNotNull(client) { "设备未连接" }
            try {
                operation(current)
            } catch (cause: Exception) {
                throw cause
            }
        }
    }

    fun isConnectionFailure(cause: Throwable): Boolean = generateSequence(cause) { it.cause }
        .filterIsInstance<SdkOperationException>()
        .any { it is SdkOperationException.Unreachable || it is SdkOperationException.Timeout }

    fun describeOperationFailure(cause: Throwable, operation: String): String {
        val sdkError = generateSequence(cause) { it.cause }
            .filterIsInstance<SdkOperationException>()
            .firstOrNull()
        return when (sdkError) {
            is SdkOperationException.Unreachable -> "暂时无法访问设备，请检查网络后重试"
            is SdkOperationException.Timeout -> "设备响应超时，请稍后重试"
            is SdkOperationException.InvalidArgument -> "输入无效：${sdkError.detail}"
            is SdkOperationException.Unsupported -> "设备不支持此操作：${sdkError.detail}"
            is SdkOperationException.RemoteFailure -> "设备操作失败：${sdkError.detail}"
            is SdkOperationException.Conflict -> "设备内容已变化，请刷新后重试：${sdkError.detail}"
            is SdkOperationException.InsufficientStorage -> "设备存储空间不足"
            is SdkOperationException.CommittedWithWarning -> "操作已提交，但设备报告警告：${sdkError.detail}"
            is SdkOperationException.CommittedButCleanupFailed -> "操作已完成，但设备清理失败：${sdkError.detail}"
            is SdkOperationException.RecoveryFailed -> "操作失败且设备恢复未完成：${sdkError.detail}"
            null -> cause.message?.takeIf(String::isNotBlank)
                ?: cause.cause?.message?.takeIf(String::isNotBlank)
                ?: "${operation}失败"
        }
    }

    fun describeUploadFailure(cause: Throwable): String {
        val sdkError = generateSequence(cause) { it.cause }.filterIsInstance<SdkOperationException>().firstOrNull()
        return when (sdkError) {
            is SdkOperationException.InvalidArgument -> "上传参数无效：${sdkError.detail}"
            is SdkOperationException.Unsupported -> "设备不支持此上传方式：${sdkError.detail}"
            is SdkOperationException.Unreachable -> "设备无法访问，请检查设备连接和网络"
            is SdkOperationException.Timeout -> "上传超时，请检查设备连接后重试"
            is SdkOperationException.Conflict -> "设备中已存在同名文件：${sdkError.detail}"
            is SdkOperationException.InsufficientStorage -> "设备存储空间不足"
            is SdkOperationException.RemoteFailure -> "设备拒绝了上传：${sdkError.detail}"
            is SdkOperationException.CommittedWithWarning -> "文件已上传，但设备报告警告：${sdkError.detail}"
            is SdkOperationException.CommittedButCleanupFailed -> "文件已上传，但设备清理临时数据失败：${sdkError.detail}"
            is SdkOperationException.RecoveryFailed -> "上传失败且设备恢复未完成：${sdkError.detail}"
            null -> cause.message?.takeIf(String::isNotBlank)
                ?: cause.cause?.message?.takeIf(String::isNotBlank)
                ?: "${cause::class.simpleName ?: "未知错误"}（详情见 Logcat）"
        }
    }

    private fun checkCapability(capability: String) {
        check(mutableState.value.active?.profile?.capabilities?.contains(capability) == true) {
            "设备不支持此操作"
        }
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = mutableState.value.saved.filterNot { it.id == id }
            persist(updated)
            if (mutableState.value.active?.saved?.id == id) {
                client?.close()
                client = null
            }
            mutableState.value = mutableState.value.copy(
                saved = updated,
                active = mutableState.value.active?.takeUnless { it.saved.id == id },
            )
        }
    }

    private fun persist(savedDevices: List<SavedDevice>) {
        val array = JSONArray()
        savedDevices.forEach { saved ->
            val values = JSONObject()
            saved.connectionValues.forEach { (key, value) -> values.put(key, value) }
            array.put(
                JSONObject()
                    .put("id", saved.id)
                    .put("deviceType", saved.deviceType)
                    .put("address", addressFor(saved))
                    .put("connectionValues", values),
            )
        }
        val stored = checkNotNull(appContext).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(SAVED_KEY, array.toString()).commit()
        if (!stored) throw IOException("无法保存设备列表")
    }

    private fun normalizeConnectionValues(deviceType: String, values: Map<String, String>): Map<String, String> {
        val definition = supportedDevices.firstOrNull { it.deviceType == deviceType }
            ?: throw IllegalArgumentException("SDK 未声明此设备类型")
        val declaredFields = definition.connectionFields.associateBy { it.key }
        require(values.keys.all(declaredFields::containsKey)) { "包含 SDK 未声明的连接字段" }
        val normalized = values.mapValues { (_, value) -> value.trim() }.toMutableMap()
        definition.connectionFields.forEach { field ->
            if (field.kind == SdkConnectionFieldKind.Toggle && field.key !in normalized) {
                normalized[field.key] = "false"
            }
            val value = normalized[field.key]
            if (field.required && field.kind != SdkConnectionFieldKind.Toggle) {
                require(!value.isNullOrBlank()) { "请输入${field.label}" }
            }
            when (val kind = field.kind) {
                is SdkConnectionFieldKind.Choice -> value?.let {
                    val index = it.toIntOrNull()
                    require(index != null && index in kind.options.indices) { "请选择${field.label}" }
                }
                SdkConnectionFieldKind.Toggle -> value?.let {
                    require(it.toBooleanStrictOrNull() != null) { "${field.label}的值无效" }
                }
                SdkConnectionFieldKind.Text, SdkConnectionFieldKind.Address -> Unit
            }
        }
        return normalized
    }
}
