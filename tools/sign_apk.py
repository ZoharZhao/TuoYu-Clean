"""Sign a zipaligned APK with the stock apksigner (v1 + v2 + v3).

The build key is converted to a PKCS#12 store first (KeyConv.java), because
apksigner cannot read PEM private keys in this environment. Requires a JDK
that can run the build-tools jars (JDK 11+).

Usage: python tools/sign_apk.py <input.apk> <output.apk>
"""
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
KEYSTORE = os.path.join(ROOT, "keystore")
SDK = os.environ.get("ANDROID_HOME") or os.path.join(
    os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")
BUILD_TOOLS = os.path.join(SDK, "build-tools", sorted(os.listdir(os.path.join(SDK, "build-tools")))[-1])
JAVA_DIR = r"C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot"
if not os.path.isdir(JAVA_DIR):
    JAVA_DIR = os.environ.get("JAVA_HOME", JAVA_DIR)
JAVA = os.path.join(JAVA_DIR, "bin", "java.exe")
JAVAC = os.path.join(JAVA_DIR, "bin", "javac.exe")
APKSIGNER_JAR = os.path.join(BUILD_TOOLS, "lib", "apksigner.jar")
P12 = os.path.join(KEYSTORE, "caffeine.p12")
PASSWORD = "caffeine"


def run(cmd):
    print(">", " ".join(cmd))
    res = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if res.stdout.strip():
        print(res.stdout.strip())
    if res.returncode != 0:
        raise SystemExit(res.stderr.strip() or "command failed")


def ensure_keystore():
    if os.path.exists(P12) and os.path.exists(os.path.join(KEYSTORE, "signing.pkcs8.pem")):
        return
    raise SystemExit("run tools/make_keystore.py first")


def ensure_p12():
    classes = os.path.join(HERE, "java", "out")
    os.makedirs(classes, exist_ok=True)
    run([JAVAC, "-d", classes, os.path.join(HERE, "java", "KeyConv.java")])
    run([JAVA, "-cp", classes, "KeyConv",
         os.path.join(KEYSTORE, "signing.pkcs8.pem"),
         os.path.join(KEYSTORE, "signing.cert.pem"), P12, PASSWORD])


def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: sign_apk.py <in.apk> <out.apk>")
    ensure_keystore()
    ensure_p12()
    src, dst = sys.argv[1], sys.argv[2]
    run([JAVA, "-jar", APKSIGNER_JAR, "sign", "--ks", P12,
         "--ks-pass", "pass:" + PASSWORD, "--key-pass", "pass:" + PASSWORD,
         "--v1-signing-enabled", "true", "--v2-signing-enabled", "true",
         "--v3-signing-enabled", "true", "--v4-signing-enabled", "false",
         "--out", dst, src])
    run([JAVA, "-jar", APKSIGNER_JAR, "verify", "--verbose", "--print-certs", dst])


if __name__ == "__main__":
    main()
