# -*- coding: utf-8 -*-
"""Read, apply and bump the app version.

Format: MAJOR.MINOR.PATCH with each part capped at 9, so the bump sequence is
1.0.0 -> 1.0.1 -> ... -> 1.0.9 -> 1.1.0 -> ... -> 1.9.9 -> 2.0.0.

versionCode is derived as MAJOR*10000 + MINOR*100 + PATCH so it stays strictly
increasing across the same bump rule (1.0.9 -> 10009, 1.1.0 -> 10100).
"""
import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VERSION_FILE = os.path.join(ROOT, "version.txt")
INITIAL = "1.0.0"


def read():
    try:
        with io.open(VERSION_FILE, encoding="utf-8") as f:
            v = f.read().strip()
    except (IOError, OSError):
        v = ""
    if not v:
        v = INITIAL
        write(v)
    return v


def write(version):
    with io.open(VERSION_FILE, "w", encoding="utf-8", newline="\n") as f:
        f.write(version + "\n")


def parts(version):
    bits = version.split(".")
    while len(bits) < 3:
        bits.append("0")
    return [int(b) for b in bits[:3]]


def version_code(version):
    major, minor, patch = parts(version)
    return major * 10000 + minor * 100 + patch


def bump(version):
    major, minor, patch = parts(version)
    patch += 1
    if patch > 9:
        patch = 0
        minor += 1
        if minor > 9:
            minor = 0
            major += 1
    return "%d.%d.%d" % (major, minor, patch)


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "read"
    current = read()
    if cmd == "read":
        print(current)
    elif cmd == "code":
        print(version_code(current))
    elif cmd == "bump":
        nxt = bump(current)
        write(nxt)
        print("%s -> %s" % (current, nxt))
    elif cmd == "set":
        if len(sys.argv) < 3:
            raise SystemExit("usage: version.py set X.Y.Z")
        write(sys.argv[2])
        print(sys.argv[2])
    else:
        raise SystemExit("usage: version.py [read|code|bump|set X.Y.Z]")


if __name__ == "__main__":
    main()
