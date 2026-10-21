"""One-command build: unsigned APK -> zipalign -> apksigner (v1+v2+v3).

Usage (from this directory):
    python Makefile.py          # builds dist/Caffeine.apk
    python Makefile.py install  # builds, then installs on the connected phone
"""
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
TOOLS = os.path.join(ROOT, "tools")
BUILD = os.path.join(ROOT, "build")
DIST = os.path.join(ROOT, "dist")
SDK = os.environ.get("ANDROID_HOME") or os.path.join(
    os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")
BUILD_TOOLS = os.path.join(SDK, "build-tools", sorted(os.listdir(os.path.join(SDK, "build-tools")))[-1])
ZIPALIGN = os.path.join(BUILD_TOOLS, "zipalign.exe")
ADB = os.path.join(SDK, "platform-tools", "adb.exe")
PY = sys.executable


def run(cmd):
    print(">", " ".join(cmd))
    res = subprocess.run(cmd)
    if res.returncode != 0:
        raise SystemExit(res.returncode)


def main():
    env_python = os.environ.copy()
    env_python["PYTHONUTF8"] = "1"
    # 0) key material (idempotent)
    run([PY, os.path.join(TOOLS, "make_keystore.py")])
    # 1) aapt2 + javac + d8 -> build/unsigned.apk
    run([PY, os.path.join(TOOLS, "build_unsigned.py")])
    # 2) zipalign -> build/aligned.apk
    aligned = os.path.join(BUILD, "aligned.apk")
    run([ZIPALIGN, "-f", "-p", "4", os.path.join(BUILD, "unsigned.apk"), aligned])
    # 3) sign -> dist/托予净化-<version>.apk + verify
    import subprocess as _sp
    _ver = _sp.run([PY, os.path.join(TOOLS, "version.py"), "read"],
                   capture_output=True, text=True).stdout.strip()
    out = os.path.join(DIST, "托予净化-%s.apk" % _ver)
    run([PY, os.path.join(TOOLS, "sign_apk.py"), aligned, out])
    print("\nbuilt:", out, "(%d KiB)" % (os.path.getsize(out) // 1024))
    # 4) consume the version: the next build gets the next number
    bumped = _sp.run([PY, os.path.join(TOOLS, "version.py"), "bump"],
                     capture_output=True, text=True).stdout.strip()
    print("next version:", bumped.split("->")[-1].strip())
    if "install" in sys.argv[1:]:
        run([ADB, "install", "-r", out])


if __name__ == "__main__":
    main()
