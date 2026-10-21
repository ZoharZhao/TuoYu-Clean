"""Compile the app: aapt2 link -> javac -> d8 -> build/unsigned.apk.

Signing is a separate step (tools/sign_apk.py, stock apksigner).

If aapt2 cannot stat directories whose path contains non-ASCII characters it
is fed explicit file paths instead of --dir.
"""
import os
import shutil
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
BUILD = os.path.join(ROOT, "build")
APP = os.path.join(ROOT, "app")

SDK = os.environ.get("ANDROID_HOME") or os.path.join(
    os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")
BUILD_TOOLS = os.path.join(SDK, "build-tools", sorted(os.listdir(os.path.join(SDK, "build-tools")))[-1])
PLATFORM = os.path.join(SDK, "platforms", "android-36")
ANDROID_JAR = os.path.join(PLATFORM, "android.jar")
JAVA_DIR = r"C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot"
if not os.path.isdir(JAVA_DIR):
    JAVA_DIR = os.environ.get("JAVA_HOME", JAVA_DIR)
AAPT2 = os.path.join(BUILD_TOOLS, "aapt2.exe")
D8 = os.path.join(BUILD_TOOLS, "d8.bat")
JAVAC = os.path.join(JAVA_DIR, "bin", "javac.exe")
MIN_SDK = "26"
TARGET_SDK = "36"
def _version():
    """read the version file; aapt2 needs both a name and a monotonic code"""
    import io as _io
    import os as _os
    path = _os.path.join(_os.path.dirname(_os.path.dirname(_os.path.abspath(__file__))),
                         "version.txt")
    try:
        with _io.open(path, encoding="utf-8") as _f:
            v = _f.read().strip()
    except (IOError, OSError):
        v = "1.0.0"
    bits = (v.split(".") + ["0", "0", "0"])[:3]
    major, minor, patch = (int(b) for b in bits)
    return v, major * 10000 + minor * 100 + patch


VERSION_NAME, VERSION_CODE_INT = _version()
VERSION_CODE = str(VERSION_CODE_INT)
APK_NAME = "托予净化-%s-unsigned.apk" % VERSION_NAME

# Shizuku API 13.1.5, fetched from Maven Central into libs/ (no Gradle here):
#   dev.rikka.shizuku:api, :aidl, :shared  + androidx.annotation.annotation
LIB_DIR = os.path.join(ROOT, "libs", "jars")
LIB_JARS = [os.path.join(LIB_DIR, n) for n in (
    "shizuku-api.jar", "shizuku-aidl.jar", "shizuku-shared.jar",
    "shizuku-provider.jar", "annotation.jar")]


def tool_env():
    env = dict(os.environ)
    env["JAVA_HOME"] = JAVA_DIR
    env["PATH"] = os.path.join(JAVA_DIR, "bin") + os.pathsep + env.get("PATH", "")
    env["PYTHONUTF8"] = "1"
    return env


def run(cmd):
    print(">", " ".join(cmd))
    res = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace", env=tool_env())
    if res.returncode != 0:
        print(res.stdout)
        raise SystemExit(res.stderr.strip() or ("command failed: " + cmd[0]))
    return res


def compile_resources():
    files = []
    for dirpath, _, names in os.walk(os.path.join(APP, "res")):
        for n in sorted(names):
            files.append(os.path.join(dirpath, n))
    flat = os.path.join(BUILD, "res.zip")
    run([AAPT2, "compile", "-o", flat] + files)
    gen = os.path.join(BUILD, "gen")
    os.makedirs(gen, exist_ok=True)
    stl = os.path.join(BUILD, "res.apk")
    run([AAPT2, "link", "-o", stl,
         "-I", ANDROID_JAR,
         "--manifest", os.path.join(APP, "AndroidManifest.xml"),
         "-R", flat,
         "--java", gen,
         "--min-sdk-version", MIN_SDK,
         "--target-sdk-version", TARGET_SDK,
         "--version-code", VERSION_CODE, "--version-name", VERSION_NAME,
         "--auto-add-overlay"])
    return stl, gen


def compile_java(gen):
    sources = []
    for base in (os.path.join(APP, "java"), gen):
        for dirpath, _, names in os.walk(base):
            for n in names:
                if n.endswith(".java"):
                    sources.append(os.path.join(dirpath, n))
    classes = os.path.join(BUILD, "classes")
    shutil.rmtree(classes, ignore_errors=True)
    os.makedirs(classes)
    missing = [p for p in LIB_JARS if not os.path.isfile(p)]
    if missing:
        raise SystemExit("missing dependency jars (run tools/fetch_libs.py): " + ", ".join(missing))
    cp = os.pathsep.join([ANDROID_JAR] + LIB_JARS)
    run([JAVAC, "--release", "8", "-Xlint:-options", "-encoding", "UTF-8",
         "-classpath", cp, "-d", classes] + sources)
    return classes


def dex(classes):
    out = os.path.join(BUILD, "dex")
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)
    class_files = []
    for dirpath, _, names in os.walk(classes):
        for n in names:
            if n.endswith(".class"):
                class_files.append(os.path.join(dirpath, n))
    # the dependency jars must be *inputs* (not --classpath), otherwise their
    # classes are missing from the dex: "ClassNotFoundException:
    # rikka.shizuku.ShizukuProvider" when the manifest provider is instantiated
    run([D8, "--release", "--min-api", MIN_SDK, "--lib", ANDROID_JAR,
         "--output", out] + sorted(class_files) + LIB_JARS)
    return os.path.join(out, "classes.dex")


def package(stl, dexfile):
    unsigned = os.path.join(BUILD, "unsigned.apk")
    with zipfile.ZipFile(stl) as src, zipfile.ZipFile(unsigned, "w") as dst:
        for info in src.infolist():
            data = src.read(info.filename)
            compress = zipfile.ZIP_STORED if info.compress_type == zipfile.ZIP_STORED else zipfile.ZIP_DEFLATED
            zi = zipfile.ZipInfo(info.filename, date_time=(2020, 1, 1, 0, 0, 0))
            zi.compress_type = compress
            zi.external_attr = info.external_attr
            dst.writestr(zi, data)
        # assets/ is not part of the aapt2 resource output: it has to be copied
        # in by hand (the bundled Shizuku APK lives there)
        assets = os.path.join(APP, "assets")
        if os.path.isdir(assets):
            for dirpath, _, names in os.walk(assets):
                for n in sorted(names):
                    full = os.path.join(dirpath, n)
                    rel = os.path.relpath(full, APP).replace(os.sep, "/")
                    zi = zipfile.ZipInfo(rel, date_time=(2020, 1, 1, 0, 0, 0))
                    zi.compress_type = zipfile.ZIP_DEFLATED
                    with open(full, "rb") as f:
                        dst.writestr(zi, f.read())
                    print("asset:", rel, os.path.getsize(full) // 1024, "KiB")
        zi = zipfile.ZipInfo("classes.dex", date_time=(2020, 1, 1, 0, 0, 0))
        zi.compress_type = zipfile.ZIP_DEFLATED
        with open(dexfile, "rb") as f:
            dst.writestr(zi, f.read())
    return unsigned


def main():
    os.makedirs(BUILD, exist_ok=True)
    os.makedirs(os.path.join(ROOT, "dist"), exist_ok=True)
    stl, gen = compile_resources()
    classes = compile_java(gen)
    dexfile = dex(classes)
    out = package(stl, dexfile)
    print("unsigned APK:", out, "(%d KiB)" % (os.path.getsize(out) // 1024))


if __name__ == "__main__":
    main()

