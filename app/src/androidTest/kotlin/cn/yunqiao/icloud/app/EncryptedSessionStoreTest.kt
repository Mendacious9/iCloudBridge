package cn.yunqiao.icloud.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.yunqiao.icloud.model.SessionState
import cn.yunqiao.icloud.store.StoredCookie
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 会话存储必须在真机/模拟器上验：AndroidKeyStore 是系统服务，JVM 单测里没有。
 *
 * 重点不是「能存能取」——那是顺带的。重点是**落盘的东西里到底还有没有令牌明文**，
 * 因为这段代码存在的唯一理由就是把令牌从明文变成密文。
 */
@RunWith(AndroidJUnit4::class)
class EncryptedSessionStoreTest {

    private companion object {
        const val PREFS = "icloud_bridge_secure"
        const val APPLE_ID = "demo@example.com"
        const val SECRET = "AT-9f2c1b7e-SECRET-TOKEN-4d8a"
    }

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var legacyDir: File
    private lateinit var store: EncryptedSessionStore

    private fun prefs() = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        legacyDir = File(ctx.filesDir, "session_test").apply { mkdirs() }
        store = EncryptedSessionStore(ctx, legacyDir)
        store.clear(APPLE_ID)
    }

    @After
    fun tearDown() {
        store.clear(APPLE_ID)
        legacyDir.deleteRecursively()
    }

    private fun sample() = SessionState(
        appleId = APPLE_ID,
        clientId = "client-1234",
        sessionToken = SECRET,
        dsid = "12345678901",
        accountCountry = "CHN",
    )

    @Test
    fun sessionRoundTrip() {
        store.writeSession(sample())
        val back = store.readSession(APPLE_ID)
        assertNotNull("刚写入的会话应能读回", back)
        assertEquals(APPLE_ID, back!!.appleId)
        assertEquals(SECRET, back.sessionToken)
        assertEquals("12345678901", back.dsid)
    }

    @Test
    fun storedValueContainsNoPlaintext() {
        store.writeSession(sample())
        // commit 一次把内存刷到磁盘，才能检查真正落在磁盘上的内容
        prefs().edit().putLong("__flush_probe", 1L).commit()

        val values = prefs().all.values.joinToString("|") { it.toString() }
        assertFalse("存储值里不得出现会话令牌明文", values.contains(SECRET))
        assertFalse("存储值里不得出现账号明文", values.contains(APPLE_ID))
        assertTrue("存储值应是 v1: 前缀的密文", values.contains("v1:"))

        val xml = File(ctx.dataDir, "shared_prefs/$PREFS.xml")
        if (xml.exists()) {
            val disk = xml.readText()
            assertFalse("磁盘 XML 里不得出现令牌明文", disk.contains(SECRET))
            assertFalse("磁盘 XML 里不得出现账号明文", disk.contains(APPLE_ID))
        }
    }

    @Test
    fun tamperedCiphertextIsTreatedAsLoggedOut() {
        store.writeSession(sample())
        val key = "bundle_" + APPLE_ID.filter { it.isLetterOrDigit() }
        val blob = prefs().getString(key, null)
        assertNotNull(blob)

        // GCM 带认证标签：改任何一位都应该解不开，而不是解出乱码
        val tampered = blob!!.dropLast(6) + "AAAAAA"
        prefs().edit().putString(key, tampered).commit()

        assertNull("密文被改动后必须按未登录处理", store.readSession(APPLE_ID))
        assertNull("被污染的密文应已被清除", prefs().getString(key, null))
    }

    @Test
    fun legacyPlaintextIsMigratedThenDeleted() {
        val legacy = File(legacyDir, "demoexamplecom.session.json")
        legacy.writeText(
            """{"session":{"appleId":"$APPLE_ID","clientId":"legacy-client",""" +
                """"sessionToken":"legacy-token"},"cookies":[]}""",
            Charsets.UTF_8,
        )

        val restored = store.readSession(APPLE_ID)
        assertEquals("旧会话应原样恢复", "legacy-token", restored?.sessionToken)
        assertFalse("迁移完成后明文文件必须删除", legacy.exists())

        // 第二次读必须走密文，而不是又读一遍文件
        assertEquals("legacy-token", store.readSession(APPLE_ID)?.sessionToken)
    }

    @Test
    fun sessionAndCookiesDoNotClobberEachOther() {
        store.writeSession(sample())
        val cookies = listOf(
            StoredCookie(
                name = "X-APPLE-WEBAUTH-TOKEN",
                value = "v=2:t=abc",
                domain = ".icloud.com",
                path = "/",
                expiresAt = 0L,
                secure = true,
                httpOnly = true,
                hostOnly = false,
            ),
        )
        store.writeCookies(APPLE_ID, cookies)

        assertEquals("写 Cookie 不能把会话冲掉", SECRET, store.readSession(APPLE_ID)?.sessionToken)
        assertEquals(cookies, store.readCookies(APPLE_ID))

        store.writeSession(sample().copy(sessionToken = SECRET + "-rotated"))
        assertEquals("写会话不能把 Cookie 冲掉", 1, store.readCookies(APPLE_ID).size)
    }

    @Test
    fun clearRemovesEverything() {
        store.writeSession(sample())
        store.writeCookies(APPLE_ID, emptyList())
        store.clear(APPLE_ID)
        assertNull(store.readSession(APPLE_ID))
        assertTrue(store.readCookies(APPLE_ID).isEmpty())
    }
}
