package cn.yunqiao.icloud

import cn.yunqiao.icloud.auth.BootArgs
import cn.yunqiao.icloud.model.AssetSize
import cn.yunqiao.icloud.model.DriveEntryType
import cn.yunqiao.icloud.store.MemorySessionStore
import cn.yunqiao.icloud.tools.PocSessionImporter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import java.io.File
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 协议解析层的回归测试。
 *
 * 这里覆盖的都是「联调中真出过问题」或「苹果改版时会静默失效」的地方：
 * Cookie 导入、boot_args 手机号解析、Master/Asset 配对、drivews 双形态响应。
 * 这些逻辑不依赖网络，可以随时跑。
 */
class ProtocolParseTest {

    private fun newClient() = ICloudClient.create(
        appleId = "test@example.com",
        region = ICloudRegion.CHINA,
        store = MemorySessionStore(),
    )

    // ------------------------------------------------------------ Cookie 导入

    @Test
    fun `LWP Cookie 导入保留域名、引号与有效期`() {
        val text = """
            #LWP-Cookies-2.0
            Set-Cookie3: X-APPLE-WEBAUTH-VALIDATE="\"v=1:t=Cw==abc~\""; path="/"; domain=.icloud.com.cn; path_spec; domain_dot; secure; expires="2026-11-26 09:02:41Z"; HttpOnly=None; version=0
            Set-Cookie3: dslang=US-EN; path="/"; domain=.apple.com.cn; path_spec; secure; discard; HttpOnly=None; version=0
        """.trimIndent()

        val file = File.createTempFile("cookies", ".txt").apply {
            writeText(text, Charsets.UTF_8)
            deleteOnExit()
        }
        val cookies = PocSessionImporter.parseLwpCookies(file)
        assertEquals(2, cookies.size, "两行 Cookie 都应该被解析出来")

        val validate = cookies.first { it.name == "X-APPLE-WEBAUTH-VALIDATE" }
        assertEquals("\"v=1:t=Cw==abc~\"", validate.value, "转义引号应还原成真实 Cookie 值")
        assertEquals(".icloud.com.cn", validate.domain)
        assertFalse(validate.hostOnly, "带前导点的域名是域 Cookie，能匹配子域名")
        assertTrue(validate.secure)
        assertEquals(
            Instant.parse("2026-11-26T09:02:41Z").toEpochMilli(),
            validate.expiresAt,
        )

        val session = cookies.first { it.name == "dslang" }
        assertTrue(
            session.expiresAt > System.currentTimeMillis(),
            "discard 的会话 Cookie 落盘时要补一个未来时间，否则重启就丢",
        )
    }

    // ------------------------------------------------------------ boot_args

    @Test
    fun `boot_args 能解析 2026 新路径下的可信手机号`() {
        val html = """
            <html><head>
            <script type="application/json" id="boot" class="boot_args">
            {"direct":{"twoSV":{"bridgeInitiateData":{"phoneNumberVerification":{"trustedPhoneNumbers":[
              {"id":7,"obfuscatedNumber":"+86 138\u2022\u2022\u2022\u20228888","numberWithDialCode":"+86 138 0000 8888"}
            ]}}}}}
            </script>
            </head></html>
        """.trimIndent()

        val phones = BootArgs.parseTrustedPhones(html)
        assertEquals(1, phones.size)
        assertEquals(7, phones.first().id)
        assertEquals("+86 138****8888", phones.first().number, "脱敏符 \u2022 应统一成 *")
    }

    @Test
    fun `boot_args 兼容属性顺序变化与旧路径`() {
        // class 在 type 之前 —— 早期用正则写死顺序时就是在这里挂的
        val html = """
            <script class="boot_args" type="application/json">
            {"direct":{"twoSV":{"phoneNumberVerification":{"trustedPhoneNumbers":[
              {"id":1,"obfuscatedNumber":"+86 139****0000"}
            ]}}}}
            </script>
        """.trimIndent()

        val phones = BootArgs.parseTrustedPhones(html)
        assertEquals(1, phones.size)
        assertEquals("+86 139****0000", phones.first().number)
    }

    @Test
    fun `页面里没有手机号时返回空列表而不是抛异常`() {
        assertEquals(0, BootArgs.parseTrustedPhones("<html><body>no scripts</body></html>").size)
    }

    // ------------------------------------------------------------ 照片配对

    @Test
    fun `CPLMaster 与 CPLAsset 按 masterRef 配对并取出各分辨率`() {
        val filenameEnc = b64("IMG_0001.JPG")
        val records = Json.parseToJsonElement(
            """
            [
              {
                "recordName": "MASTER-1",
                "recordType": "CPLMaster",
                "fields": {
                  "filenameEnc": {"value": "$filenameEnc"},
                  "itemType": {"value": "public.jpeg"},
                  "resOriginalFileType": {"value": "public.jpeg"},
                  "resOriginalRes": {"value": {"downloadURL": "https://cdn/orig", "size": 4096, "fileChecksum": "aa"}},
                  "resJPEGMedRes": {"value": {"downloadURL": "https://cdn/med", "size": 1024, "fileChecksum": "bb"}},
                  "resJPEGThumbRes": {"value": {"downloadURL": "https://cdn/thumb", "size": 128, "fileChecksum": "cc"}}
                }
              },
              {
                "recordName": "ASSET-1",
                "recordType": "CPLAsset",
                "fields": {
                  "masterRef": {"value": {"recordName": "MASTER-1"}},
                  "assetDate": {"value": 1700000000000},
                  "isFavorite": {"value": true},
                  "duration": {"value": 3500}
                }
              }
            ]
            """.trimIndent(),
        ).jsonArray

        val assets = newClient().photos.pairAssets(records)
        assertEquals(1, assets.size)

        val asset = assets.first()
        assertEquals("IMG_0001.JPG", asset.filename, "文件名要从 base64 解回 UTF-8")
        assertEquals("MASTER-1", asset.masterRecordName)
        assertEquals(1700000000000L, asset.assetDateMillis)
        assertTrue(asset.isFavorite)
        assertFalse(asset.isVideo)
        // 苹果给的是毫秒：3500 → 3.5 秒。这条断言就是「显示成 58:20」那类 bug 的守门员
        assertEquals(3.5, asset.durationSeconds ?: 0.0, 1e-6)

        assertEquals(4096L, asset.versions[AssetSize.ORIGINAL]?.sizeBytes)
        assertEquals("https://cdn/orig", asset.versions[AssetSize.ORIGINAL]?.downloadUrl)
        assertEquals("https://cdn/med", asset.versions[AssetSize.MEDIUM]?.downloadUrl)
        assertEquals("https://cdn/thumb", asset.versions[AssetSize.THUMB]?.downloadUrl)
    }

    @Test
    fun `实况照片带视频资源但按照片处理且没有时长`() {
        // 实况照片（HEIC）天生带一段 2~3 秒的动态影片，同样有 resVidSmallRes。
        // 若按「有视频资源就是视频」判定，网格里就会出现「有 ▶ 没时长」的格子
        val records = Json.parseToJsonElement(
            """
            [
              {
                "recordName": "MASTER-LIVE",
                "recordType": "CPLMaster",
                "fields": {
                  "filenameEnc": {"value": "${b64("IMG_0927.HEIC")}"},
                  "itemType": {"value": "public.heic"},
                  "resJPEGMedRes": {"value": {"downloadURL": "https://cdn/live-med", "size": 1024}},
                  "resJPEGThumbRes": {"value": {"downloadURL": "https://cdn/live-thumb", "size": 128}},
                  "resVidSmallRes": {"value": {"downloadURL": "https://cdn/live-vid", "size": 512}}
                }
              },
              {
                "recordName": "ASSET-LIVE",
                "recordType": "CPLAsset",
                "fields": {
                  "masterRef": {"value": {"recordName": "MASTER-LIVE"}},
                  "duration": {"value": 0},
                  "vidComplDurValue": {"value": 1720},
                  "vidComplDurScale": {"value": 600}
                }
              }
            ]
            """.trimIndent(),
        ).jsonArray

        val asset = newClient().photos.pairAssets(records).single()
        assertFalse(asset.isVideo, "实况照片不是视频")
        assertNull(asset.durationSeconds, "视频时长为 0 时不应给出秒数")
        // 动态片段的地址要单独留一份，长按时才去下
        assertEquals("https://cdn/live-vid", asset.liveVideoUrl)
    }

    @Test
    fun `MOV 按视频处理并带毫秒换算后的时长`() {
        val records = Json.parseToJsonElement(
            """
            [
              {
                "recordName": "MASTER-MOV",
                "recordType": "CPLMaster",
                "fields": {
                  "filenameEnc": {"value": "${b64("IMG_0887.MOV")}"},
                  "itemType": {"value": "com.apple.quicktime-movie"},
                  "resVidSmallRes": {"value": {"downloadURL": "https://cdn/mov-small", "size": 512}},
                  "resVidMedRes": {"value": {"downloadURL": "https://cdn/mov-med", "size": 2048}}
                }
              },
              {
                "recordName": "ASSET-MOV",
                "recordType": "CPLAsset",
                "fields": {
                  "masterRef": {"value": {"recordName": "MASTER-MOV"}},
                  "duration": {"value": 288182}
                }
              }
            ]
            """.trimIndent(),
        ).jsonArray

        val asset = newClient().photos.pairAssets(records).single()
        assertTrue(asset.isVideo)
        assertEquals(288.182, asset.durationSeconds ?: 0.0, 1e-6)
        assertNull(asset.liveVideoUrl, "普通视频不该被当成实况照片")
    }

    @Test
    fun `没有对应 master 的孤立 CPLAsset 会被忽略`() {
        val records = Json.parseToJsonElement(
            """
            [
              {
                "recordName": "ASSET-9",
                "recordType": "CPLAsset",
                "fields": {"masterRef": {"value": {"recordName": "MISSING"}}}
              }
            ]
            """.trimIndent(),
        ).jsonArray
        assertEquals(0, newClient().photos.pairAssets(records).size)
    }

    // ------------------------------------------------------------ 云盘解析

    @Test
    fun `drivews 数组形态（目录树）解析`() {
        val payload = Json.parseToJsonElement(
            """
            [
              {
                "drivewsid": "FOLDER::com.apple.CloudDocs::root",
                "name": "iCloud 云盘",
                "items": [
                  {"drivewsid": "FOLDER::com.apple.CloudDocs::A", "docwsid": "A", "name": "Documents", "type": "FOLDER", "numberOfItems": 3},
                  {"drivewsid": "FILE::com.apple.CloudDocs::B", "docwsid": "B", "name": "note.txt", "type": "FILE", "size": 42, "zoneName": "com.apple.CloudDocs"}
                ]
              }
            ]
            """.trimIndent(),
        )

        val entries = newClient().drive.parseEntries(payload)
        assertEquals(2, entries.size)
        assertEquals(DriveEntryType.FOLDER, entries[0].type)
        assertEquals(3, entries[0].childCount)
        assertEquals("FOLDER::com.apple.CloudDocs::A", entries[0].drivewsid)
        assertEquals(DriveEntryType.FILE, entries[1].type)
        assertEquals(42L, entries[1].sizeBytes)
    }

    @Test
    fun `drivews 对象形态解析`() {
        val payload = Json.parseToJsonElement(
            """
            {"items": [
              {"drivewsid": "FOLDER::com.apple.CloudDocs::C", "docwsid": "C", "name": "照片", "type": "FOLDER"},
              {"docwsid": "D", "name": "archive.zip", "type": "FILE", "size": 10240}
            ]}
            """.trimIndent(),
        )
        val entries = newClient().drive.parseEntries(payload)
        assertEquals(2, entries.size)
        assertEquals("照片", entries[0].name)
        assertEquals(10240L, entries[1].sizeBytes)
        assertEquals("com.apple.CloudDocs", entries[1].zone, "缺省 zone 要补成 CloudDocs")
    }

    @Test
    fun `drivews 错误响应会转成可读异常`() {
        val payload = Json.parseToJsonElement(
            """{"errorCode": "NOT_FOUND", "errorReason": "Folder not found"}""",
        )
        val error = runCatching { newClient().drive.parseEntries(payload) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error is ICloudApiException)
        assertTrue(error.message.orEmpty().contains("Folder not found"))
    }

    // ------------------------------------------------------------ 云盘扩展名

    /**
     * 苹果把扩展名单独放在 `extension` 字段，`name` 只有主名。
     * 这个字段一旦漏掉，列表少后缀，下载存盘也少后缀 —— 文件直接打不开。
     */
    @Test
    fun `云盘条目要把 extension 拼回文件名`() {
        val payload = Json.parseToJsonElement(
            """
            {"items": [
              {"docwsid": "A", "name": "s5", "extension": "png", "type": "FILE", "size": 67322},
              {"docwsid": "B", "name": "report.pdf", "extension": "pdf", "type": "FILE"},
              {"docwsid": "C", "name": "无后缀", "type": "FILE"},
              {"docwsid": "D", "name": "照片", "type": "FOLDER"}
            ]}
            """.trimIndent(),
        )
        val entries = newClient().drive.parseEntries(payload)
        assertEquals("s5.png", entries[0].name)
        // 名字里已经有后缀时不能拼成 report.pdf.pdf
        assertEquals("report.pdf", entries[1].name)
        assertEquals("无后缀", entries[2].name)
        assertEquals("照片", entries[3].name)
    }

    // ------------------------------------------------------------ 存储空间

    @Test
    fun `存储空间解析出总量、分类与配额状态`() {
        val raw = """
            {
              "storageUsageByMedia": [
                {"mediaKey": "photos", "displayLabel": "Photos and Videos", "displayColor": "ffcc00", "usageInBytes": 1000},
                {"mediaKey": "backup", "displayLabel": "Backups", "displayColor": "5856d6", "usageInBytes": 250}
              ],
              "storageUsageInfo": {
                "usedStorageInBytes": 1250,
                "totalStorageInBytes": 5000
              },
              "quotaStatus": {"paidQuota": true, "overQuota": false}
            }
        """.trimIndent()

        val usage = newClient().account.parseStorageUsage(raw)
        assertEquals(5000L, usage.totalBytes)
        assertEquals(1250L, usage.usedBytes)
        assertEquals(3750L, usage.freeBytes)
        assertEquals(0.25f, usage.usedRatio)
        assertTrue(usage.paidQuota)
        assertFalse(usage.overQuota)
        assertEquals(listOf("photos", "backup"), usage.itemsBySize().map { it.mediaKey })
        assertEquals("#ffcc00", "#" + usage.itemsBySize().first().colorHex)
    }

    @Test
    fun `存储空间缺字段时降级而不是崩溃`() {
        val usage = newClient().account.parseStorageUsage("""{"storageUsageInfo": {}}""")
        assertEquals(0L, usage.totalBytes)
        assertEquals(0f, usage.usedRatio)
        assertTrue(usage.items.isEmpty())
    }

    private fun b64(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
}
