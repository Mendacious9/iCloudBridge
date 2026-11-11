package cn.yunqiao.icloud.tools

import cn.yunqiao.icloud.ICloudClient
import cn.yunqiao.icloud.ICloudException
import cn.yunqiao.icloud.ICloudRegion
import cn.yunqiao.icloud.model.AssetSize
import cn.yunqiao.icloud.model.DriveEntryType
import cn.yunqiao.icloud.store.FileSessionStore
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

/**
 * M1 真机联调脚本：用 PoC 阶段留下的会话跑一遍完整的 Kotlin 链路。
 *
 * 它验证的不是「代码能编译」，而是「Kotlin 移植是否与 Python 行为一致」：
 * 会话复用、账户信息、相册列表、资产分页、分辨率版本、云盘目录、下载地址。
 *
 * 用法：
 * ```
 * ./gradlew :core:icloud:verifyLive --args="<poc_session 目录> <apple id>"
 * ```
 */
private val region = ICloudRegion.CHINA
private var passed = 0
private var failed = 0

fun main(args: Array<String>) = runBlocking {
    val pocDir = File(args.getOrElse(0) { "poc/poc_session" })
    val appleId = args.getOrNull(1)
        ?: error("用法：./gradlew :core:icloud:verifyLive --args=\"<poc 会话目录> <Apple ID>\"")
    val storeDir = File(args.getOrElse(2) { "build/verify-store" })

    println("=".repeat(72))
    println("iCloud Kotlin 链路联调  appleId=$appleId  region=${region.label}")
    println("PoC 会话目录: ${pocDir.absolutePath}")
    println("=".repeat(72))

    val store = FileSessionStore(storeDir) { println("  [!] $it") }
    val imported = PocSessionImporter.importInto(pocDir, appleId, store)
    if (imported == null) {
        println("[x] 没找到 PoC 会话文件，无法离线联调")
        exitProcess(2)
    }
    println(
        "[1] 会话导入完成：sessionToken=${if (imported.session.sessionToken != null) "有" else "无"} " +
            "trustToken=${if (imported.session.trustToken != null) "有" else "无"} " +
            "cookies=${imported.cookies.size} 条",
    )

    val client = ICloudClient.create(appleId, region, store)

    step("会话复用 / accountLogin") {
        val info = client.auth.restoreSession()
        if (info == null) {
            println("    [!] 会话已失效或仍需要 2FA —— 需要重新登录后才能继续联调")
            return@step false
        }
        println("    账户：${info.fullName ?: "(无姓名)"} <${info.appleId}> dsid=${info.dsid}")
        println("    区域：${info.region}")
        println("    可用服务(${info.availableServices.size})：${info.availableServices.joinToString(", ")}")
        println("    需要 2FA：${client.auth.requiresTwoFactor()}")
        true
    }

    step("服务入口与 Cookie 覆盖") {
        val info = client.auth.account ?: return@step false
        for (key in listOf("ckdatabasews", "drivews")) {
            val url = info.serviceUrl(key)
            println("    $key -> $url")
            val names = client.http.cookieNamesFor(url.orEmpty())
            println("      可发送 Cookie(${names.size})：${names.joinToString(", ")}")
        }
        true
    }

    step("排序与分页语义确认") {
        val page1 = client.photos.libraryAssets(0, 3, descending = false)
        val page2 = client.photos.libraryAssets(3, 3, descending = false)
        fun show(page: cn.yunqiao.icloud.photos.AssetPage) =
            page.assets.joinToString(" | ") { "${it.filename}@${it.assetDateMillis}" }
        println("    rank 0-2：${show(page1)}")
        println("    rank 3-5：${show(page2)}")
        val overlap = page1.assets.map { it.filename }.intersect(
            page2.assets.map { it.filename }.toSet(),
        )
        println("    两页重叠资产：${if (overlap.isEmpty()) "无（分页正常）" else overlap}")
        page1.assets.isNotEmpty() && page2.assets.isNotEmpty() && overlap.isEmpty()
    }

    step("照片库索引状态") {
        val state = client.photos.indexPathingState()
        println("    state=$state")
        true
    }

    step("相册列表") {
        val albums = client.photos.albums()
        println("    共 ${albums.size} 个用户相册")
        albums.take(5).forEach { println("      - ${it.name}") }
        true
    }

    step("照片库分页（升序 10 张）") {
        val page = client.photos.libraryAssets(startRank = 0, limit = 10, descending = false)
        println("    原始记录 ${page.rawRecordCount} 条 → 配对出 ${page.assets.size} 个资产")
        page.assets.take(3).forEach { asset ->
            println(
                "      · ${asset.filename}  sizes=${asset.versions.keys.joinToString(",")}" +
                    "  video=${asset.isVideo}  date=${asset.assetDateMillis}",
            )
        }
        page.assets.isNotEmpty()
    }

    step("最新照片（倒序）") {
        val latest = client.photos.latest(5)
        println("    取到 ${latest.size} 条")
        latest.take(3).forEach { println("      · ${it.filename} @ ${it.assetDateMillis}") }
        true
    }

    step("相册内取资产（验证 zone 假设）") {
        val album = client.photos.albums().firstOrNull() ?: return@step false
        val page = client.photos.albumAssets(album, limit = 10)
        println("    相册「${album.name}」原始记录 ${page.rawRecordCount} 条 → ${page.assets.size} 个资产")
        page.assets.take(2).forEach { println("      · ${it.filename}") }
        true
    }

    step("云盘根目录") {
        val entries = client.drive.listChildren()
        val folders = entries.count { it.type == DriveEntryType.FOLDER }
        println("    共 ${entries.size} 项（$folders 个文件夹）")
        entries.take(6).forEach { println("      · [${it.type}] ${it.name} ${it.sizeBytes ?: ""}") }
        entries.isNotEmpty()
    }

    step("云盘子目录下钻") {
        val root = client.drive.listChildren()
        val folder = root.firstOrNull { it.type == DriveEntryType.FOLDER } ?: return@step false
        val children = client.drive.listFolder(folder)
        println("    文件夹「${folder.name}」下 ${children.size} 项")
        children.take(3).forEach { println("      · [${it.type}] ${it.name}") }
        true
    }

    step("云盘文件下载地址") {
        val entries = client.drive.listChildren()
        val file = entries.firstOrNull {
            it.type == DriveEntryType.FILE && it.docwsid != null && (it.sizeBytes ?: 0) > 0
        } ?: return@step false
        val url = client.drive.downloadUrl(file.docwsid!!)
        println("    文件「${file.name}」(${file.sizeBytes} B) 下载地址：")
        println("      ${url?.take(100)}…")
        url != null && url.startsWith("http")
    }

    step("缩略图实际下载（前 4KB）") {
        val asset = client.photos.latest(1).firstOrNull() ?: return@step false
        val thumb = asset.versions[AssetSize.THUMB] ?: asset.bestVersion ?: return@step false
        var read = 0
        client.http.stream(thumb.downloadUrl) { response ->
            val buffer = ByteArray(4096)
            val input = response.body?.byteStream() ?: return@stream
            while (read < 4096) {
                val n = input.read(buffer, 0, 4096 - read)
                if (n <= 0) break
                read += n
            }
        }
        println("    下载 ${asset.filename} 的缩略图，读到 $read 字节")
        read > 0
    }

    println("=".repeat(72))
    println("结果：$passed 项通过，$failed 项未通过")
    println("=".repeat(72))
    if (failed > 0) exitProcess(1)
}

private suspend fun step(name: String, block: suspend () -> Boolean) {
    print("[ ] $name … ")
    val ok = try {
        block()
    } catch (e: ICloudException) {
        println("失败")
        println("    [!] ${e.message}")
        failed++
        return
    } catch (e: Exception) {
        println("异常")
        println("    [!] ${e.javaClass.simpleName}: ${e.message}")
        failed++
        return
    }
    if (ok) {
        println("OK")
        passed++
    } else {
        println("未通过")
        failed++
    }
}
