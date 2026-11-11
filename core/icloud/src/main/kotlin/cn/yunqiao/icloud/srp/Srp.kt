package cn.yunqiao.icloud.srp

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * SRP-6a，参数与 iCloud 网页端一致：
 * - RFC 5054 的 2048 位群（N, g=2）
 * - SHA-256
 * - Apple 的 s2k / s2k_fo 密码派生（PBKDF2-HMAC-SHA256）
 *
 * 正确性由 test 目录下的向量测试保证（向量由 icloudpd 同款 Python 实现生成，
 * 并经服务端公式独立复算交叉校验）。
 */
object Rfc5054 {
    /** RFC 5054 Appendix A，2048-bit 群 */
    const val N_HEX: String =
        "AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050" +
            "A37329CBB4A099ED8193E0757767A13DD52312AB4B03310DCD7F48A9DA04FD50" +
            "E8083969EDB767B0CF6095179A163AB3661A05FBD5FAAAE82918A9962F0B93B8" +
            "55F97993EC975EEAA80D740ADBF4FF747359D041D5C33EA71D281E446B14773B" +
            "CA97B43A23FB801676BD207A436C6481F1D2B9078717461A5B9D32E688F87748" +
            "544523B524B0D57D5EA77A2775D2ECFA032CFBDBF52FB3786160279004E57AE6" +
            "AF874E7303CE53299CCC041C7BC308D82A5698F3A8D0C38271AE35F8E9DBFBB6" +
            "94B5C803D89F7AE435DE236D525F54759B65E372FCD68EF20FA7111F9E4AFF73"

    val N: BigInteger = BigInteger(N_HEX, 16)
    val G: BigInteger = BigInteger.valueOf(2)

    /** 群元素的固定字节长度（2048 bit） */
    const val LEN: Int = 256
}

/** SHA-256 拼接哈希 */
internal fun sha256(vararg parts: ByteArray): ByteArray {
    val md = MessageDigest.getInstance("SHA-256")
    for (p in parts) md.update(p)
    return md.digest()
}

/** 左补零到固定长度（对应 srp 库 H(..., width=256) 的补零规则） */
internal fun BigInteger.toFixed(len: Int = Rfc5054.LEN): ByteArray {
    val raw = toByteArray()
    val unsigned = if (raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
    if (unsigned.size >= len) return unsigned.copyOfRange(unsigned.size - len, unsigned.size)
    val out = ByteArray(len)
    unsigned.copyInto(out, len - unsigned.size)
    return out
}

/** 最短字节表示（对应 Python srp 的 long_to_bytes） */
internal fun BigInteger.toMinimal(): ByteArray {
    val raw = toByteArray()
    return if (raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
}

private val HEX_CHARS = "0123456789abcdef".toCharArray()

internal fun ByteArray.toLowerHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        out[i * 2] = HEX_CHARS[v ushr 4]
        out[i * 2 + 1] = HEX_CHARS[v and 0x0F]
    }
    return String(out)
}

/** PBKDF2-HMAC-SHA256（手写实现，避免 PBEKeySpec 的 char/byte 转换破坏二进制保真） */
internal fun pbkdf2HmacSha256(
    password: ByteArray,
    salt: ByteArray,
    iterations: Int,
    keyLength: Int,
): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(password, "HmacSHA256"))
    val out = ByteArray(keyLength)
    var offset = 0
    var blockIndex = 1
    while (offset < keyLength) {
        mac.reset()
        mac.update(salt)
        mac.update(
            byteArrayOf(
                (blockIndex ushr 24).toByte(),
                (blockIndex ushr 16).toByte(),
                (blockIndex ushr 8).toByte(),
                blockIndex.toByte(),
            )
        )
        var u = mac.doFinal()
        val t = u.copyOf()
        for (i in 2..iterations) {
            mac.reset()
            mac.update(u)
            u = mac.doFinal()
            for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
        }
        val take = minOf(keyLength - offset, t.size)
        t.copyInto(out, offset, 0, take)
        offset += take
        blockIndex++
    }
    return out
}

/**
 * Apple 的 s2k / s2k_fo 密码派生：
 * - s2k_fo：先 SHA-256 取十六进制字符串（ASCII），再 PBKDF2
 * - s2k：先 SHA-256 取原始字节，再 PBKDF2
 */
class SrpPassword(private val password: String) {
    fun encode(protocol: String, salt: ByteArray, iterations: Int): ByteArray {
        val passHash = sha256(password.toByteArray(Charsets.UTF_8))
        val material =
            if (protocol == "s2k_fo") passHash.toLowerHex().toByteArray(Charsets.US_ASCII)
            else passHash
        return pbkdf2HmacSha256(material, salt, iterations, 32)
    }
}

/** SRP 客户端一次认证的结果 */
data class SrpProof(
    val publicA: ByteArray,
    val sessionKey: ByteArray,
    val m1: ByteArray,
    val m2: ByteArray,
    val secretX: ByteArray,
    val verifier: ByteArray,
    val multiplierK: ByteArray,
)

class SrpClient(
    private val username: String,
    private val password: SrpPassword,
    /** 仅测试用：固定私钥 a */
    fixedA: ByteArray? = null,
) {
    private val n: BigInteger = Rfc5054.N
    private val g: BigInteger = Rfc5054.G

    val secretA: BigInteger = fixedA?.let { BigInteger(1, it) } ?: randomA()
    val publicA: BigInteger = g.modPow(secretA, n)

    /** k = H(N | PAD(g)) */
    val multiplierK: BigInteger =
        BigInteger(1, sha256(n.toFixed(), g.toFixed()))

    /**
     * 处理服务端挑战，生成 M1/M2。
     * @param protocol 服务端返回的 "s2k" 或 "s2k_fo"
     */
    fun challenge(
        salt: ByteArray,
        publicB: BigInteger,
        protocol: String,
        iterations: Int,
    ): SrpProof {
        require(publicB.mod(n) != BigInteger.ZERO) { "SRP 安全校验失败：B mod N == 0" }

        val u = BigInteger(1, sha256(publicA.toFixed(), publicB.toFixed()))
        require(u != BigInteger.ZERO) { "SRP 安全校验失败：u == 0" }

        val passwordHash = password.encode(protocol, salt, iterations)
        // no_username_in_x：x = H(salt | H(":" + password))
        val inner = sha256(byteArrayOf(0x3A) + passwordHash)
        val x = BigInteger(1, sha256(salt, inner))

        val v = g.modPow(x, n)
        val base = publicB.subtract(multiplierK.multiply(v)).mod(n)
        val exponent = secretA.add(u.multiply(x))
        val sessionSecret = base.modPow(exponent, n)
        val key = sha256(sessionSecret.toMinimal())

        val hN = sha256(n.toFixed())
        val hG = sha256(g.toFixed())
        val hNxorg = ByteArray(32) { (hN[it].toInt() xor hG[it].toInt()).toByte() }
        val m1 = sha256(
            hNxorg,
            sha256(username.toByteArray(Charsets.UTF_8)),
            salt,
            publicA.toMinimal(),
            publicB.toMinimal(),
            key,
        )
        val m2 = sha256(publicA.toMinimal(), m1, key)

        return SrpProof(
            publicA = publicA.toFixed(),
            sessionKey = key,
            m1 = m1,
            m2 = m2,
            secretX = x.toFixed(),
            verifier = v.toFixed(),
            multiplierK = multiplierK.toFixed(),
        )
    }

    private fun randomA(): BigInteger {
        val bytes = ByteArray(Rfc5054.LEN)
        SecureRandom().nextBytes(bytes)
        bytes[0] = (bytes[0].toInt() or 0x80).toByte() // 保证足够位长
        return BigInteger(1, bytes)
    }
}
