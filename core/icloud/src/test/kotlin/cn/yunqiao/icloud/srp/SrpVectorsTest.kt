package cn.yunqiao.icloud.srp

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * SRP 向量测试：向量来自 icloudpd 同款 Python 实现（srp 库），
 * 并用服务端公式独立复算过 M1/M2，因此可作为"标准答案"。
 */
class SrpVectorsTest {

    private fun loadVectors(): JsonObject {
        val text = checkNotNull(javaClass.getResourceAsStream("/srp_vectors.json")) {
            "缺少测试向量资源 srp_vectors.json"
        }.bufferedReader().readText()
        return Json.parseToJsonElement(text) as JsonObject
    }

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.num(key: String): Int = getValue(key).jsonPrimitive.content.toInt()

    private fun ByteArray.toHex(): String = toLowerHex()

    @Test
    fun `SRP 全流程与 Python 参考实现逐位一致`() {
        val v = loadVectors()

        val username = v.str("username")
        val password = v.str("password")
        val salt = hexToBytes(v.str("saltHex"))
        val aBytes = hexToBytes(v.str("aBytesHex"))
        val protocol = v.str("protocol")
        val iterations = v.num("iterations")
        val publicB = BigInteger(1, hexToBytes(v.str("BHex")))

        val client = SrpClient(username, SrpPassword(password), fixedA = aBytes)

        assertEquals(v.str("AHex"), client.publicA.toFixed().toHex(), "公开值 A 不一致")
        assertEquals(v.str("kHex"), client.multiplierK.toFixed().toHex(), "乘数 k 不一致")

        val proof = client.challenge(salt, publicB, protocol, iterations)

        assertEquals(v.str("xHex"), proof.secretX.toHex(), "x 不一致")
        assertEquals(v.str("vHex"), proof.verifier.toHex(), "验证值 v 不一致")
        assertEquals(v.str("KHex"), proof.sessionKey.toHex(), "会话密钥 K 不一致")
        assertEquals(v.str("M1Hex"), proof.m1.toHex(), "客户端证明 M1 不一致")
        assertEquals(v.str("M2Hex"), proof.m2.toHex(), "服务端证明 M2 不一致")
    }

    @Test
    fun `服务端可用 v 复算出同一个 B`() {
        val v = loadVectors()
        val client = SrpClient(v.str("username"), SrpPassword(v.str("password")))
        val salt = hexToBytes(v.str("saltHex"))
        val passwordHash = SrpPassword(v.str("password"))
            .encode(v.str("protocol"), salt, v.num("iterations"))
        val x = BigInteger(
            1,
            sha256(salt, sha256(byteArrayOf(0x3A) + passwordHash)),
        )
        val bigV = Rfc5054.G.modPow(x, Rfc5054.N)
        val b = BigInteger(1, hexToBytes(v.str("bHex")))
        val expectedB = client.multiplierK.multiply(bigV)
            .add(Rfc5054.G.modPow(b, Rfc5054.N))
            .mod(Rfc5054.N)
        assertEquals(
            BigInteger(1, hexToBytes(v.str("BHex"))),
            expectedB,
            "由 v 与 b 推导出的 B 不一致，说明 k/v 计算有误",
        )
    }

    @Test
    fun `PBKDF2 结果与 Python hashlib 一致`() {
        val v = loadVectors()
        val actual = SrpPassword(v.str("password"))
            .encode(v.str("protocol"), hexToBytes(v.str("saltHex")), v.num("iterations"))
        assertEquals(v.str("pkcsHex"), actual.toHex(), "s2k_fo 派生结果不一致")
    }
}

internal fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "hex 长度必须为偶数" }
    return ByteArray(hex.length / 2) { i ->
        hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}
