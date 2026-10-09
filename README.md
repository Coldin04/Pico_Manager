<div align="center">
  <img src="android/app/src/main/ic_launcher-playstore.png" alt="Pico Manager 应用图标" width="128">
  <h1>Pico Manager</h1>
  <p><strong>专注阅读，轻松管理。</strong></p>
  <p>原生 Android / iOS 应用 · 多固件适配 · 由 InkReaderLink 驱动</p>
  <p>
    <a href="https://github.com/Coldin04/Pico_Manager/releases">下载</a>
    · <a href="#设备连接">了解设备连接</a>
    · <a href="https://github.com/Coldin04/InkReaderLink">查看 InkReaderLink</a>
  </p>
  iOS版本暂未上架，可下载ipa自签
</div>

Pico Manager 是一款可供多种固件使用的墨水屏管理应用，支持Android 与 iOS。

- **连接设备并推书**：通过地址或二维码可轻松连接多种设备，更快的推送你的书籍。
- **管理设备内容**：支持管理多种固件，轻松调节你的设备设置。

功能入口由 [InkReaderLink](https://github.com/Coldin04/InkReaderLink) 的设备能力决定，不支持的功能不会出现在对应设备的界面中。

Copyright (C) 2026 Coldin04

## 兼容性声明

本项目对第三方固件或设备的兼容，不代表其作者或权利人对本项目的认可、赞助或隶属关系。项目名称中的 `Pico` 意为“小”，旨在做一个方便管理小尺寸墨水屏设备（未来可能扩展支持更多阅读设备）的工具。不表示本项目是[官方 Read Pico 应用](https://dot.mindreset.tech/docs/read_0) ，也不表示本项目与 Read Pico 存在第一方关系。

## 许可证

源代码采用 GNU General Public License v3.0 授权，详见 [LICENSE](LICENSE) 和 [NOTICE](NOTICE)。

项目名称、Logo、图标和官方展示素材不在 GPLv3 的授权范围内，详见 [TRADEMARKS.md](TRADEMARKS.md)。


## 设备连接

Android 的设备列表从 InkReaderLink SDK 获取支持的固件和显示名称。添加或编辑设备会打开独立配置 Activity，按 SDK 返回的连接字段动态显示文本输入、下拉选择或开关；右上角链接按钮会保存配置并发起连接。地址字段保留相机扫码和相册二维码识别，扫码结果写回 SDK 声明的地址字段。

设备连接字段随设备记录保存。升级后首次读取旧记录时，会把原有地址迁移到 `address` 字段。

## Android release 签名

Android `applicationId` 和 `namespace` 为 `com.cold04.inkreadermgr`。旧预览版使用不同 ID，不能原位升级。

本地 release 构建读取 `android/keystore.properties` 指定的密钥；CI 可通过环境变量提供同一套签名信息。

本地 debug 和 release APK 使用同一签名及 `applicationId`，以便在版本号允许时互相覆盖安装。Play App Signing 分发的安装包可能使用不同于本地上传密钥的证书，不能据此保证与本地 debug 包互相覆盖。

## Android Public 发布

只有推送 `Android-vX.Y.Z`、`Android-vX.Y.Z-preview` 或 `Android-vX.Y.Z-previewN` tag 时，GitHub Actions 才按该 tag 的代码编译签名 APK、生成更新日志并创建同名 Draft Release。检查 APK 和说明后，可在 GitHub Releases 页面编辑说明并发布；带 `-preview` 的版本会标为预发布。其他 tag（包括 `vX.Y.Z` 和 `iOS-vX.Y.Z`）不会触发此工作流。

软件设置页通过 GitHub Releases 检测已发布的 `Android-v*` 版本；默认只检查稳定版，可选包含预览版。每次点击“检测更新”都会重新查询 Release，检测结果显示在条目副标题；发现更新时弹窗展示按 Markdown 排版的 Release 正文。更新时会按手机支持的 ABI 优先下载对应 APK，没有匹配包时回退到 universal APK，并交给 Android 系统安装器确认安装。若尚未允许本 App 安装应用，会弹窗引导前往系统设置，开启后返回会继续安装。已下载但未安装的 APK 会留在缓存中；再次点击“检测更新”时，若仍是最新 Release 则直接安装，若出现更新的 Release 则展示新版本。系统确认安装版本已更新后会清除缓存。Draft Release 或没有适配 APK 的 Release 不会作为更新显示。

App 每次回到前台时会按编译配置的间隔自动检查更新，默认间隔为 2 天；可在编译前调整 `android/gradle.properties` 中的 `appUpdateCheckIntervalDays`。自动检查只在发现新版本时展示更新页。

仓库 Actions Secrets 需要设置 `ANDROID_KEYSTORE_BASE64`（签名密钥文件的 Base64 内容）、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS` 和 `ANDROID_KEY_PASSWORD`。CI 应使用与本地发布相同的签名密钥，以便已安装版本升级。tag 使用 `X.Y.Z` 数字版本，主版本号不超过 20，次版本号和补丁号不超过 999；预览序号为 1 到 98（省略序号时按 1 处理）。版本号会写入 APK 的 `versionName` 和 `versionCode`，同一基础版本的正式版 `versionCode` 高于所有预览版。

每次发布包含 Gradle 默认命名的 `app-arm64-v8a-release.apk`、`app-armeabi-v7a-release.apk`、`app-x86_64-release.apk`、`app-universal-release.apk`，以及 `SHA256SUMS.txt`。工作流会在上传前核对全部 APK 的签名、版本号和 SHA-256 校验值；App 下载和再次安装缓存包前也会校验 SHA-256。

## Android 提交检查

Android 提交检查只在推送到 `master`，或 Pull Request 的目标分支为 `master` 时运行；单独推送功能分支不会启动检查。检查覆盖代码和文档改动，按固定 SDK commit 构建或缓存 InkReaderLink AAR，运行 Android 单元测试并组装 debug APK，同时校验 APK 的 applicationId 和原生库。CI 使用 Android 默认 debug keystore，不需要 Release keystore；本地 debug 构建默认继续使用 Release 签名，以保留安装升级兼容性。同一分支或 PR 的新提交会取消尚未完成的旧检查。来自 fork 的 PR 也符合触发条件；仓库管理员应在 GitHub Actions 设置中要求首次贡献者审批后再运行工作流。工作流仅有 `contents: read` 权限，不构建 Release APK 或创建 Release；发布仍由匹配的版本 tag 触发。

## Android InkReaderLink 依赖与开发

App 使用独立的 **InkReaderLink** Android 库 `com.cold04:inkreaderlink-uniffi`，并通过 `android/gradle.properties` 中的 `inkreaderlinkSdkVersion` 固定其版本。版本形式决定 SDK 来源：

下表中的版本号仅为格式示例，不代表当前或已发布版本。

| 版本形式 | 来源与用途 |
| --- | --- |
| `0.2.0`、`0.2.0-preview.3` | 从 Maven Central 获取已发布版本。 |
| `git.<完整40位commit SHA>` | 按 InkReaderLink 仓库中的指定 commit 构建。GitHub 发布工作流会拉取该 commit、构建 AAR 并缓存到 CI 的 Maven 本地仓库，再构建 App。 |
| `local` | 从开发者机器的 `mavenLocal()` 获取。GitHub 发布工作流会拒绝此版本。 |

普通版本可直接用于 App 的 CI 发布。使用 `git.<SHA>` 时，App 的 Draft Release 会附带 `BUILD-INFO.txt`，记录 App commit、InkReaderLink 坐标和 SDK commit。SDK AAR 缓存按完整 SDK SHA 及工具链缓存键保存；SHA 改变时会自动构建新产物，不需要手工清缓存。

SDK 仓库位置可通过 `INKREADERLINK_SDK_REPOSITORY` 配置，默认值为 `https://github.com/Coldin04/InkReaderLink.git`。本地 shell 环境和 App GitHub 仓库的 Actions Variables 都可设置该变量以使用 fork 或镜像。

本地构建的 `local` 与 `git.<SHA>` 产物都必须先存在于 `mavenLocal()`；普通版本只从 Maven Central 解析。版本属性位于 `android/gradle.properties`，InkReaderLink 本地构建和发布命令见 SDK 仓库 README 的“SDK 引用指南”。
