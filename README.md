# iCloudBridge

An **unofficial** Android client for iCloud Photos and iCloud Drive.

It talks to the same private web API that icloud.com uses, so an ordinary Apple ID
and password (plus two-factor authentication) is enough — no App-specific password,
no Mac, no jailbreak.

> **中文说明见 [README.zh-CN.md](README.zh-CN.md)**（含完整的协议逆向笔记，讲清了上传、
> 实况照片、云盘目录这些坑）。

---

## ⚠️ Read this first

- **This project is not affiliated with, endorsed by, or supported by Apple Inc.**
  "iCloud" is a trademark of Apple Inc., used here only to describe interoperability.
- It uses a **reverse-engineered private API**. Apple's iCloud Terms of Service
  prohibit accessing the service "through any automated means" and prohibit
  unauthorised access. **Using this app may therefore violate those terms**, and
  Apple may suspend or terminate the Apple ID you use with it.
- It is published **free of charge, as-is, with no warranty** (see [LICENSE](LICENSE)).
  If a photo or a file exists only in iCloud, **do not** use this app as your sole
  means of accessing it. Keep a second copy.
- The API is undocumented and changes without notice. A future iCloud release may
  break this app completely, and there is no fix schedule.

If any of that is not acceptable to you, please do not use this project.

---

## What it does

| Area | Capability |
|---|---|
| Sign-in | SRP-6a (RFC 5054, 2048-bit) against `idmsa.apple.com`, two-factor via trusted device or SMS, session reuse so you only solve 2FA once |
| Photos | Album list, pinch-to-zoom grid whose step size changes as you zoom, viewer with left/right swipe paging across the whole library |
| Live Photos | `LIVE` badge in the grid, motion playback from the viewer (button or long-press) |
| Video | In-place playback with a self-drawn control bar (play/pause, seek, elapsed/total) |
| Upload | Photos and videos into the iCloud Photo Library, with transcoding and clear errors for media Apple refuses |
| Drive | Folder browsing, download to device, upload, delete |
| Security | Session tokens encrypted with AndroidKeyStore + AES-256-GCM, optional biometric app lock |
| Languages | English, 简体中文, 繁體中文, 日本語, 한국어 — UI and error messages |

Tested on a Samsung SM-S9380 (Android 16, 1440×3120). `minSdk` is 26, so anything
from Android 8.0 up should at least run.

## Architecture

```
icloud-bridge/
├── core/icloud/          Pure Kotlin protocol layer — runs on plain JVM, no Android
│   └── src/main/kotlin/cn/yunqiao/icloud/
│       ├── srp/          SRP-6a (RFC 5054, 2048-bit)
│       ├── auth/         Sign-in, 2FA, session establishment
│       ├── http/         OkHttp base + cookie persistence
│       ├── photos/       CloudKit Web Services queries
│       ├── drive/        CloudDrive listing and download
│       ├── store/        Session stores (file / in-memory)
│       └── tools/        VerifyMain: run the whole chain against a real session
├── app/                  Android client (Jetpack Compose, single activity)
└── poc/                  Python protocol PoC — kept as a readable reference
```

`core/icloud` has **no Android dependency**, which is what makes the protocol layer
unit-testable and lets you drive it from a JVM CLI.

## Build

Requirements: **JDK 21** and the Android SDK with platform 35.

Kotlin 2.0.x and AGP 8.9 do **not** support JDK 25 (the JBR bundled with recent
Android Studio). Point Gradle at 21 — `gradle.properties` deliberately does not
hard-code a path, so either set `JAVA_HOME` or pick the JDK under
*Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK*.

```bash
# 1. Tell Gradle where your SDK is (this file is git-ignored)
echo "sdk.dir=/path/to/Android/sdk" > local.properties

# 2. Build a debug APK
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# 3. Run the protocol unit tests
./gradlew :core:icloud:test

# 4. Install on a connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Release signing

Release builds are signed from `keystore/keystore.properties`, which is git-ignored.
Copy the template and fill it in:

```bash
cp keystore/keystore.properties.example keystore/keystore.properties
./gradlew :app:assembleRelease
```

Without that file, `assembleRelease` still runs but produces an **unsigned** APK.

Because release and debug use different keys, you cannot `install -r` one over the
other — and uninstalling wipes the saved session, costing you another 2FA round.
For UI work on a real device, sign the debug variant with the release key instead:

```bash
./gradlew :app:assembleDebug -PdebugSignWithRelease=true
```

## Security model

- Your password is **never written to disk**. It is used once to compute the SRP
  proof, and lives only in the composable's in-memory state until the screen leaves.
- The session token and `X-APPLE-WEBAUTH-*` cookies are **long-term credentials**
  (they bypass 2FA on reuse). They are stored encrypted with an AES-256-GCM key that
  is generated in and never leaves the **AndroidKeyStore**.
- `android:allowBackup="false"` and `android:usesCleartextTraffic="false"`; the only
  permissions are `INTERNET` and `ACCESS_NETWORK_STATE`.
- Traffic goes only to Apple's own hosts (`*.apple.com`, `*.icloud.com`,
  `*.icloud-content.com`). There is **no analytics, no crash reporting, no server of
  ours** in the path — the app cannot see your data even if we wanted it to.
- The optional app lock uses `BiometricPrompt` with `BIOMETRIC_WEAK` (so 2D face
  unlock works) and falls back to the device credential.

If you find a security issue, please open an issue — or contact the maintainer
privately if it is sensitive.

## Known limitations

- The photo library is effectively read-only: no creating albums, renaming, or
  deleting individual assets (deletion only through "Recently Deleted").
- Video upload depends on Apple's transcoder. Anything outside H.264/HEVC is
  rejected with HTTP 415, surfaced as a distinct error rather than "retry".
- Drive uploads have no resume; the UI blocks while uploading so a half-written
  file cannot be left behind.
- Subscription changes, Family Sharing and the device list are not exposed by the
  private web API; those still require icloud.com.
- The client identifiers in `ICloudConstants` (widget key, client build number, UA)
  are copied from the web client and **must be updated when Apple changes them**.
  This is the most likely cause of a sudden "login stopped working" report.

## Tests

```bash
./gradlew :core:icloud:test        # SRP vectors + protocol parsing, JVM-only
```

Instrumented tests (session encryption, media transcoding) require a device or
emulator running API 26+:

```bash
./gradlew :app:connectedAndroidTest
```

## Contributing

Issues and pull requests are welcome. Please:

1. Keep `core/icloud` free of Android imports — it is the portable layer.
2. Add a unit test for protocol parsing changes; the fixtures in
   `core/icloud/src/test/` show the CloudKit record shape.
3. Never commit a real Apple ID, session file, cookie jar, or keystore.
4. Keep all five `values*/strings.xml` files in sync.

## Credits

The protocol understanding stands on the shoulders of
[pyicloud](https://github.com/picklepete/pyicloud) and
[icloudpd](https://github.com/icloud-photos-downloader/icloud_photos_downloader).
The SRP implementation follows RFC 5054.

## License

[MIT](LICENSE) © 2026 iCloudBridge contributors
