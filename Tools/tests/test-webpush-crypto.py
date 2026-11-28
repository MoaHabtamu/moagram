#!/usr/bin/env python3
"""Decrypt Telegram-style aesgcm web pushes with the production Java decryptor.

The encryptor below follows draft-ietf-webpush-encryption-04 independently with
Python's `cryptography` package; MoagramWebPushCrypto.java is compiled unchanged
and must recover each plaintext from the relay-framed body.
"""
from pathlib import Path
import base64
import hashlib
import hmac
import os
import struct
import subprocess
import tempfile

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

root = Path(__file__).resolve().parents[2]
crypto_src = root / 'TMessagesProj/src/main/java/org/telegram/messenger/MoagramWebPushCrypto.java'


def b64u(data):
    return base64.urlsafe_b64encode(data).rstrip(b'=').decode()


def hkdf(salt, ikm, info, length):
    prk = hmac.new(salt, ikm, hashlib.sha256).digest()
    return hmac.new(prk, info + b'\x01', hashlib.sha256).digest()[:length]


def raw_pub(key):
    return key.public_key().public_bytes(serialization.Encoding.X962,
                                         serialization.PublicFormat.UncompressedPoint)


def encrypt(ua_pub, auth, plaintext, rs=4096, pad=0, server=None, salt=None):
    server = server or ec.generate_private_key(ec.SECP256R1())
    salt = salt or os.urandom(16)
    ua_key = ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), ua_pub)
    shared = server.exchange(ec.ECDH(), ua_key)
    as_pub = raw_pub(server)
    ikm = hkdf(auth, shared, b'Content-Encoding: auth\x00', 32)
    context = b'P-256\x00' + struct.pack('>H', 65) + ua_pub + struct.pack('>H', 65) + as_pub
    cek = hkdf(salt, ikm, b'Content-Encoding: aesgcm\x00' + context, 16)
    nonce = hkdf(salt, ikm, b'Content-Encoding: nonce\x00' + context, 12)
    data = plaintext
    out = b''
    seq = 0
    first = True
    while first or data:
        first = False
        p = pad if seq == 0 else 0
        chunk_len = rs - 2 - p
        chunk, data = data[:chunk_len], data[chunk_len:]
        record = struct.pack('>H', p) + b'\x00' * p + chunk
        n = bytearray(nonce)
        for i in range(6):
            n[11 - i] ^= (seq >> (8 * i)) & 0xff
        out += AESGCM(cek).encrypt(bytes(n), record, None)
        seq += 1
    enc = f'salt={b64u(salt)}' + (f';rs={rs}' if rs != 4096 else '')
    return enc, f'dh={b64u(as_pub)};p256ecdsa={b64u(os.urandom(65))}', out


def gateway(enc, ck, ciphertext):
    return f'aesgcm\nEncryption: {enc}\nCrypto-Key: {ck}\n'.encode() + ciphertext


client = ec.generate_private_key(ec.SECP256R1())
pkcs8 = client.private_bytes(serialization.Encoding.DER, serialization.PrivateFormat.PKCS8,
                             serialization.NoEncryption())
ua_pub = raw_pub(client)
auth = os.urandom(16)

cases = []
telegram = b'{"p":"' + b64u(os.urandom(300)).encode() + b'"}'
cases.append(('telegram-sized payload', gateway(*encrypt(ua_pub, auth, telegram)), telegram))
cases.append(('padding is stripped', gateway(*encrypt(ua_pub, auth, b'hello', pad=7)), b'hello'))
big = os.urandom(70)
cases.append(('multiple records', gateway(*encrypt(ua_pub, auth, big, rs=25)), big))

enc, ck, ct = encrypt(ua_pub, auth, telegram)
tampered = bytearray(ct)
tampered[5] ^= 1
bad_cases = [
    ('tampered ciphertext', gateway(enc, ck, bytes(tampered))),
    ('wrong auth secret', gateway(*encrypt(ua_pub, os.urandom(16), telegram))),
    ('missing dh', f'aesgcm\nEncryption: {enc}\nCrypto-Key: keyid=1\n'.encode() + ct),
    ('not a gateway body', b'\x00\x01raw aes128gcm body'),
]


def jbytes(data):
    return 'hex("' + data.hex() + '")'


body = []
for name, payload, expected in cases:
    body.append(f'check(Arrays.equals(MoagramWebPushCrypto.decryptGatewayBody({jbytes(payload)}, keys), {jbytes(expected)}), "{name}");')
for name, payload in bad_cases:
    body.append(f'''try {{ MoagramWebPushCrypto.decryptGatewayBody({jbytes(payload)}, keys); throw new AssertionError("{name} must fail"); }}
        catch (java.security.GeneralSecurityException expected) {{ checks++; }}''')

java = f'''
import java.util.Arrays;
public class WebPushCryptoTest {{
    static int checks;
    static void check(boolean value, String message) {{ checks++; if (!value) throw new AssertionError(message); }}
    static byte[] hex(String s) {{
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return out;
    }}
    public static void main(String[] args) throws Exception {{
        MoagramWebPushCrypto.Keys keys = new MoagramWebPushCrypto.Keys({jbytes(pkcs8)}, {jbytes(ua_pub)}, {jbytes(auth)});
        check(keys.isValid(), "python keys load");
        {chr(10).join(body)}

        MoagramWebPushCrypto.Keys fresh = MoagramWebPushCrypto.generateKeys();
        check(fresh.isValid(), "generated keys are well formed");
        byte[] sample = hex("{os.urandom(31).hex()}");
        check(Arrays.equals(MoagramWebPushCrypto.fromBase64Url(MoagramWebPushCrypto.base64Url(sample)), sample), "base64url round trip");
        check(MoagramWebPushCrypto.base64Url({jbytes(ua_pub)}).equals("{b64u(ua_pub)}"), "base64url matches python");
        check(Arrays.equals(MoagramWebPushCrypto.fromBase64Url("{base64.b64encode(auth).decode()}"), {jbytes(auth)}), "standard padded base64 decodes");
        check("abc".equals(MoagramWebPushCrypto.headerParam("keyid=p256dh;dh=abc,p256ecdsa=xyz", "dh")), "dh found among VAPID params");
        check(MoagramWebPushCrypto.headerParam("salt=1", "rs") == null, "absent param is null");
        System.out.println("web push crypto checks: " + checks);
    }}
}}
'''

with tempfile.TemporaryDirectory() as tmp:
    tmp = Path(tmp)
    src = crypto_src.read_text().replace('package org.telegram.messenger;', '')
    (tmp / 'MoagramWebPushCrypto.java').write_text(src)
    (tmp / 'WebPushCryptoTest.java').write_text(java)
    subprocess.run(['javac', '-d', str(tmp), str(tmp / 'MoagramWebPushCrypto.java'), str(tmp / 'WebPushCryptoTest.java')], check=True)
    subprocess.run(['java', '-cp', str(tmp), 'WebPushCryptoTest'], check=True)
