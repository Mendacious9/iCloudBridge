# iCloudBridge

**An unofficial Android client for iCloud Photos and iCloud Drive.**
**非官方的 iCloud 相册与云盘安卓客户端。**

`Kotlin` · `Jetpack Compose` · `MIT` · `minSdk 26`

---

## English

An unofficial Android client for iCloud Photos and iCloud Drive. It talks to the
same web API that icloud.com uses, so a regular Apple ID with two-factor
authentication is enough — no app-specific password, no Mac, no jailbreak.

### Features

| | |
|---|---|
| **Sign-in** | SRP-6a (RFC 5054), 2FA via trusted device or SMS, session reuse so 2FA is needed once |
| **Photos** | Album list, pinch-to-zoom grid, viewer that pages left/right across the whole library |
| **Live Photos** | `LIVE` badge in the grid, motion playback from the viewer |
| **Video** | Playback with play/pause, seek, and elapsed/total time |
| **Upload** | Photos and videos into iCloud Photos; Drive browse, download, upload, delete |
| **Security** | Session tokens encrypted with AndroidKeyStore + AES-256-GCM; optional biometric lock |
| **Languages** | English, 简体中文, 繁體中文, 日本語, 한국어 |

Tested on a Samsung SM-S9380 (Android 16). `minSdk` is 26.

### Build

Requires **JDK 21** and the Android SDK with platform 35. Kotlin 2.0.x and AGP 8.9
do not support JDK 25.

```bash
echo "sdk.dir=/path/to/Android/sdk" > local.properties
./gradlew :app:assembleDebug      # → app/build/outputs/apk/debug/
./gradlew :core:icloud:test       # protocol unit tests, plain JVM
```

Release builds are signed from `keystore/keystore.properties`, which is
git-ignored — copy `keystore/keystore.properties.example` and fill it in.

### In-app updates

The app is distributed outside any store, so it updates itself: it downloads a new
APK, verifies it, and hands it to the system installer.

```bash
# 1. bump appVersionCode / appVersionName in app/build.gradle.kts
# 2. build and write the manifest
./gradlew :app:writeUpdateManifest
# 3. edit apkUrl and changelog, then attach the APK and update.json
#    to a GitHub Release tagged v<versionName>
```

Four checks must pass before anything is installed: SHA-256, signing certificate
(must equal the installed app's), package name, and versionCode. Any mismatch
aborts and deletes the download.

### Notes

- `core/icloud` is a pure Kotlin protocol layer with no Android dependency, covered
  by JVM unit tests.
- The photo library is effectively read-only: no album creation, no per-asset delete.
- Client identifiers in `ICloudConstants` are copied from the web client and must be
  updated when Apple changes them — the usual cause of a sudden "login stopped working".
- Deeper protocol notes (Chinese): [docs/PROTOCOL.zh-CN.md](docs/PROTOCOL.zh-CN.md)

### Disclaimer

Not affiliated with, endorsed by, or supported by Apple Inc. "iCloud" is a trademark
of Apple Inc., used here only to describe interoperability. Provided free of charge,
as-is, without warranty of any kind — use at your own risk. If a photo or file exists
only in iCloud, do not rely on this app as your sole means of accessing it.

---

## 中文

安卓端访问 **iCloud 照片** 与 **iCloud 云盘** 的非官方客户端。走的是 icloud.com
网页端同一套 Web API，只要一个普通 Apple ID 加双重认证就能登录 —— 不需要 App
专用密码，不需要 Mac，也不需要越狱。

### 功能

| | |
|---|---|
| **登录** | SRP-6a（RFC 5054），支持受信设备 / 短信双重认证；会话可复用，2FA 只需过一次 |
| **相册** | 相册列表、双指缩放网格、查看器左右滑动跨整库翻页 |
| **实况照片** | 网格 `LIVE` 角标，查看器内播放动态 |
| **视频** | 原地播放，播放/暂停、进度拖动、当前/总时长 |
| **上传** | 照片与视频上传进 iCloud 照片图库；云盘浏览、下载、上传、删除 |
| **安全** | 会话令牌用 AndroidKeyStore + AES-256-GCM 加密落盘；可选指纹 / 人脸应用锁 |
| **多语言** | 英文、简体中文、繁體中文、日本語、한국어 |

在三星 SM-S9380（Android 16）上实测通过，`minSdk = 26`。

### 构建

需要 **JDK 21** 与带 platform 35 的 Android SDK；Kotlin 2.0.x 与 AGP 8.9
不支持 JDK 25。

```bash
echo "sdk.dir=/path/to/Android/sdk" > local.properties
./gradlew :app:assembleDebug      # → app/build/outputs/apk/debug/
./gradlew :core:icloud:test       # 协议层单元测试（纯 JVM）
```

release 包从 `keystore/keystore.properties` 读签名配置，该文件不入版本库 ——
复制 `keystore/keystore.properties.example` 后填写即可。

### 线上更新

本应用不上任何商店，更新由客户端自己完成：下载新 APK → 校验 → 交给系统安装器。

```bash
# 1. 改 app/build.gradle.kts 里的 appVersionCode / appVersionName
# 2. 构建并生成清单
./gradlew :app:writeUpdateManifest
# 3. 编辑 apkUrl 与 changelog，把 APK 与 update.json 一起传到
#    v<versionName> 的 GitHub Release
```

安装前必须通过四道校验：SHA-256、签名证书（须与已装包一致）、包名、
versionCode。任一条不符即中止并删除安装包。

### 说明

- 协议层 `core/icloud` 不依赖 Android，由 JVM 单测覆盖。
- 相册基本只读：不能新建相册，也不能单张删除。
- `ICloudConstants` 里的客户端标识抄自网页端，苹果改版时必须跟着更新 ——
  用户反馈「突然登录不了」，九成是这个原因。
- 更细的协议笔记见 [docs/PROTOCOL.zh-CN.md](docs/PROTOCOL.zh-CN.md)。

### 免责声明

本项目与 Apple Inc. 无任何关联，未获其授权或认可。"iCloud" 是 Apple Inc.
的商标，此处仅用于说明互通对象。本项目免费、按原样提供，不附带任何担保，
使用风险自负。若某张照片或某个文件只存在于 iCloud，请勿将本应用作为唯一的
取用途径。

---

[MIT](LICENSE) © 2026 iCloudBridge contributors
