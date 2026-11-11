"""云盘下载接口实测探针。

用途：用本机已有的 iCloud 会话，把「获取下载地址」的几种请求形态各打一次，
对比哪种才是苹果现在接受的，并验证返回的 URL 是否真能下到字节。

只在本机运行，输出不含 Cookie / token 明文。
"""

import http.cookiejar as cookielib
import json
import ssl
import sys
import urllib.request
from pathlib import Path
from urllib.parse import urlencode

BASE = Path(__file__).parent / "poc_session"

# 账号不写死在源码里：这是要进版本库的脚本，任何人的 Apple ID 都不该出现在上面。
# 用法：python drive_probe.py you@example.com   （先跑 icloud_poc.py 生成会话）
if len(sys.argv) < 2:
    raise SystemExit("用法：python drive_probe.py <Apple ID>")
SAFE_ID = "".join(ch for ch in sys.argv[1] if ch.isalnum())
SESSION = BASE / f"{SAFE_ID}.session.json"
COOKIES = BASE / f"{SAFE_ID}.cookies.txt"

HOME = "https://www.icloud.com.cn"
SETUP = "https://setup.icloud.com.cn/setup/ws/1"
UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)

jar = cookielib.LWPCookieJar(str(COOKIES))
jar.load(ignore_discard=True, ignore_expires=True)

ctx = ssl.create_default_context()
opener = urllib.request.build_opener(
    urllib.request.HTTPSHandler(context=ctx),
    urllib.request.HTTPCookieProcessor(jar),
)


def cookie_header(host: str) -> str:
    parts = []
    for c in jar:
        dom = c.domain.lstrip(".")
        if host == dom or host.endswith("." + dom):
            parts.append(f"{c.name}={c.value}")
    return "; ".join(parts)


def call(method: str, url: str, body: str | None = None,
         content_type: str = "text/plain", extra: dict | None = None):
    from urllib.parse import urlsplit

    host = urlsplit(url).hostname or ""
    headers = {
        "User-Agent": UA,
        "Accept": "application/json",
        "Origin": HOME,
        "Referer": HOME + "/",
        "Cookie": cookie_header(host),
    }
    if body is not None:
        headers["Content-Type"] = content_type
    if extra:
        headers.update(extra)

    data = body.encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with opener.open(req, timeout=60) as resp:
            return resp.status, resp.read(), dict(resp.headers)
    except urllib.error.HTTPError as e:
        return e.code, e.read(), dict(e.headers)


def main() -> int:
    session = json.loads(SESSION.read_text())
    # 用 /validate 而不是 /accountLogin：accountLogin 对这份会话恒返 421，
    # 但 validate 能正常返回 dsInfo + webservices，足够做下载接口实验
    status, body, _ = call("GET", f"{SETUP}/validate")
    print(f"validate -> HTTP {status}")
    if status >= 400:
        print(body[:400].decode("utf-8", "replace"))
        return 1
    account = json.loads(body)
    if not account.get("dsInfo"):
        print("会话需重新完成 2FA，无法继续")
        return 1
    jar.save(ignore_discard=True, ignore_expires=True)

    dsid = account["dsInfo"]["dsid"]
    ws = account.get("webservices", {})
    print("drivews =", ws.get("drivews", {}).get("url"))
    print("docws   =", ws.get("docws", {}).get("url"))

    params = {
        "clientBuildNumber": "2532Project47",
        "clientMasteringNumber": "2532B20",
        "clientId": session["client_id"],
        "dsid": dsid,
    }

    def norm(u: str) -> str:
        return u.rstrip("/")

    drivews = norm(ws["drivews"]["url"])
    docws = norm(ws["docws"]["url"])

    # ---- 列根目录，找几个文件
    list_url = f"{drivews}/retrieveItemDetailsInFolders?{urlencode(params)}"
    status, body, _ = call(
        "POST", list_url, json.dumps([{"drivewsid": "FOLDER::com.apple.CloudDocs::root",
                                       "partialData": False}])
    )
    print(f"list root -> HTTP {status}")
    if status >= 400:
        print(body[:300].decode("utf-8", "replace"))
        return 1
    tree = json.loads(body)
    nodes = tree if isinstance(tree, list) else tree.get("items", [])
    files = []
    for node in nodes:
        for child in node.get("items", []) or [node]:
            if child.get("type") == "FILE":
                files.append(child)
    files.sort(key=lambda f: f.get("size") or 0)
    print(f"文件数 {len(files)}")
    for f in files[:3]:
        print(f"  - {(f.get('name') or '')}.{f.get('extension') or ''} "
              f"size={f.get('size')} docwsid={'有' if f.get('docwsid') else '无'} "
              f"drivewsid={'有' if f.get('drivewsid') else '无'}")
    if not files:
        print("没有可测文件")
        return 1

    target = files[0]
    doc_id = target.get("docwsid")
    print(f"\n目标文件：{target.get('name')}.{target.get('extension')} "
          f"({target.get('size')} 字节)")

    variants = [
        ("A GET  docws  仅document_id", "GET", docws,
         {"document_id": doc_id}),
        ("B GET  docws  +zoneName", "GET", docws,
         {"document_id": doc_id, "zoneName": "com.apple.CloudDocs"}),
        ("C POST docws  JSON{id,zone}", "POST", docws,
         {"document_id": doc_id, "zoneName": "com.apple.CloudDocs"}),
        ("D POST drivews JSON{id,zone}", "POST", drivews,
         {"document_id": doc_id, "zoneName": "com.apple.CloudDocs"}),
        ("E GET  drivews 仅document_id", "GET", drivews,
         {"document_id": doc_id}),
    ]

    download_url = None
    for label, method, host, q in variants:
        url = f"{host}/ws/com.apple.CloudDocs/download/by_id?{urlencode({**params, **q})}"
        body_text = None
        if method == "POST":
            body_text = json.dumps({"document_id": doc_id,
                                    "zoneName": "com.apple.CloudDocs"})
        status, resp_body, _ = call(method, url, body_text)
        text = resp_body.decode("utf-8", "replace")
        print(f"\n{label}: HTTP {status} len={len(resp_body)}")
        print("   ", text[:220].replace("\n", " "))
        if status == 200 and "url" in text and download_url is None:
            try:
                parsed = json.loads(text)
                token = parsed.get("data_token") or parsed.get("package_token") or {}
                u = token.get("url")
                if isinstance(u, list):
                    u = u[0] if u else None
                if u:
                    download_url = u
                    print("    -> 拿到下载地址（主机 %s）" % u.split("/")[2])
            except Exception as exc:  # noqa: BLE001
                print("    解析失败", exc)

    if not download_url:
        print("\n所有形态都没拿到地址")
        return 1

    print("\n下载内容主机:", download_url.split("/")[2])
    status, data, headers = call("GET", download_url, extra={"Accept": "*/*"})
    print(f"GET 内容 -> HTTP {status} bytes={len(data)} "
          f"type={headers.get('Content-Type')}")
    print("前 16 字节:", data[:16])
    print("期望大小:", target.get("size"))
    return 0 if status == 200 else 1


if __name__ == "__main__":
    sys.exit(main())
