"""生成 SRP 测试向量：用 Python srp 库（icloudpd 同款实现）算出标准答案，
并用服务端公式独立复算 M1/M2 做交叉校验，供 Kotlin 实现逐位比对。"""
import hashlib
import json

import srp

srp.rfc5054_enable()
srp.no_username_in_x()

HASH = hashlib.sha256
GROUP_BYTES = 256  # 2048-bit


def h(*parts) -> bytes:
    """等价于 srp 的 H(hash, *args, width=GROUP_BYTES)：RFC5054 模式下按 256 字节左补零"""
    m = HASH()
    for p in parts:
        data = p if isinstance(p, bytes) else p.to_bytes((p.bit_length() + 7) // 8 or 1, "big")
        m.update(b"\x00" * (GROUP_BYTES - len(data)))
        m.update(data)
    return m.digest()


def h_raw(*parts) -> bytes:
    """不带补零的 H"""
    m = HASH()
    for p in parts:
        m.update(p if isinstance(p, bytes) else p.to_bytes((p.bit_length() + 7) // 8 or 1, "big"))
    return m.digest()


class ApplePassword:
    def __init__(self, pwd: str):
        self.pwd = pwd

    def set_encrypt_info(self, protocol: str, salt: bytes, iterations: int):
        self.protocol, self.salt, self.iterations = protocol, salt, iterations

    def encode(self) -> bytes:
        hsh = HASH(self.pwd.encode())
        digest = hsh.hexdigest().encode() if self.protocol == "s2k_fo" else hsh.digest()
        return hashlib.pbkdf2_hmac("sha256", digest, self.salt, self.iterations, 32)


I = "test@example.com"
P = "SecretPass123!"
SALT = bytes.fromhex("00112233445566778899aabbccddeeff")
PROTOCOL = "s2k_fo"
ITERATIONS = 20000
A_BYTES = bytes(range(256))
B_BYTES = bytes([3] + [0] * 255)

pw = ApplePassword(P)
usr = srp.User(I, pw, hash_alg=srp.SHA256, ng_type=srp.NG_2048, bytes_a=A_BYTES)
_, A = usr.start_authentication()
N, g, k = usr.N, usr.g, usr.k

pw.set_encrypt_info(PROTOCOL, SALT, ITERATIONS)
pw_bytes = pw.encode()

# 客户端 x（no_username_in_x 下 username 为空）
x = int.from_bytes(h_raw(SALT, h_raw(b":" + pw_bytes)), "big")
v = pow(g, x, N)

# 服务端生成 B
b = int.from_bytes(B_BYTES, "big")
B = (k * v + pow(g, b, N)) % N
B_bytes = B.to_bytes(GROUP_BYTES, "big")

# 客户端计算 M1
M1 = usr.process_challenge(SALT, B_bytes)

# ---- 服务端独立复算（交叉校验）----
A_int = int.from_bytes(A, "big")
u = int.from_bytes(h(A_int, B), "big")
S_server = pow((A_int * pow(v, u, N)) % N, b, N)
K_server = HASH(S_server.to_bytes((S_server.bit_length() + 7) // 8, "big")).digest()
hNxorg = bytes(a ^ c for a, c in zip(HASH(N.to_bytes(GROUP_BYTES, "big")).digest(),
                                     HASH(b"\x00" * (GROUP_BYTES - 1) + b"\x02").digest()))
M1_server = HASH(hNxorg + HASH(I.encode()).digest() + SALT + A + B_bytes + K_server).digest()
M2_server = HASH(A + M1_server + K_server).digest()

vectors = {
    "username": I,
    "password": P,
    "saltHex": SALT.hex(),
    "protocol": PROTOCOL,
    "iterations": ITERATIONS,
    "aBytesHex": A_BYTES.hex(),
    "aHex": usr.a.to_bytes(GROUP_BYTES, "big").hex(),
    "AHex": A.hex(),
    "bHex": B_BYTES.hex(),
    "BHex": B_bytes.hex(),
    "kHex": k.to_bytes(GROUP_BYTES, "big").hex(),
    "pkcsHex": pw_bytes.hex(),
    "xHex": x.to_bytes(GROUP_BYTES, "big").hex(),
    "vHex": v.to_bytes(GROUP_BYTES, "big").hex(),
    "KHex": usr.K.hex(),
    "M1Hex": M1.hex(),
    "M2Hex": usr.H_AMK.hex(),
    "KServerHex": K_server.hex(),
    "M1ServerHex": M1_server.hex(),
    "M2ServerHex": M2_server.hex(),
    "crossCheckM1": M1 == M1_server,
    "crossCheckM2": usr.H_AMK == M2_server,
}
print(json.dumps(vectors, indent=2))
