"""Fetch the Shizuku API jars this project compiles against (no Gradle).

Downloads from Maven Central / Google Maven into libs/, extracts classes.jar
from each AAR and flattens everything into libs/jars/. Idempotent: existing
files are kept, pass --force to re-download.

    python tools/fetch_libs.py [--force]
"""
import os
import shutil
import sys
import urllib.request
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
LIBS = os.path.join(ROOT, "libs")
JARS = os.path.join(LIBS, "jars")

VERSION = "13.1.5"
MAVEN = "https://repo1.maven.org/maven2"
GOOGLE = "https://maven.google.com"

AARS = [
    ("%s/dev/rikka/shizuku/api/%s/api-%s.aar" % (MAVEN, VERSION, VERSION), "api-%s.aar" % VERSION),
    ("%s/dev/rikka/shizuku/aidl/%s/aidl-%s.aar" % (MAVEN, VERSION, VERSION), "aidl-%s.aar" % VERSION),
    ("%s/dev/rikka/shizuku/shared/%s/shared-%s.aar" % (MAVEN, VERSION, VERSION), "shared-%s.aar" % VERSION),
    ("%s/dev/rikka/shizuku/provider/%s/provider-%s.aar" % (MAVEN, VERSION, VERSION), "provider-%s.aar" % VERSION),
]
PLAIN_JARS = [
    ("%s/androidx/annotation/annotation/1.3.0/annotation-1.3.0.jar" % GOOGLE, "annotation-1.3.0.jar"),
]
# name in libs/jars/ <- name in libs/
FLAT = {
    "shizuku-api.jar": "api-%s.aar" % VERSION,
    "shizuku-aidl.jar": "aidl-%s.aar" % VERSION,
    "shizuku-shared.jar": "shared-%s.aar" % VERSION,
    "shizuku-provider.jar": "provider-%s.aar" % VERSION,
    "annotation.jar": "annotation-1.3.0.jar",
}


def download(url, dest, force):
    if os.path.isfile(dest) and not force:
        print("keep  ", os.path.basename(dest))
        return
    print("fetch ", url)
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    with urllib.request.urlopen(url, timeout=60) as r, open(dest, "wb") as f:
        f.write(r.read())


def main():
    force = "--force" in sys.argv[1:]
    os.makedirs(JARS, exist_ok=True)
    for url, name in AARS + PLAIN_JARS:
        download(url, os.path.join(LIBS, name), force)
    for target, source in FLAT.items():
        src = os.path.join(LIBS, source)
        dst = os.path.join(JARS, target)
        if src.endswith(".jar"):
            if not os.path.isfile(dst) or force:
                shutil.copyfile(src, dst)
                print("flat  ", target)
            continue
        if os.path.isfile(dst) and not force:
            print("keep  ", target)
            continue
        with zipfile.ZipFile(src) as z:
            data = z.read("classes.jar")
        with open(dst, "wb") as f:
            f.write(data)
        print("flat  ", target)
    print("done ->", JARS)


if __name__ == "__main__":
    main()
