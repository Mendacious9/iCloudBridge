#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
云桥 iCloudBridge — M0 协议 PoC
================================

目的：在写安卓 App 之前，先在命令行完整验证 iCloud 私有 Web API 协议链路：
  1. App 专用密码登录（SRP 安全远程密码协议，2026 新流程）
  2. 双重认证 2FA（受信设备推送验证码 + 信任会话）
  3. accountLogin 建立 iCloud 会话（获取 webservices 服务地址）
  4. 列出用户相册（CPLAlbumByPositionLive 查询）
  5. 获取最新一张照片的下载 URL（可实际下载验证）
  6. 会话持久化 —— 二次运行免密码免 2FA（验证"持续登录"设计）

用法：
  python icloud_poc.py                 完整流程：登录 + 列相册 + 最新照片
  python icloud_poc.py --check         仅验证已保存的会话是否有效（持续登录验证）
  python icloud_poc.py --sms           2FA 直接走短信验证码（没有苹果设备时用）
  python icloud_poc.py --download      登录后下载最新一张照片原图到 ./downloads/
  python icloud_poc.py --drive         验证云盘协议：列出 iCloud 云盘根目录
  python icloud_poc.py --drive --drive-download 文件名   下载云盘根目录下的文件
  python icloud_poc.py --domain cn     国区 Apple ID（云上贵州，icloud.com.cn）用 cn
  python icloud_poc.py --reset         清除本地保存的会话数据

密码输入（二选一）：
  1. 交互输入（推荐；输入不回显，粘贴用鼠标右键或 Ctrl+V，输入后按回车）
  2. 环境变量 ICLOUD_APP_PASSWORD（多行命令务必逐行执行，避免顺序错乱）
密码指 Apple ID 账户主密码，不是 App 专用密码。

前置条件：
  1. Apple ID 已开启双重认证
  2. 准备 Apple ID 账户密码（主密码）。注意：网页 API 实测不接受 App 专用密码
     （专用密码仅适用于邮件/日历/通讯录等 IMAP/CalDAV 协议，在登录阶段会被拒 -20101）
  3. iCloud 已开启"允许在网页上访问 iCloud 数据"
  4. 账户未开启"高级数据保护(ADP)"
  5. 用户名请使用账户的【主要 Apple ID】（别名可能被拒）

密码通过安全输入（getpass），不会出现在命令行参数、日志或本地文件中。
会话数据保存在 ./poc_session/（App 阶段将替换为 Android Keystore 加密存储）。

协议参考：icloud-photos-downloader/icloud_photos_downloader v1.32.3（MIT License）
"""

import argparse
import base64
import getpass
import hashlib
import http.cookiejar as cookielib
import json
import os
import sys
import time
from pathlib import Path
from urllib.parse import urlencode
from uuid import uuid1

import requests

try:
    import srp
except ImportError:
    srp = None

# ---------------------------------------------------------------------------
# 常量（对齐 icloud.com 网页端客户端，来源：pyicloud_ipd v1.32.3）
# ---------------------------------------------------------------------------

WIDGET_KEY = "d39ba9916b7251055b22c7f910e2ea796ee65e98b2ddecea8f5dde8d9d1a815d"
OAUTH_CLIENT_ID = WIDGET_KEY
USER_AGENT = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
)
CLIENT_BUILD_NUMBER = "2522Project44"
CLIENT_MASTERING_NUMBER = "2522B2"

DOMAINS = {
    "com": {
        "AUTH_ENDPOINT": "https://idmsa.apple.com/appleauth/auth",
        "AUTH_ROOT": "https://idmsa.apple.com",
        "HOME_ENDPOINT": "https://www.icloud.com",
        "SETUP_ENDPOINT": "https://setup.icloud.com/setup/ws/1",
    },
    "cn": {
        "AUTH_ENDPOINT": "https://idmsa.apple.com.cn/appleauth/auth",
        "AUTH_ROOT": "https://idmsa.apple.com.cn",
        "HOME_ENDPOINT": "https://www.icloud.com.cn",
        "SETUP_ENDPOINT": "https://setup.icloud.com.cn/setup/ws/1",
    },
}

# 登录过程中需要从响应头持续捕获并持久化的会话数据
HEADER_DATA = {
    "X-Apple-ID-Account-Country": "account_country",
    "X-Apple-ID-Session-Id": "session_id",
    "X-Apple-Session-Token": "session_token",
    "X-Apple-TwoSV-Trust-Token": "trust_token",
    "X-Apple-TwoSV-Trust-Eligible": "trust_eligible",
    "scnt": "scnt",
}

DATA_DIR = Path(__file__).parent / "poc_session"
DOWNLOAD_DIR = Path(__file__).parent / "downloads"

# 照片各分辨率字段的下载信息所在字段名（asset/master 记录中）
PHOTO_VERSION_LOOKUP = {
    "original": "resOriginal",
    "medium": "resJPEGMed",
    "thumb": "resJPEGThumb",
}

# 资产查询必须显式声明的字段列表（对齐 pyicloud_ipd PhotoAlbum._list_query_gen，
# 缺少该列表时苹果 Hyperion 索引接口会返回 BAD_REQUEST: Unexpected input）
PHOTO_DESIRED_KEYS = [
    "resJPEGFullWidth", "resJPEGFullHeight", "resJPEGFullFileType",
    "resJPEGFullFingerprint", "resJPEGFullRes",
    "resJPEGLargeWidth", "resJPEGLargeHeight", "resJPEGLargeFileType",
    "resJPEGLargeFingerprint", "resJPEGLargeRes",
    "resJPEGMedWidth", "resJPEGMedHeight", "resJPEGMedFileType",
    "resJPEGMedFingerprint", "resJPEGMedRes",
    "resJPEGThumbWidth", "resJPEGThumbHeight", "resJPEGThumbFileType",
    "resJPEGThumbFingerprint", "resJPEGThumbRes",
    "resVidFullWidth", "resVidFullHeight", "resVidFullFileType",
    "resVidFullFingerprint", "resVidFullRes",
    "resVidMedWidth", "resVidMedHeight", "resVidMedFileType",
    "resVidMedFingerprint", "resVidMedRes",
    "resVidSmallWidth", "resVidSmallHeight", "resVidSmallFileType",
    "resVidSmallFingerprint", "resVidSmallRes",
    "resSidecarWidth", "resSidecarHeight", "resSidecarFileType",
    "resSidecarFingerprint", "resSidecarRes",
    "itemType", "dataClassType", "filenameEnc", "originalOrientation",
    "resOriginalWidth", "resOriginalHeight", "resOriginalFileType",
    "resOriginalFingerprint", "resOriginalRes",
    "resOriginalAltWidth", "resOriginalAltHeight", "resOriginalAltFileType",
    "resOriginalAltFingerprint", "resOriginalAltRes",
    "resOriginalVidComplWidth", "resOriginalVidComplHeight",
    "resOriginalVidComplFileType", "resOriginalVidComplFingerprint",
    "resOriginalVidComplRes",
    "isDeleted", "isExpunged", "dateExpunged", "remappedRef",
    "recordName", "recordType", "recordChangeTag", "masterRef",
    "adjustmentRenderType", "assetDate", "addedDate", "isFavorite",
    "isHidden", "orientation", "duration", "assetSubtype", "assetSubtypeV2",
    "assetHDRType", "burstFlags", "burstFlagsExt", "burstId", "captionEnc",
    "locationEnc", "locationV2Enc", "locationLatitude", "locationLongitude",
    "adjustmentType", "timeZoneOffset", "vidComplDurValue",
    "vidComplDurScale", "vidComplDispValue", "vidComplDispScale",
    "keywordsEnc", "extendedDescEnc", "adjustedMediaMetaDataEnc",
    "adjustmentSimpleDataEnc", "vidComplVisibilityState",
    "customRenderedValue", "containerId", "itemId", "position", "isKeyAsset",
]


class PocError(Exception):
    """PoC 流程错误，附带用户可读的中文说明。"""


class SrpPassword:
    """SRP 协议要求的密码编码器（s2k / s2k_fo）。"""

    def __init__(self, password: str):
        self.pwd = password

    def set_encrypt_info(self, protocol: str, salt: bytes, iterations: int):
        self.protocol = protocol
        self.salt = salt
        self.iterations = iterations

    def encode(self) -> bytes:
        password_hash = hashlib.sha256(self.pwd.encode())
        password_digest = (
            password_hash.hexdigest().encode()
            if self.protocol == "s2k_fo"
            else password_hash.digest()
        )
        return hashlib.pbkdf2_hmac(
            "sha256", password_digest, self.salt, self.iterations, 32
        )


class ICloudPoc:
    """最小可用的 iCloud Web API 客户端，实现 M0 验证链路。"""

    def __init__(self, apple_id: str, password: str, domain: str = "com"):
        if domain not in DOMAINS:
            raise PocError(f"不支持的域名区域: {domain}（可选 com / cn）")
        self.apple_id = apple_id
        self.password = password
        self.domain = domain
        self.cfg = DOMAINS[domain]

        DATA_DIR.mkdir(parents=True, exist_ok=True)
        safe_id = "".join(c for c in apple_id if c.isalnum()) or "account"
        self.session_path = DATA_DIR / f"{safe_id}.session.json"
        self.cookiejar_path = DATA_DIR / f"{safe_id}.cookies.txt"

        self.session_data: dict = {}
        if self.session_path.exists():
            try:
                self.session_data = json.loads(self.session_path.read_text("utf-8"))
            except json.JSONDecodeError:
                pass
        self.client_id = self.session_data.get("client_id") or f"auth-{uuid1()}".lower()
        self.session_data["client_id"] = self.client_id

        self.data: dict = {}          # accountLogin 返回的账户与服务信息
        self._warned: set = set()
        self.params = {
            "clientBuildNumber": CLIENT_BUILD_NUMBER,
            "clientMasteringNumber": CLIENT_MASTERING_NUMBER,
            "clientId": self.client_id,
        }

        self.http = requests.Session()
        self.http.headers.update(
            {
                "Origin": self.cfg["HOME_ENDPOINT"],
                "Referer": f"{self.cfg['HOME_ENDPOINT']}/",
                "User-Agent": USER_AGENT,
            }
        )
        self.http.cookies = cookielib.LWPCookieJar(str(self.cookiejar_path))
        if self.cookiejar_path.exists():
            try:
                self.http.cookies.load(ignore_discard=True, ignore_expires=True)
            except Exception:
                pass

    # ----------------------------------------------------------------- 工具

    def _save_state(self):
        """保存会话数据。Windows 上文件可能被杀毒/索引服务瞬时锁定，
        因此改为原子写入 + 重试，且失败不影响本次运行。"""
        payload = json.dumps(self.session_data, ensure_ascii=False, indent=2)
        tmp = self.session_path.with_suffix(".tmp")
        try:
            tmp.write_text(payload, "utf-8")
            os.replace(tmp, self.session_path)
        except OSError:
            try:
                self.session_path.write_text(payload, "utf-8")
            except OSError as exc:
                self._warn_once(f"会话数据保存失败：{exc}")

        if isinstance(self.http.cookies, cookielib.LWPCookieJar):
            for _ in range(3):
                try:
                    self.http.cookies.save(ignore_discard=True, ignore_expires=True)
                    return
                except OSError:
                    time.sleep(0.3)
            self._warn_once(
                "Cookie 保存失败（文件被占用），本次运行不受影响，"
                "但退出后需重新登录"
            )

    def _warn_once(self, message: str):
        if message in self._warned:
            return
        self._warned.add(message)
        print(f"[!] {message}")

    def _capture_headers(self, response: requests.Response):
        for header, key in HEADER_DATA.items():
            if response.headers.get(header):
                self.session_data[key] = response.headers[header]

    def _post_json(self, url, payload: dict | None, headers: dict, **kw):
        """发请求并统一处理：会话头捕获、状态持久化、错误转译。"""
        resp = self.http.post(
            url,
            data=json.dumps(payload) if payload is not None else "null",
            headers=headers,
            timeout=45,
            **kw,
        )
        self._capture_headers(resp)
        self._save_state()
        return resp

    def auth_headers(self, extra: dict | None = None) -> dict:
        headers = {
            "Accept": "application/json, text/javascript",
            "Content-Type": "application/json",
            "X-Apple-OAuth-Client-Id": OAUTH_CLIENT_ID,
            "X-Apple-OAuth-Client-Type": "firstPartyAuth",
            "X-Apple-OAuth-Redirect-URI": self.cfg["HOME_ENDPOINT"],
            "X-Apple-OAuth-Require-Grant-Code": "true",
            "X-Apple-OAuth-Response-Mode": "web_message",
            "X-Apple-OAuth-Response-Type": "code",
            "X-Apple-OAuth-State": self.client_id,
            "X-Apple-Widget-Key": WIDGET_KEY,
        }
        for key in ("scnt", "session_id"):
            if self.session_data.get(key):
                headers["scnt" if key == "scnt" else "X-Apple-ID-Session-Id"] = (
                    self.session_data[key]
                )
        if extra:
            headers.update(extra)
        return headers

    # ------------------------------------------------------------- 登录链路

    def login(self, interactive_2fa: bool = True, prefer_sms: bool = False):
        """完整登录：优先复用已保存会话，否则走 SRP + 2FA。"""
        if self.session_data.get("session_token"):
            print("[*] 发现已保存会话，尝试复用（持续登录验证）...")
            try:
                self.account_login()
                if not self.requires_2fa():
                    print("[+] 会话复用成功，无需重新登录")
                    return
                print("[*] 会话有效但需要补充 2FA 验证")
            except PocError as exc:
                print(f"[!] 会话复用失败：{exc}，将重新登录")

        if not self.password:
            self.password = self._prompt_password()

        print("[1/6] SRP 登录（账户主密码经 SRP 密钥交换，不传输明文密码）...")
        self._srp_signin()
        self._save_state()

        try:
            self.account_login()
        except PocError:
            pass  # 2FA 未完成时 accountLogin 可能失败，先补 2FA

        if self.requires_2fa() or "dsInfo" not in self.data:
            if not interactive_2fa:
                raise PocError("需要 2FA 验证（本次运行为非交互模式）")
            print("[2/6] 需要双重认证（2FA）")
            self._do_2fa(prefer_sms=prefer_sms)

        print("[3/6] 建立 iCloud 会话（accountLogin）...")
        self.account_login()
        self.params["dsid"] = self.data["dsInfo"]["dsid"]

    def _prompt_password(self) -> str:
        """仅在会话失效、需要重新登录时才提示输入密码。"""
        print("提示：此处应输入 Apple ID 账户密码（主密码），不是 App 专用密码")
        password = getpass.getpass(
            "Apple ID 账户密码（粘贴或输入后回车，界面不显示）: "
        ).strip()
        if not password:
            raise PocError("未输入密码")
        if len(password) == 19 and password.count("-") == 3:
            print(
                "[!] 注意：这 19 位格式看起来是 App 专用密码，"
                "网页 API 实测不接受，请改用 Apple ID 账户主密码"
            )
        else:
            print(f"[*] 密码已接收：长度 {len(password)} 字符")
        return password

    def _srp_signin(self):
        """SRP 两步登录：/signin/init + /signin/complete（2026 现行流程）。"""
        if srp is None:
            raise PocError("缺少 srp 库：pip install srp")

        srp_password = SrpPassword(self.password)
        srp.rfc5054_enable()
        srp.no_username_in_x()
        usr = srp.User(self.apple_id, srp_password, hash_alg=srp.SHA256, ng_type=srp.NG_2048)
        uname, a_key = usr.start_authentication()

        headers = self.auth_headers(
            {
                "Origin": self.cfg["AUTH_ROOT"],
                "Referer": f"{self.cfg['AUTH_ROOT']}/",
            }
        )
        resp = self._post_json(
            f"{self.cfg['AUTH_ENDPOINT']}/signin/init",
            {
                "a": base64.b64encode(a_key).decode(),
                "accountName": uname,
                "protocols": ["s2k", "s2k_fo"],
            },
            headers,
        )
        if resp.status_code == 401:
            raise PocError("登录被拒绝（401）：请检查 Apple ID 与 App 专用密码是否正确")
        if resp.status_code >= 400:
            raise PocError(f"signin/init 失败：HTTP {resp.status_code} {resp.text[:200]}")
        body = resp.json()
        salt = base64.b64decode(body["salt"])
        b_key = base64.b64decode(body["b"])
        c_token = body["c"]
        protocol = body["protocol"]

        srp_password.set_encrypt_info(protocol, salt, body["iteration"])
        m1 = usr.process_challenge(salt, b_key)
        m2 = usr.H_AMK

        data = {
            "accountName": uname,
            "c": c_token,
            "m1": base64.b64encode(m1).decode(),
            "m2": base64.b64encode(m2).decode(),
            "rememberMe": True,
            "trustTokens": [self.session_data["trust_token"]]
            if self.session_data.get("trust_token")
            else [],
        }
        resp = self._post_json(
            f"{self.cfg['AUTH_ENDPOINT']}/signin/complete",
            data,
            headers,
            params={"isRememberMeEnabled": "true"},
        )
        if resp.status_code == 409:
            print("[*] 返回 409：账户要求双重认证（正常，稍后输入验证码）")
        elif resp.status_code == 412:
            resp = self._post_json(
                f"{self.cfg['AUTH_ENDPOINT']}/repair/complete", {}, headers
            )
        elif resp.status_code >= 400:
            detail = resp.text[:200] if resp.text else "(无响应体)"
            hint = ""
            if "-20101" in detail:
                hint = (
                    "\n    >>> -20101 = 账户名或密码不匹配。按顺序排查：\n"
                    "        1) 用户名必须是账户【主要 Apple ID】，别名邮箱可能被拒\n"
                    "           （iPhone: 设置 -> Apple ID -> 登录与安全 -> 姓名、电话号码、电子邮件）\n"
                    "        2) 密码要用 Apple ID 账户主密码，不是 App 专用密码\n"
                    "        3) 若近期多次失败/改过密码，账户可能处于 24-72 小时安全硬化期，需等待后再试\n"
                    "        4) 若主密码在浏览器能登录但这里仍失败，可能该账户已被苹果迁移到新认证体系"
                )
            raise PocError(
                f"HTTP {resp.status_code}（账户信息校验未通过）{hint}\n"
                f"    苹果服务器返回: {detail}"
            )
        else:
            print("[+] SRP 登录成功（该账户可能凭 trust token 免 2FA）")

    def _do_2fa(self, prefer_sms: bool = False):
        """2026 新流程：PUT 触发受信设备推送验证码 -> POST 验证 -> GET trust。
        没有苹果设备时（如已换安卓）用 prefer_sms 直接走短信通道。"""
        if prefer_sms:
            print("[*] 走短信验证码通道（--sms）...")
            if self._2fa_via_sms():
                return
            raise PocError("短信验证码通道失败")

        print("[*] 触发受信设备验证码推送（PUT /verify/trusteddevice/securitycode）...")
        self.http.put(
            f"{self.cfg['AUTH_ENDPOINT']}/verify/trusteddevice/securitycode",
            headers=self.auth_headers({"Accept": "application/json"}),
            timeout=45,
        )
        print("    >>> 若手上有苹果设备，会弹出 6 位验证码 <<<")
        print("    >>> 没有苹果设备：直接按回车，改用短信验证码 <<<")
        for attempt in range(3):
            code = getpass.getpass("    输入验证码（留空=短信通道）: ").strip()
            if code:
                ok = self._verify_2fa_code(code)
                if ok:
                    return
                print("[!] 验证码错误，请重试")
            else:
                if self._2fa_via_sms():
                    return
                raise PocError("短信验证码通道不可用")
        raise PocError("连续 3 次验证码验证失败")

    def _verify_2fa_code(self, code: str) -> bool:
        resp = self._post_json(
            f"{self.cfg['AUTH_ENDPOINT']}/verify/trusteddevice/securitycode",
            {"securityCode": {"code": code}},
            self.auth_headers({"Accept": "application/json"}),
        )
        self._capture_headers(resp)
        self._save_state()
        if resp.status_code in (400, 401, 409):
            return False
        print("[+] 验证码验证成功，信任本会话（GET /2sv/trust）...")
        resp = self.http.get(
            f"{self.cfg['AUTH_ENDPOINT']}/2sv/trust",
            headers=self.auth_headers(),
            timeout=45,
        )
        self._capture_headers(resp)
        self._save_state()
        return resp.status_code < 400

    def _2fa_via_sms(self) -> bool:
        """短信验证码通道（无苹果设备时的主用方式）。"""
        print("[*] 读取可信手机号列表...")
        resp = self.http.get(
            self.cfg["AUTH_ENDPOINT"],
            headers=self.auth_headers(
                {
                    "X-Apple-OAuth-Redirect-URI": self.cfg["HOME_ENDPOINT"],
                    "Accept": "text/html",
                }
            ),
            timeout=45,
        )
        phones = self._parse_trusted_phones(resp.text)
        if not phones:
            print("[!] 未能解析到可信手机号（页面结构可能已变更）")
            return False

        if len(phones) == 1:
            phone_id, number = phones[0]
            print(f"[+] 使用可信手机号：{number}")
        else:
            print("    可信手机号：")
            for idx, (_pid, number) in enumerate(phones):
                print(f"      {idx + 1}. {number}")
            pick = input("    选择序号: ").strip() or "1"
            phone_id, number = phones[int(pick) - 1]

        print(f"[*] 请求向 {number} 发送短信验证码...")
        send = self.http.put(
            f"{self.cfg['AUTH_ENDPOINT']}/verify/phone",
            json={"phoneNumber": {"id": phone_id}, "mode": "sms"},
            headers=self.auth_headers(
                {"Content-type": "application/json; charset=utf-8"}
            ),
            timeout=45,
        )
        self._capture_headers(send)
        self._save_state()
        if send.status_code >= 400:
            print(f"[!] 短信发送失败：HTTP {send.status_code} {send.text[:160]}")
            return False
        print("    >>> 短信已发送，请查收 6 位验证码 <<<")

        for attempt in range(3):
            code = getpass.getpass("    输入短信验证码: ").strip()
            if not code:
                continue
            resp = self._post_json(
                f"{self.cfg['AUTH_ENDPOINT']}/verify/phone/securitycode",
                {
                    "phoneNumber": {"id": phone_id},
                    "securityCode": {"code": code},
                    "mode": "sms",
                },
                self.auth_headers(
                    {
                        "Content-type": "application/json; charset=utf-8",
                        "Accept": "application/json; charset=utf-8",
                    }
                ),
            )
            if resp.status_code >= 400:
                print(f"[!] 验证码校验失败（HTTP {resp.status_code}），请重试")
                continue
            print("[+] 短信验证码校验成功，信任本会话（GET /2sv/trust）...")
            resp = self.http.get(
                f"{self.cfg['AUTH_ENDPOINT']}/2sv/trust",
                headers=self.auth_headers(),
                timeout=45,
            )
            self._capture_headers(resp)
            self._save_state()
            return resp.status_code < 400
        return False

    @staticmethod
    def _parse_trusted_phones(html_text: str):
        """从登录页 boot_args JSON 中解析可信手机号（含 2026 新字段路径）。"""
        from html.parser import HTMLParser

        class BootArgsParser(HTMLParser):
            def __init__(self):
                super().__init__()
                self.capture = False
                self.payload = ""

            def handle_starttag(self, tag, attrs):
                if tag != "script":
                    return
                attrs = dict(attrs)
                if attrs.get("class") == "boot_args" and attrs.get(
                    "type"
                ) == "application/json":
                    self.capture = True

            def handle_endtag(self, tag):
                if tag == "script":
                    self.capture = False

            def handle_data(self, data):
                if self.capture:
                    self.payload += data

        parser = BootArgsParser()
        try:
            parser.feed(html_text)
        except Exception:
            return []
        if not parser.payload:
            return []
        try:
            boot = json.loads(parser.payload)
        except json.JSONDecodeError:
            return []

        two_sv = boot.get("direct", {}).get("twoSV", {})
        numbers = two_sv.get("phoneNumberVerification", {}).get(
            "trustedPhoneNumbers", []
        )
        if not numbers:  # Apple 2026+ 移动到 bridgeInitiateData
            numbers = (
                two_sv.get("bridgeInitiateData", {})
                .get("phoneNumberVerification", {})
                .get("trustedPhoneNumbers", [])
            )
        result = []
        for item in numbers:
            if item.get("id") is not None and item.get("obfuscatedNumber"):
                result.append(
                    (item["id"], item["obfuscatedNumber"].replace("\u2022", "*"))
                )
        return result

    def account_login(self):
        """用会话令牌登录 iCloud（获取 dsInfo / webservices）。"""
        payload = {
            "accountCountryCode": self.session_data.get("account_country"),
            "dsWebAuthToken": self.session_data.get("session_token"),
            "extended_login": True,
            "trustToken": self.session_data.get("trust_token", ""),
        }
        if not payload["dsWebAuthToken"]:
            raise PocError("缺少会话令牌，需要先完成登录")
        resp = self._post_json(
            f"{self.cfg['SETUP_ENDPOINT']}/accountLogin", payload,
            {"Origin": self.cfg["HOME_ENDPOINT"], "Referer": f"{self.cfg['HOME_ENDPOINT']}/",
             "Content-Type": "application/json", "Accept": "application/json"},
        )
        if resp.status_code >= 400:
            raise PocError(f"accountLogin 失败：HTTP {resp.status_code}")
        body = resp.json()
        if body.get("domainToUse"):
            raise PocError(
                f"Apple 要求使用 {body['domainToUse']} 域（请用 --domain "
                f"{'cn' if body['domainToUse'].endswith('cn') else 'com'} 重试）"
            )
        if not body.get("dsInfo"):
            raise PocError("accountLogin 响应缺少 dsInfo（会话可能未受信任，需先完成 2FA）")
        self.data = body

    def requires_2fa(self) -> bool:
        ds = self.data.get("dsInfo", {})
        return (
            ds.get("hsaVersion", 0) == 2
            and (
                self.data.get("hsaChallengeRequired", False)
                or not self.data.get("hsaTrustedBrowser", False)
            )
            and ds.get("hasICloudQualifyingDevice", False)
        )

    # ------------------------------------------------------------- 照片服务

    def photos_endpoint(self) -> str:
        ws = self.data.get("webservices", {})
        ckd = ws.get("ckdatabasews")
        if not ckd:
            raise PocError(
                "照片服务不可用：请确认 iCloud 已开启网页访问，且账户未开高级数据保护"
            )
        return ckd["url"] + "/database/1/com.apple.photos.cloud/production/private"

    def _ck_query(self, payload: dict) -> dict:
        url = f"{self.photos_endpoint()}/records/query?{urlencode(self.params)}"
        resp = self.http.post(
            url, data=json.dumps(payload),
            headers={"Content-type": "text/plain"}, timeout=60,
        )
        self._capture_headers(resp)
        if resp.status_code >= 400:
            raise PocError(f"照片查询失败：HTTP {resp.status_code} {resp.text[:200]}")
        return resp.json()

    # ------------------------------------------------------------- 云盘服务

    def drive_endpoint(self) -> str:
        ws = self.data.get("webservices", {})
        drive = ws.get("drivews")
        if not drive:
            raise PocError(
                "云盘服务不可用：请确认已在 iCloud 设置中开启「iCloud 云盘」"
            )
        return drive["url"]

    def drive_list(self, drivewsid: str = "FOLDER::com.apple.CloudDocs::root") -> list:
        """列出云盘某目录内容（现代 drivews 接口）。"""
        url = f"{self.drive_endpoint()}/retrieveItemDetailsInFolders?{urlencode(self.params)}"
        payload = [{"drivewsid": drivewsid, "partialData": False}]
        resp = self.http.post(
            url, data=json.dumps(payload),
            headers={"Content-type": "text/plain"}, timeout=60,
        )
        self._capture_headers(resp)
        if resp.status_code >= 400:
            raise PocError(
                f"云盘目录读取失败：HTTP {resp.status_code} {resp.text[:300]}"
            )
        body = resp.json()
        # 现代接口返回目录树数组：[{drivewsid, docwsid, name, type, items: [...]}]
        # 某些版本返回 {"items": [...]}，两种都兼容
        raw_items: list = []
        if isinstance(body, list):
            for node in body:
                if not isinstance(node, dict):
                    continue
                children = node.get("items")
                if isinstance(children, list) and node.get("name"):
                    raw_items.extend(children)
                elif isinstance(children, list):
                    raw_items.extend(children)
                else:
                    raw_items.append(node)
        elif isinstance(body, dict):
            if body.get("errorCode"):
                raise PocError(
                    f"云盘目录读取失败：{body.get('errorReason') or body.get('errorCode')}"
                )
            raw_items = body.get("items", []) or []
        else:
            raise PocError(f"云盘响应格式异常：{str(body)[:200]}")

        items = []
        for item in raw_items:
            items.append(
                {
                    "name": item.get("name"),
                    "type": item.get("type"),              # FOLDER / FILE / APP_LIBRARY
                    "size": item.get("size"),
                    "modified": item.get("dateModified"),
                    "docwsid": item.get("docwsid"),
                    "drivewsid": item.get("drivewsid"),
                    "zone": item.get("zoneName") or "com.apple.CloudDocs",
                    "count": item.get("numberOfItems"),
                    "children": item.get("items", []),
                }
            )
        if not items:
            print(f"[*] 云盘响应为空，原始内容：{json.dumps(body, ensure_ascii=False)[:400]}")
        return items

    def drive_download(self, docwsid: str, zone: str = "com.apple.CloudDocs"):
        """获取云盘文件的下载地址（先换 token，再取内容）。"""
        url = f"{self.drive_endpoint()}/download/by_id?{urlencode(self.params)}"
        payload = {"document_id": docwsid, "zoneName": zone}
        resp = self.http.post(
            url, data=json.dumps(payload),
            headers={"Content-type": "text/plain"}, timeout=60,
        )
        self._capture_headers(resp)
        if resp.status_code >= 400:
            raise PocError(
                f"获取下载地址失败：HTTP {resp.status_code} {resp.text[:300]}"
            )
        body = resp.json()
        token = body.get("data_token") or {}
        download_url = token.get("url")
        if not download_url:
            raise PocError(f"响应中没有下载地址：{json.dumps(body)[:300]}")
        return download_url

    # ------------------------------------------------------------- 照片查询

    def check_indexing_state(self):
        """查询照片库索引状态（对齐 PhotoLibrary.__init__ 的 CheckIndexingState）。"""
        try:
            body = self._ck_query(
                {"query": {"recordType": "CheckIndexingState"},
                 "zoneID": {"zoneName": "PrimarySync"}}
            )
            return body["records"][0]["fields"]["state"]["value"]
        except (KeyError, IndexError, PocError):
            return None

    def list_albums(self) -> list:
        """列出用户自建相册（智能相册走固定列表，此 PoC 仅打印用户相册）。"""
        body = self._ck_query(
            {"query": {"recordType": "CPLAlbumByPositionLive"},
             "zoneID": {"zoneName": "PrimarySync"}}
        )
        albums = []
        for rec in body.get("records", []):
            fields = rec.get("fields", {})
            if fields.get("isDeleted", {}).get("value"):
                continue
            if rec["recordName"] in ("----Root-Folder----", "----Project-Root-Folder----"):
                continue
            name_enc = fields.get("albumNameEnc", {}).get("value", "")
            try:
                name = base64.b64decode(name_enc).decode("utf-8")
            except Exception:
                name = rec["recordName"]
            albums.append({"recordName": rec["recordName"], "name": name})
        return albums

    def latest_photos(self, count: int = 100) -> list:
        """按时间升序拉一页资产记录（CPLAsset + CPLMaster 成对）。
        请求体严格对齐 pyicloud_ipd PhotoAlbum._list_query_gen。"""
        params = dict(self.params)
        params.update({"remapEnums": "true", "getCurrentSyncToken": "true"})
        url = f"{self.photos_endpoint()}/records/query?{urlencode(params)}"
        payload = {
            "query": {
                "filterBy": [
                    {"fieldName": "startRank",
                     "fieldValue": {"type": "INT64", "value": 0},
                     "comparator": "EQUALS"},
                    {"fieldName": "direction",
                     "fieldValue": {"type": "STRING", "value": "ASCENDING"},
                     "comparator": "EQUALS"},
                ],
                "recordType": "CPLAssetAndMasterByAssetDateWithoutHiddenOrDeleted",
            },
            "resultsLimit": count * 2,
            "desiredKeys": PHOTO_DESIRED_KEYS,
            "zoneID": {"zoneName": "PrimarySync"},
        }
        resp = self.http.post(
            url, data=json.dumps(payload),
            headers={"Content-type": "text/plain"}, timeout=60,
        )
        self._capture_headers(resp)
        if resp.status_code >= 400:
            raise PocError(
                f"照片查询失败：HTTP {resp.status_code} {resp.text[:300]}"
            )
        body = resp.json()
        assets, masters = {}, []
        for rec in body.get("records", []):
            if rec.get("recordType") == "CPLAsset":
                master_id = rec["fields"]["masterRef"]["value"]["recordName"]
                assets[master_id] = rec
            elif rec.get("recordType") == "CPLMaster":
                masters.append(rec)
        pairs = []
        for m in masters:
            a = assets.get(m["recordName"])
            if a:
                pairs.append((m, a))
        return pairs  # 升序，最后一对即最新

    @staticmethod
    def asset_download_info(master: dict, asset: dict, want: str = "original"):
        """从 asset/master 记录字段中提取指定分辨率的下载信息。"""
        prefix = PHOTO_VERSION_LOOKUP[want]
        fields = None
        if f"{prefix}Res" in asset["fields"]:
            fields = asset["fields"]
        if not fields and f"{prefix}Res" in master["fields"]:
            fields = master["fields"]
        if not fields:
            return None
        res = fields[f"{prefix}Res"]["value"]
        filename_enc = master["fields"].get("filenameEnc", {}).get("value", "")
        try:
            filename = base64.b64decode(filename_enc).decode("utf-8")
        except Exception:
            filename = "asset"
        return {
            "filename": filename,
            "url": res["downloadURL"],
            "size": res["size"],
            "checksum": res["fileChecksum"],
            "file_type": fields.get(f"{prefix}FileType", {}).get("value", ""),
        }


# ---------------------------------------------------------------------------
# 命令行入口
# ---------------------------------------------------------------------------

def reset_session():
    if DATA_DIR.exists():
        for f in DATA_DIR.iterdir():
            f.unlink()
        print(f"[+] 已清除会话数据：{DATA_DIR}")
    else:
        print("[*] 没有已保存的会话")


def main():
    parser = argparse.ArgumentParser(
        description="云桥 iCloudBridge M0 协议 PoC（详见文件头说明）"
    )
    parser.add_argument("--domain", choices=["com", "cn"], default="com",
                        help="Apple ID 区域：com=国际（默认），cn=国区云上贵州")
    parser.add_argument("--check", action="store_true",
                        help="仅验证已保存会话（持续登录验证），不输入密码")
    parser.add_argument("--sms", action="store_true",
                        help="2FA 直接走短信验证码（没有苹果设备时使用）")
    parser.add_argument("--download", action="store_true",
                        help="下载最新一张照片的原图")
    parser.add_argument("--drive", action="store_true",
                        help="验证云盘协议：列出 iCloud 云盘根目录")
    parser.add_argument("--drive-download", default=None, metavar="文件名",
                        help="配合 --drive：下载根目录下的指定文件")
    parser.add_argument("--reset", action="store_true", help="清除本地会话")
    parser.add_argument("--apple-id", default=None, help="Apple ID（留空则交互输入）")
    args = parser.parse_args()

    if args.reset:
        reset_session()
        return

    apple_id = args.apple_id or input("Apple ID（邮箱）: ").strip()
    if not apple_id:
        print("[!] 未输入 Apple ID，退出")
        sys.exit(1)

    # 密码惰性获取：会话仍有效时不会提示输入密码
    password = None
    env_pw = os.environ.get("ICLOUD_APP_PASSWORD")
    if env_pw:
        print("[*] 已从环境变量 ICLOUD_APP_PASSWORD 读取密码")
        password = env_pw.strip()

    api = ICloudPoc(apple_id, password, args.domain)

    try:
        api.login(interactive_2fa=not args.check, prefer_sms=args.sms)
    except PocError as exc:
        print(f"[!] 登录失败：{exc}")
        sys.exit(2)

    # ---- 登录成功，打印账户信息 ------------------------------------------
    ds = api.data.get("dsInfo", {})
    print()
    print("=" * 64)
    print(f"[+] 登录成功：{ds.get('fullName', '(未提供姓名)')} ({apple_id})")
    print(f"    区域: {'国区 icloud.com.cn' if args.domain == 'cn' else '国际 icloud.com'}")
    print(f"    dsid: {ds.get('dsid')}")
    ws = api.data.get("webservices", {})
    enabled = [k for k, v in ws.items() if v.get("status") == "active"]
    print(f"    可用服务({len(enabled)}): {', '.join(sorted(enabled)[:12])}")
    print("=" * 64)

    # ---- 云盘模式（--drive）-----------------------------------------------
    if args.drive:
        print()
        print("[4/6] 读取云盘根目录（drivews / retrieveItemDetailsInFolders）...")
        try:
            items = api.drive_list()
        except PocError as exc:
            print(f"[!] {exc}")
            print("[*] 若提示服务不可用：iPhone → 设置 → Apple ID → iCloud → iCloud 云盘 需为开启")
            sys.exit(3)
        folders = [i for i in items if i["type"] == "FOLDER"]
        files = [i for i in items if i["type"] != "FOLDER"]
        print(f"[+] 根目录共 {len(items)} 项（{len(folders)} 个文件夹 / {len(files)} 个文件）")
        for it in items[:30]:
            kind = it["type"]
            if kind == "FOLDER":
                count = it["count"]
                suffix = f"（{count} 项）" if count is not None else ""
                print(f"    [目录] {it['name']}  {suffix}")
            elif kind == "APP_LIBRARY":
                print(f"    [应用] {it['name']}")
            else:
                try:
                    size = int(it["size"] or 0) / 1024 / 1024
                    size_text = f"{size:.2f} MB"
                except (TypeError, ValueError):
                    size_text = "大小未知"
                print(f"    [文件] {it['name']}  {size_text}  {it['modified'] or ''}")
        if len(items) > 30:
            print(f"    ... 其余 {len(items) - 30} 项略")

        if args.drive_download:
            target = next(
                (i for i in items if i["name"] == args.drive_download), None
            )
            if not target:
                print(f"[!] 根目录下没有名为 {args.drive_download} 的文件")
                sys.exit(4)
            print()
            print(f"[5/6] 获取下载地址：{target['name']} ...")
            try:
                url = api.drive_download(target["docwsid"], target.get("zone") or "com.apple.CloudDocs")
            except PocError as exc:
                print(f"[!] {exc}")
                sys.exit(5)
            print(f"    下载地址: {url[:96]}…")
            DOWNLOAD_DIR.mkdir(exist_ok=True)
            dest = DOWNLOAD_DIR / target["name"]
            print("[6/6] 下载中...")
            done = 0
            with api.http.get(url, stream=True, timeout=600) as r:
                r.raise_for_status()
                with open(dest, "wb") as f:
                    for chunk in r.iter_content(1024 * 256):
                        f.write(chunk)
                        done += len(chunk)
            print(f"[+] 已下载: {dest}（{done / 1024 / 1024:.1f} MB）")
            print("[+] 云盘协议验证通过：目录读取 + 文件下载")
        return

    # ---- 相册列表 ----------------------------------------------------------
    print()
    print("[4/6] 查询相册（recordType: CPLAlbumByPositionLive）...")
    albums = api.list_albums()
    print(f"[+] 共 {len(albums)} 个用户相册")
    for a in albums[:20]:
        print(f"    - {a['name']}  ({a['recordName'][:18]}…)")
    if len(albums) > 20:
        print(f"    ... 其余 {len(albums) - 20} 个略")

    # ---- 最新照片 ----------------------------------------------------------
    print()
    print("[5/6] 查询最新照片（CPLAssetAndMasterByAssetDate...）...")
    state = api.check_indexing_state()
    if state and state != "FINISHED":
        print(f"[!] 照片库索引状态为 {state}（尚未完成，返回结果可能不完整）")
    pairs = api.latest_photos(100)
    if not pairs:
        print("[!] 未查询到照片（新账户或库为空）")
        return
    master, asset = pairs[-1]
    info = api.asset_download_info(master, asset, "original")
    if not info:
        info = api.asset_download_info(master, asset, "medium")
    print(f"[+] 最新资产: {info['filename']}  类型: {info['file_type']}")
    print(f"    大小: {info['size'] / 1024 / 1024:.1f} MB")
    print(f"    校验和: {info['checksum'][:32]}…")
    print(f"    下载 URL: {info['url'][:96]}…")

    # ---- 可选下载 ----------------------------------------------------------
    if args.download:
        print()
        print("[6/6] 下载原图...")
        DOWNLOAD_DIR.mkdir(exist_ok=True)
        dest = DOWNLOAD_DIR / info["filename"]
        with api.http.get(info["url"], stream=True, timeout=300) as r:
            r.raise_for_status()
            done = 0
            with open(dest, "wb") as f:
                for chunk in r.iter_content(1024 * 256):
                    f.write(chunk)
                    done += len(chunk)
        print(f"[+] 已下载: {dest}  ({done / 1024 / 1024:.1f} MB)")
        print("[+] M0 PoC 全链路验证通过：登录 / 2FA / 会话 / 相册 / 资产下载")
    else:
        print()
        print("[+] M0 核心链路验证通过（登录 / 2FA / 会话 / 相册 / 资产元数据）")
        print("[*] 再次运行本脚本可验证会话复用（持续登录）；--download 可验证原图下载")


if __name__ == "__main__":
    main()
