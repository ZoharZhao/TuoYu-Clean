# -*- coding: utf-8 -*-
"""Create (or reuse) a GitHub release for the current version and upload the APK.

Idempotent: if the release for this tag already exists it is reused, and an
asset with the same name is replaced instead of failing.
"""
import io
import json
import os
import subprocess
import urllib.error
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = "ZoharZhao/TuoYu-Clean"
API = "https://api.github.com"


def token():
    inp = "protocol=https\nhost=github.com\n\n"
    out = subprocess.run(["git", "credential", "fill"], input=inp,
                         capture_output=True, text=True, cwd=ROOT).stdout
    for line in out.splitlines():
        if line.startswith("password="):
            return line.split("=", 1)[1].strip()
    raise SystemExit("no github token in git credentials")


def version():
    with io.open(os.path.join(ROOT, "version.txt"), encoding="utf-8") as f:
        return f.read().strip()


def call(method, url, body=None, raw=None, content_type="application/json"):
    data = json.dumps(body).encode("utf-8") if body is not None else raw
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", "Bearer " + token())
    req.add_header("Accept", "application/vnd.github+json")
    if data:
        req.add_header("Content-Type", content_type)
    try:
        with urllib.request.urlopen(req, timeout=600) as r:
            text = r.read().decode("utf-8")
            return json.loads(text) if text else {}
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")
        raise SystemExit("HTTP %d on %s %s\n%s" % (e.code, method, url, detail))


def note(ver):
    return """## 托予净化 {ver}

### 功能

- **屏幕控制** —— 屏幕常亮、自动变暗，每项独立开关
- **系统去广告** —— 本地 VPN DNS 过滤 + MIUI 推荐开关 + 19 万条完整清单（可开关）
- **GKD 集成** —— 内嵌安装包，一键复制订阅链接
- **三语界面** —— 简体中文 / 繁体中文 / English，支持深色主题
- **桌面快捷方式** —— 长按图标直接切换三项功能

### 前置条件

- 屏幕控制需要 Shizuku（应用内已内嵌，可一键安装）或一次 adb 授权
- 广告拦截需要授予 VPN 权限

### 安装

下载下方 APK 直接安装，首次打开会引导完成授权。

详见 [README](https://github.com/{repo}#readme)。
""".format(ver=ver, repo=REPO)


def main():
    ver = version()
    tag = "v" + ver
    src = os.path.join(ROOT, "dist", "托予净化-%s.apk" % ver)
    if not os.path.isfile(src):
        raise SystemExit("missing apk: " + src)

    # asset names stay ASCII: GitHub mangles non-ASCII names in upload URLs
    asset_name = "TuoYuClean-%s.apk" % ver

    releases = call("GET", "%s/repos/%s/releases" % (API, REPO))
    rel = next((r for r in releases if r.get("tag_name") == tag), None)
    if rel is None:
        print("creating release", tag)
        rel = call("POST", "%s/repos/%s/releases" % (API, REPO), {
            "tag_name": tag,
            "name": tag,
            "body": note(ver),
            "draft": False,
            "prerelease": False,
        })
    else:
        print("reusing release", tag, "(id %d)" % rel["id"])

    for a in rel.get("assets", []):
        if a["name"] == asset_name:
            print("removing old asset", a["name"])
            call("DELETE", "%s/repos/%s/releases/assets/%d" % (API, REPO, a["id"]))

    print("uploading", asset_name, "(%d KiB)" % (os.path.getsize(src) // 1024))
    with io.open(src, "rb") as f:
        data = f.read()
    asset = call("POST",
                 "https://uploads.github.com/repos/%s/releases/%d/assets?name=%s"
                 % (REPO, rel["id"], urllib.parse.quote(asset_name)),
                 raw=data,
                 content_type="application/vnd.android.package-archive")
    print("done:", asset["browser_download_url"])
    print("release:", rel["html_url"])


if __name__ == "__main__":
    main()
