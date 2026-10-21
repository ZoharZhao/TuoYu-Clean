"""Generate a local signing keystore using pure-Python RSA (no openssl needed).

Output:
  keystore/signing.der          - PKCS#8 RSA private key (DER)
  keystore/signing.cert.der     - self-signed X.509 v3 certificate (DER)
Both are consumed by tools/build_apk.py.
"""
import base64
import hashlib
import os
import random
import struct
import time

OUT_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "keystore")

# ---------------------------------------------------------------- DER helpers


def der_len(n):
    if n < 0x80:
        return bytes([n])
    body = n.to_bytes((n.bit_length() + 7) // 8, "big")
    return bytes([0x80 | len(body)]) + body


def tlv(tag, content):
    return bytes([tag]) + der_len(len(content)) + content


def der_int(v):
    if v == 0:
        return tlv(0x02, b"\x00")
    b = v.to_bytes((v.bit_length() + 8) // 8, "big")  # keeps a leading zero when high bit set
    return tlv(0x02, b)


def der_seq(*items):
    return tlv(0x30, b"".join(items))


def der_set(*items):
    return tlv(0x31, b"".join(items))


def der_oid(dotted):
    parts = [int(x) for x in dotted.split(".")]
    out = bytes([parts[0] * 40 + parts[1]])
    for p in parts[2:]:
        chunk = [p & 0x7F]
        p >>= 7
        while p:
            chunk.append(0x80 | (p & 0x7F))
            p >>= 7
        out += bytes(reversed(chunk))
    return tlv(0x06, out)


def der_null():
    return b"\x05\x00"


def der_bitstring(data, unused_bits=0):
    return tlv(0x03, bytes([unused_bits]) + data)


def der_octetstring(data):
    return tlv(0x04, data)


def der_utf8(s):
    return tlv(0x0C, s.encode("utf-8"))


def der_utctime(s):
    return tlv(0x17, (s if isinstance(s, str) else s.strftime("%y%m%d%H%M%SZ")).encode())


def der_explicit(n, data):
    return tlv(0xA0 + n, data)


# ---------------------------------------------------------------- primality

SMALL_PRIMES = [p for p in range(3, 2000) if all(p % d for d in range(2, int(p ** 0.5) + 1))]


def is_probable_prime(n, rounds=32):
    if n < 2:
        return False
    for p in [2] + SMALL_PRIMES:
        if n % p == 0:
            return n == p
    d, s = n - 1, 0
    while d % 2 == 0:
        d //= 2
        s += 1
    rnd = random.SystemRandom()
    for _ in range(rounds):
        a = rnd.randrange(2, n - 1)
        x = pow(a, d, n)
        if x in (1, n - 1):
            continue
        for _ in range(s - 1):
            x = pow(x, 2, n)
            if x == n - 1:
                break
        else:
            return False
    return True


def gen_prime(bits):
    rnd = random.SystemRandom()
    while True:
        cand = rnd.getrandbits(bits) | (1 << (bits - 1)) | (1 << (bits - 2)) | 1
        if is_probable_prime(cand):
            return cand


def egcd(a, b):
    if b == 0:
        return (a, 1, 0)
    g, x, y = egcd(b, a % b)
    return (g, y, x - (a // b) * y)


def modinv(a, m):
    g, x, _ = egcd(a % m, m)
    if g != 1:
        raise ValueError("no inverse")
    return x % m


# ---------------------------------------------------------------- RSA key


def gen_rsa(bits=2048):
    e = 65537
    while True:
        p = gen_prime(bits // 2)
        q = gen_prime(bits // 2)
        if p == q:
            continue
        n = p * q
        if n.bit_length() != bits:
            continue
        phi = (p - 1) * (q - 1)
        if (phi % e) == 0:
            continue
        d = modinv(e, phi)
        break
    dp = d % (p - 1)
    dq = d % (q - 1)
    qinv = modinv(q, p)
    return dict(n=n, e=e, d=d, p=p, q=q, dp=dp, dq=dq, qinv=qinv, bits=bits)


def private_key_pkcs8_der(k):
    # RSAPrivateKey ::= SEQUENCE { version, n, e, d, p, q, dp, dq, qinv }
    rsa_priv = der_seq(
        der_int(0),
        der_int(k["n"]),
        der_int(k["e"]),
        der_int(k["d"]),
        der_int(k["p"]),
        der_int(k["q"]),
        der_int(k["dp"]),
        der_int(k["dq"]),
        der_int(k["qinv"]),
    )
    alg = der_seq(der_oid("1.2.840.113549.1.1.1"), der_null())
    return der_seq(der_int(0), alg, der_octetstring(rsa_priv))


def der_read_len(b, i):
    n = b[i]
    if n < 0x80:
        return n, i + 1
    cnt = n & 0x7F
    return int.from_bytes(b[i + 1:i + 1 + cnt], "big"), i + 1 + cnt


def der_children(b):
    """DER helper: see der.kids / der.content (kept for old call sites)."""
    import sys as _sys
    _sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import der as _der
    if not b:
        return []
    return _der.kids(b)


def load_der():
    import sys as _sys
    _sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import der as _der
    return _der


def _der_items(b):
    """Items of a SEQUENCE given as its content bytes."""
    return load_der().kids(b)


def pkcs8_to_pkcs1(der):
    """Extract the inner RSAPrivateKey from a PKCS#8 PrivateKeyInfo."""
    items = _der_items(load_der().content(der))
    for tag, body in items:
        if tag == 0x04:
            return body
    raise ValueError("no private key octets found")


def cert_public_key(cert_der):
    """(n, e) of the certificate subject's RSA public key."""
    der = load_der()
    tbs = der.content(der.content(cert_der))
    spki = None
    for tag, body in _der_items(tbs):
        if tag == 0x30 and len(_der_items(body)) == 2:
            spki = body
    if spki is None:
        raise ValueError("no SPKI in certificate")
    bit = None
    for tag, body in _der_items(spki):
        if tag == 0x03:
            bit = body
    rsa_items = _der_items(der.content(bit[1:]))
    n = int.from_bytes(rsa_items[0][1], "big")
    e = int.from_bytes(rsa_items[1][1], "big")
    return n, e


def validate_key(k, priv_pkcs8, cert_der):
    """Sanity check: cert carries the same modulus and the key is consistent."""
    n, e = cert_public_key(cert_der)
    assert n == k["n"] and e == k["e"], "cert public key mismatch"
    x = 123456789
    sig = pow(x, k["d"], k["n"])
    assert pow(sig, k["e"], k["n"]) == x, "key is not a valid RSA key"


def public_key_der(k):
    rsa_pub = der_seq(der_int(k["n"]), der_int(k["e"]))
    return der_seq(der_seq(der_oid("1.2.840.113549.1.1.1"), der_null()), der_bitstring(rsa_pub))


def _der():
    import sys as _system
    _system.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import der as _der
    return _der


def sign_sha256(k, data):
    """RSASSA-PKCS1-v1_5 with SHA-256 over `data` (see der.rsa_pkcs1_sha256)."""
    return _der().rsa_pkcs1_sha256(k, data)


def verify_sha256(n, e, signature, data):
    """True if `signature` is a valid RSASSA-PKCS1-v1_5/SHA-256 over `data`."""
    return _der().rsa_pkcs1_verify(n, e, signature, hashlib.sha256(data).digest())


def der_tlv_printable(s):
    return tlv(0x13, s.encode("ascii"))


def self_signed_cert(k, subject_cn, days=10000):
    serial = random.SystemRandom().getrandbits(63) | 1
    name = der_seq(
        der_set(der_seq(der_oid("2.5.4.11"), der_tlv_printable("CaffeinePy"))),
        der_set(der_seq(der_oid("2.5.4.10"), der_tlv_printable("Android"))),
        der_set(der_seq(der_oid("2.5.4.3"), der_utf8(subject_cn))),
        der_set(der_seq(der_oid("2.5.4.6"), der_tlv_printable("CN"))),
    )
    nb = time.strftime("%y%m%d%H%M%SZ", time.gmtime(time.time() - 86400))
    na = time.strftime("%y%m%d%H%M%SZ", time.gmtime(time.time() + days * 86400))
    validity = der_seq(der_utctime(nb), der_utctime(na))
    sig_alg = der_seq(der_oid("1.2.840.113549.1.1.11"), der_null())
    tbs = der_seq(
        der_explicit(0, der_int(2)),  # v3
        der_int(serial),
        sig_alg,
        name,
        validity,
        name,
        public_key_der(k),
    )
    sig = sign_sha256(k, tbs)
    return der_seq(tbs, sig_alg, der_bitstring(sig))


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    priv_path = os.path.join(OUT_DIR, "signing.pem")
    cert_path = os.path.join(OUT_DIR, "signing.cert.pem")
    if os.path.exists(priv_path) and os.path.exists(cert_path):
        print("keystore already exists:", OUT_DIR)
        return
    print("generating 2048-bit RSA key ...")
    k = gen_rsa(2048)
    print("key ready")
    priv = private_key_pkcs8_der(k)
    cert = self_signed_cert(k, "Caffeine")
    validate_key(k, priv, cert)
    # apksigner wants a bare PKCS#1 RSA private key

    def pem(label, data):
        b64 = base64.b64encode(data).decode()
        body = "\r\n".join(b64[i:i + 64] for i in range(0, len(b64), 64))
        return "-----BEGIN %s-----\r\n%s\r\n-----END %s-----\r\n" % (label, body, label)

    with open(priv_path, "w", newline="\n") as f:
        f.write(pem("RSA PRIVATE KEY", pkcs8_to_pkcs1(priv)))
    with open(os.path.join(OUT_DIR, "signing.pkcs8.pem"), "w", newline="\n") as f:
        f.write(pem("PRIVATE KEY", priv))
    with open(cert_path, "w", newline="\n") as f:
        f.write(pem("CERTIFICATE", cert))
    with open(os.path.join(OUT_DIR, "signing.cert.der"), "wb") as f:
        f.write(cert)
    print("wrote", OUT_DIR)


if __name__ == "__main__":
    main()
