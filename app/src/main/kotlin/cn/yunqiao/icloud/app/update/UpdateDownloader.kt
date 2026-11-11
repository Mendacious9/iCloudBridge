package cn.yunqiao.icloud.app.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 下载更新包并做三重校验。
 *
 * 自更新最危险的一步是「装了一个别人塞给你的 APK」。这里挡三层：
 *
 * 1. **SHA-256**：清单里给了就必须逐字节对上。挡的是传输损坏与中间人替换；
 * 2. **签名证书**：下载包的签名必须与**已安装应用**的签名完全一致。
 *    这一条是真正的防线 —— 即使清单和 APK 都被换掉，攻击者也拿不到你的私钥，
 *    签不出能覆盖安装的包，系统会直接拒绝升级；
 * 3. **包名 + versionCode**：防止把别的应用或旧版本当作更新推下来。
 *
 * 任何一条不过都抛 [UpdateException]，绝不放行到安装环节。
 */
class UpdateDownloader(private val context: Context) {

    suspend fun download(
        manifest: UpdateManifest,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // 旧包必须先清掉：既省空间，也避免界面上误装上一次下载的版本
        dir.listFiles()?.forEach { it.delete() }

        val target = File(dir, "iCloudBridge-${manifest.versionCode}.apk")
        val partial = File(dir, "download.part")

        val request = Request.Builder()
            .url(manifest.apkUrl)
            .header("User-Agent", UpdateConfig.USER_AGENT)
            .build()

        val knownSize = manifest.sizeBytes.takeIf { it > 0 }

        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw UpdateException("下载失败：HTTP ${response.code}")
            val body = response.body ?: throw UpdateException("下载失败：响应为空")
            val total = body.contentLength().takeIf { it > 0 } ?: knownSize ?: -1L

            body.byteStream().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    var lastEmit = 0f
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        // 每 1% 才回调一次：进度条不需要每个 64KB 都重组一次界面
                        if (total > 0) {
                            val progress = (copied * 100 / total).toInt() / 100f
                            if (progress - lastEmit >= 0.01f) {
                                lastEmit = progress
                                onProgress(progress.coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }

            if (total > 0 && partial.length() != total) {
                partial.delete()
                throw UpdateException("下载不完整（${partial.length()}/$total 字节），请重试")
            }
        }

        onProgress(1f)
        if (!partial.renameTo(target)) {
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
        verify(target, manifest)
        target
    }

    // ------------------------------------------------------------------ 校验

    private fun verify(apk: File, manifest: UpdateManifest) {
        if (manifest.sha256.isNotBlank()) {
            val actual = sha256Of(apk)
            if (!actual.equals(manifest.sha256, ignoreCase = true)) {
                apk.delete()
                throw UpdateException("安装包校验失败（SHA-256 不匹配），已删除，请稍后重试")
            }
        }

        val pm = context.packageManager
        val flags = signingFlags()
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: run {
                apk.delete()
                throw UpdateException("安装包无法解析，可能已损坏")
            }

        if (archive.packageName != context.packageName) {
            apk.delete()
            throw UpdateException("安装包包名不符（${archive.packageName}），已拒绝")
        }

        // longVersionCode 是 API 28 才有的字段，26/27 上只能读已废弃的 versionCode
        val archiveVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            archive.versionCode
        }
        if (archiveVersion != manifest.versionCode) {
            apk.delete()
            throw UpdateException("安装包版本号不符（$archiveVersion ≠ ${manifest.versionCode}），已拒绝")
        }

        if (!signatureMatches(archive, pm.getPackageInfo(context.packageName, flags))) {
            apk.delete()
            throw UpdateException("安装包签名与本机应用不一致，已拒绝安装")
        }
    }

    /**
     * 比较两个包的签名证书。
     *
     * 比的是**证书字节的 SHA-256 集合**，不是 `Signature.equals` ——
     * 后者在部分 ROM 上受编码差异影响会误判。
     */
    private fun signatureMatches(candidate: PackageInfo, installed: PackageInfo): Boolean {
        val a = signerDigests(candidate)
        val b = signerDigests(installed)
        return a.isNotEmpty() && a == b
    }

    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // apkContentsSigners 取的是「这个包实际用的证书」，而不是历史链：
            // 拿历史链来比会把「换过密钥」误判成一致，这里要严格
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        return signatures.orEmpty().map { sha256Hex(it.toByteArray()) }.toSet()
    }

    private fun signingFlags(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        @Suppress("DEPRECATION")
        PackageManager.GET_SIGNATURES
    }

    private fun sha256Of(file: File): String =
        file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
            // Locale.ROOT：某些区域的数字格式化会把 0-9 换成别的字符，哈希就再也匹配不上了
            digest.digest().joinToString("") { String.format(Locale.ROOT, "%02x", it) }
        }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { String.format(Locale.ROOT, "%02x", it) }

    private companion object {
        val downloadClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            // 安装包十几 MB，弱网下可能需要一两分钟，不设总超时
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
