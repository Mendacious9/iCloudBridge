# iCloudBridge

安卓端访问 **iCloud 照片** 与 **iCloud 云盘** 的非官方客户端。

走的是 icloud.com 网页端那一套私有 Web API，所以只要一个普通 Apple ID + 密码
（再加双重认证）就能登录 —— 不需要 App 专用密码，不需要 Mac，也不需要越狱。

> English README: [README.md](README.md)

---

## ⚠️ 先说清楚

- **本项目和苹果公司没有任何关系**，未获其授权、认可或支持。"iCloud" 是 Apple Inc.
  的商标，这里仅用于说明「与什么互通」。
- 它依赖**逆向出来的私有接口**。苹果 iCloud 服务条款明确禁止「通过自动化手段
  （脚本、爬虫等）访问服务」以及未授权访问，**使用本应用可能违反该条款**，
  苹果有权停用或封禁你登录时用的 Apple ID。
- 本项目**免费、按原样提供、不附带任何担保**（见 [LICENSE](LICENSE)）。
  如果某张照片、某个文件只存在于 iCloud，**不要**把它当作唯一的取用途径。
- 接口没有文档，苹果随时会改。某次改版之后本应用可能直接失效，且没有修复时间表。

不能接受以上任何一条，请不要使用。

---

## 能做什么

| 模块 | 能力 |
|---|---|
| 登录 | SRP-6a（RFC 5054，2048 位）直连 `idmsa.apple.com`，支持受信设备 / 短信双重认证；会话可复用，2FA 只需过一次 |
| 相册 | 相册列表；双指缩放网格（跨档时列数变化）；查看器左右滑动翻页，可跨整库连续切换 |
| 实况照片 | 网格左下角 `LIVE` 角标；查看器里点 `LIVE` 或长按播放动态（约 3 秒后自动回静态） |
| 视频 | 原地播放，自绘控制条（播放/暂停、进度拖动、当前/总时长） |
| 上传 | 照片、视频上传进 iCloud 照片图库，带转码与「苹果拒收」的明确提示 |
| 云盘 | 目录浏览、下载到本机、上传、删除 |
| 安全 | 会话令牌用 AndroidKeyStore + AES-256-GCM 加密落盘；可选指纹/人脸应用锁 |
| 多语言 | 英文、简体中文、繁體中文、日本語、한국어，连错误提示一起翻译 |

在三星 SM-S9380（Android 16，1440×3120）上实测通过。`minSdk = 26`，Android 8.0 以上
理论上都能跑。

## 工程结构

```
icloud-bridge/
├── core/icloud/          纯 Kotlin 协议层 —— 不依赖 Android，可直接在 JVM 上跑
│   └── src/main/kotlin/cn/yunqiao/icloud/
│       ├── srp/          SRP-6a 认证（RFC 5054，2048-bit）
│       ├── auth/         登录、双重认证、会话建立
│       ├── http/         OkHttp 底座 + Cookie 持久化
│       ├── photos/       照片库查询（CloudKit Web Services）
│       ├── drive/        云盘目录与下载
│       ├── store/        会话存储（文件 / 内存）
│       └── tools/        VerifyMain：用真实会话跑全链路联调
├── app/                  Android 客户端（Jetpack Compose，单 Activity）
└── poc/                  M0 阶段的 Python 协议验证，留作可读的参考实现
```

`core/icloud` **不 import 任何 Android 类**，这是协议层能被 JVM 单测覆盖、
也能用命令行驱动的原因。

## 构建

需要 **JDK 21** 和带 platform 35 的 Android SDK。

Kotlin 2.0.x 与 AGP 8.9 **不支持 JDK 25**（新版 Android Studio 自带的 JBR 就是 25）。
`gradle.properties` 里刻意没有写死 JDK 路径，请自行用 `JAVA_HOME` 指定，或在
*Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK* 里选 21。

```bash
# 1. 告诉 Gradle 你的 SDK 在哪（该文件不入版本库）
echo "sdk.dir=/path/to/Android/sdk" > local.properties

# 2. 编译 Debug APK
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# 3. 协议层单元测试
./gradlew :core:icloud:test

# 4. 装到已连接的真机
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 发布签名

release 包从 `keystore/keystore.properties` 读签名配置，该文件不入版本库。
先复制模板再填：

```bash
cp keystore/keystore.properties.example keystore/keystore.properties
./gradlew :app:assembleRelease
```

没有这个文件时 `assembleRelease` 仍会执行，但产出的是**未签名** APK，装不上。

release 与 debug 用的是不同密钥，不能互相 `install -r` 覆盖；而卸载会清掉已保存的
会话（又要重走一遍 2FA）。真机上调试界面时，可以给 debug 包套发布签名：

```bash
./gradlew :app:assembleDebug -PdebugSignWithRelease=true
```

## 安全设计

- **密码不落盘**。只在内存里算一次 SRP proof，离开登录页即随 Composable 状态销毁。
- 会话令牌与 `X-APPLE-WEBAUTH-*` Cookie 是**长期凭证**（复用时可绕过 2FA），
  用 AndroidKeyStore 里生成、永不导出的 AES-256-GCM 密钥加密后才写进
  SharedPreferences；首次启动会把旧的明文文件迁移并删除。
- `allowBackup="false"`、`usesCleartextTraffic="false"`，权限只有
  `INTERNET` 与 `ACCESS_NETWORK_STATE`。
- 流量只发往苹果自己的域名（`*.apple.com` / `*.icloud.com` / `*.icloud-content.com`）。
  **没有统计 SDK、没有崩溃上报、没有自己的服务器** —— 数据不经过任何第三方。
- 应用锁用 `BiometricPrompt`，选 `BIOMETRIC_WEAK` 以覆盖 2D 人脸，并保留设备密码兜底。

## 已知限制

- 相册基本只读：不能新建相册、重命名、单张删除（删除只在「最近删除」里）。
- 视频上传受苹果转码能力限制，H.264/HEVC 之外的编码会被 415 拒收（UI 会明确提示换格式）。
- 云盘上传不支持断点续传；上传期间界面被遮罩锁住，避免留下半个文件。
- 订阅变更、家人共享、设备列表不在私有 Web API 里，只能跳官网。
- `ICloudConstants` 里的 widget key、client build number、UA 是从网页端抄来的，
  **苹果改版时必须跟着更新**。用户反馈「突然登录不了」，九成是这个原因。

---

# 协议笔记

下面是逆向过程中踩出来的结论，按「照做就能复现」的粒度写。换实现（或移植到别的
语言）时，这部分比代码更有价值。

## 上传

照片与云盘是**两套完全不同的协议**，入口也分开，不要混用。

**照片 → iCloud 照片图库**（`photosupload` 服务，三步）：

1. `POST {photosupload}/photosupload/createUploadUrl`
   body `{zoneName:"PrimarySync", assets:{<uuid>:<bytes>}}` → `{uploadUrls:{<uuid>:<url>}}`
2. `POST <url>` 原样写字节。该地址在 `cws.icloud-content.com.cn` 上，自带 token，
   **不能带 `Content-Type: application/json`**
3. `POST {photosupload}/photosupload/putAsset`
   `{zoneName, files:[{fileName, lastModDate, timeZoneOffset, singleFileUploadRequest:<receipt>}],
     localTimeZoneId, importGroup}` → `[{uploadJobId, cplMaster, cplAsset, response:{status}}]`

坑：

- `timeZoneOffset` 用 JS 的 `getTimezoneOffset` 约定（UTC 减本地），东八区发 **-480**；
- **登记失败不会体现在 HTTP 状态上**，外层永远 200，结果在数组元素的 `response.status` 里。
  不校验它，视频失败会被静默吞掉（本工程踩过：415 `uploaded media is unsupported for transcoding`）；
- 415 = 苹果拒收：照片要 JPEG/HEIC/PNG，视频编码必须可转码（H.264/HEVC），
  换成 `ICloudUnsupportedMediaException` 让 UI 提示「换格式」而不是「重试」；
- 登记成功 ≠ 立刻可查，CloudKit 还要索引几秒，所以上传完会 `delay(2500)` 再刷新。

**云盘 → iCloud 云盘**（`docws` 服务，三步）：

1. `POST {docws}/ws/com.apple.CloudDocs/upload/web` `{filename,type:"FILE",content_type,size}`
   → `[{document_id, url}]`
2. `POST <url>` 写字节 → `{singleFile:{fileChecksum, wrappingKey, referenceChecksum, receipt, size}}`
3. `POST {docws}/ws/com.apple.CloudDocs/update/documents`，`command:"add_file"`，
   `path.starting_document_id` = 目标文件夹 docwsid（根目录就是字面量 `root`）

坑：

- 少了 `command:"add_file"` 只会回 `Unexpected input at column:56`，文件成为孤儿；
- 列表接口把扩展名单独放在 `extension` 字段，`name` 只有主名 —— 不拼回去，列表少后缀、
  下载存盘也少后缀，文件双击打不开（`DriveApi.toEntry` 已处理，并有单测）；
- 删除走 `update/documents` + `command:"delete"`，不是 `docws/delete`（那个返回 405）。

## 实况照片的识别

`itemType` 是 `public.heic`，同时带 `resVidSmallRes` / `resVidMedRes`，
且 `duration = 0` —— 真实动态时长在 `vidComplDurValue / vidComplDurScale`
（例如 1720/600 ≈ 2.9 秒）。所以判定顺序必须是：

1. `itemType` 含 `movie` / `mpeg` / `video` / `quicktime` / `avi` → 视频；
2. `itemType` 以 `public.` / `com.adobe.` 开头 → 照片（可能是实况）；
3. `itemType` 缺失时才退回「有没有视频资源」这种猜测。

只按「有没有 `resVid*`」判断，会把实况照片当成 `duration=0` 的视频，
网格上就会出现「有的视频有 00:00 时长角标、有的没有」这种怪现象。

## 播放器

- **不要用 `SurfaceView`**：放进 `graphicsLayer` / 离屏层会黑屏；铺满窗口会在图层上
  「挖洞」，同层的 Compose 控件被盖住；`screencap` 也只能拍到黑（要用 `screenrecord` 抽帧）。
- 用 **`TextureView`**：普通 View，参与正常合成，可以和 Compose 叠、可以截屏。
- 系统 `MediaController` 塞进 `AndroidView` 会定位错乱并吞掉整块触摸，所以控制条是自绘的。
- 播放结束后 `onCompletion` 要把按钮切回三角、并回退一帧，否则停在黑屏；
  拖到末尾要夹到 `durationMs - 50`。

## 会话恢复

`POST {setup}/validate` 即使返回 401，`X-APPLE-WEBAUTH-TOKEN` Cookie 往往还活着
（有效期约 30 天），可以直接拿 Cookie 重新 `accountLogin` 恢复会话 ——
这是「重启 App 不用重新登录」的关键。

---

## 贡献

欢迎 issue 与 PR，请尽量遵守：

1. `core/icloud` 保持零 Android 依赖，它是可移植层。
2. 改协议解析时补单测，`core/icloud/src/test/` 里有 CloudKit 记录形状的样例。
3. **不要提交任何真实的 Apple ID、会话文件、Cookie jar 或签名文件。**
4. 五套 `values*/strings.xml` 同步维护。

## 致谢

协议理解来自 [pyicloud](https://github.com/picklepete/pyicloud) 与
[icloudpd](https://github.com/icloud-photos-downloader/icloud_photos_downloader)，
SRP 实现遵循 RFC 5054。

## 许可

[MIT](LICENSE) © 2026 iCloudBridge contributors
