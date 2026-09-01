#!/usr/bin/env python3
"""Generate golden vectors for the Kotlin Companion-protocol port.

Everything here is produced by the *same* libraries pyatv uses, so a Kotlin
implementation that reproduces these bytes is wire-compatible with pyatv.
"""
import binascii
import hashlib
import json
import plistlib
import sys
from uuid import UUID

# pyatv/__init__.py pulls in aiohttp; load the leaf modules straight from file so no
# pyatv dependencies are needed. These three import nothing but the stdlib.
import importlib.util  # noqa: E402


def _load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


_P = "/tmp/pyatv/pyatv"
opack = _load("opack", f"{_P}/support/opack.py")
_tlv = _load("hap_tlv8", f"{_P}/auth/hap_tlv8.py")
write_tlv, read_tlv, TlvValue = _tlv.write_tlv, _tlv.read_tlv, _tlv.TlvValue
_rti = _load("rti", f"{_P}/protocols/companion/plist_payloads/rti_text_operations.py")
get_rti_clear_text_payload = _rti.get_rti_clear_text_payload
get_rti_input_text_payload = _rti.get_rti_input_text_payload

from srptools import SRPClientSession, SRPContext, constants  # noqa: E402

H = lambda b: binascii.hexlify(b).decode()  # noqa: E731
_s = lambda v: v.decode() if isinstance(v, bytes) else v  # noqa: E731

out = {}

# ---------------------------------------------------------------- OPACK
opack_cases = [
    ("null", None),
    ("true", True),
    ("false", False),
    ("int_0", 0),
    ("int_1", 1),
    ("int_39", 0x27),
    ("int_40", 0x28),
    ("int_255", 0xFF),
    ("int_256", 0x100),
    ("int_65535", 0xFFFF),
    ("int_65536", 0x10000),
    ("int_2p32m1", 0xFFFFFFFF),
    ("int_2p32", 0x100000000),
    ("float_1_5", 1.5),
    ("float_neg", -30.0),
    ("str_empty", ""),
    ("str_short", "test"),
    ("str_32", "a" * 32),
    ("str_33", "a" * 33),
    ("str_300", "b" * 300),
    ("bytes_empty", b""),
    ("bytes_short", b"\x01\x02\x03"),
    ("bytes_32", b"\xaa" * 32),
    ("bytes_33", b"\xaa" * 33),
    ("bytes_300", b"\xbb" * 300),
    ("uuid", UUID("12345678-1234-5678-1234-567812345678")),
    ("list_empty", []),
    ("list_small", [1, 2, 3]),
    ("list_15", list(range(15))),
    ("list_20", list(range(20))),
    ("dict_empty", {}),
    ("dict_small", {"a": 1, "b": 2}),
    ("dict_nested", {"a": {"b": [1, 2, {"c": True}]}}),
    ("dict_dedup", {"one": "aaaaaaaa", "two": "aaaaaaaa", "three": "aaaaaaaa"}),
    ("dict_16", {f"k{i:02d}": i for i in range(16)}),
    # Real Companion messages.
    ("msg_hidc_down", {"_i": "_hidC", "_t": 2, "_c": {"_hBtS": 1, "_hidC": 6}, "_x": 12345}),
    ("msg_hidt", {"_i": "_hidT", "_t": 1,
                  "_c": {"_ns": 123456789, "_tFg": 1, "_cx": 500, "_tPh": 1, "_cy": 500},
                  "_x": 7}),
    ("msg_touchstart", {"_i": "_touchStart", "_t": 2,
                        "_c": {"_height": 1000.0, "_tFl": 0, "_width": 1000.0}, "_x": 1}),
    ("msg_interest", {"_i": "_interest", "_t": 1, "_c": {"_regEvents": ["_iMC"]}, "_x": 2}),
    ("msg_sessionstart", {"_i": "_sessionStart", "_t": 2,
                          "_c": {"_srvT": "com.apple.tvremoteservices", "_sid": 3735928559},
                          "_x": 3}),
    ("msg_launchapp", {"_i": "_launchApp", "_t": 2,
                       "_c": {"_bundleID": "com.netflix.Netflix"}, "_x": 4}),
    ("msg_mcc", {"_i": "_mcc", "_t": 2, "_c": {"_mcc": 7, "_skpS": -10.0}, "_x": 5}),
    ("msg_systeminfo", {
        "_i": "_systemInfo", "_t": 2,
        "_c": {
            "_bf": 0, "_cf": 512, "_clFl": 128, "_i": "6c62fca18a4e",
            "_idsID": b"0123456789abcdef", "_pubID": "AA:BB:CC:DD:EE:FF",
            "_sf": 256, "_sv": "170.18", "model": "iPhone14,3", "name": "Light Phone",
        },
        "_x": 6,
    }),
]
out["opack"] = []
for name, value in opack_cases:
    packed = opack.pack(value)
    out["opack"].append({"name": name, "hex": H(packed)})

# Decode-only vectors (device -> client shapes, incl. UID back-references).
out["opack_decode"] = []
for name, value in [
    ("resp_sessionstart", {"_i": "_sessionStart", "_t": 3, "_c": {"_sid": 2882400001}, "_x": 3}),
    ("resp_applist", {"_i": "FetchLaunchableApplicationsEvent", "_t": 3,
                      "_c": {"com.netflix.Netflix": "Netflix",
                             "com.apple.TVWatchList": "TV",
                             "com.google.ios.youtube": "YouTube"}, "_x": 9}),
    ("event_imc", {"_i": "_iMC", "_t": 1, "_c": {"_mcF": 0x0703}, "_x": 11}),
    ("resp_attention", {"_i": "FetchAttentionState", "_t": 3, "_c": {"state": 3}, "_x": 12}),
    ("resp_error", {"_em": "No request handler", "_t": 3, "_x": 13}),
    ("resp_volume", {"_i": "_mcc", "_t": 3, "_c": {"_vol": 0.375}, "_x": 14}),
]:
    out["opack_decode"].append({"name": name, "hex": H(opack.pack(value)),
                                "json": json.dumps(value, default=str, sort_keys=True)})

# ---------------------------------------------------------------- TLV8
out["tlv8"] = []
for name, d in [
    ("ps_m1", {TlvValue.Method: b"\x00", TlvValue.SeqNo: b"\x01"}),
    ("pv_m1", {TlvValue.SeqNo: b"\x01", TlvValue.PublicKey: bytes(range(32))}),
    ("long_value", {TlvValue.PublicKey: bytes((i * 7) & 0xFF for i in range(384)),
                    TlvValue.Proof: bytes((i * 3) & 0xFF for i in range(64))}),
]:
    enc = write_tlv(d)
    out["tlv8"].append({"name": name, "hex": H(enc),
                        "decoded": {str(int(k)): H(v) for k, v in read_tlv(enc).items()}})

# ---------------------------------------------------------------- HKDF-SHA512
from cryptography.hazmat.primitives import hashes  # noqa: E402
from cryptography.hazmat.primitives.kdf.hkdf import HKDF  # noqa: E402


def hkdf(salt, info, ikm):
    return HKDF(algorithm=hashes.SHA512(), length=32,
                salt=salt.encode(), info=info.encode()).derive(ikm)


ikm = bytes(range(64))
out["hkdf"] = [
    {"salt": s, "info": i, "ikm": H(ikm), "out": H(hkdf(s, i, ikm))}
    for s, i in [
        ("Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info"),
        ("Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info"),
        ("Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info"),
        ("", "ClientEncrypt-main"),
        ("", "ServerEncrypt-main"),
    ]
]

# ---------------------------------------------------------------- ChaCha20-Poly1305
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305  # noqa: E402

key = bytes(range(32))
out["chacha"] = []
for name, nonce, aad, pt in [
    ("pv_msg03", b"\x00" * 4 + b"PV-Msg03", None, b"hello world"),
    ("ps_msg05", b"\x00" * 4 + b"PS-Msg05", None, bytes(range(200))),
    ("counter0_aad", (0).to_bytes(12, "little"), bytes([8, 0, 0, 32]), bytes(range(32))),
    ("counter1_aad", (1).to_bytes(12, "little"), bytes([8, 0, 1, 0]), b"x" * 256),
]:
    ct = ChaCha20Poly1305(key).encrypt(nonce, pt, aad)
    out["chacha"].append({"name": name, "key": H(key), "nonce": H(nonce),
                          "aad": H(aad) if aad else "", "pt": H(pt), "ct": H(ct)})

# ---------------------------------------------------------------- Ed25519 / X25519
# Generated by the same library pyatv signs with, so a Kotlin port that matches these
# is interoperable with what the Apple TV verifies.
from cryptography.hazmat.primitives import serialization  # noqa: E402
from cryptography.hazmat.primitives.asymmetric.ed25519 import (  # noqa: E402
    Ed25519PrivateKey,
)
from cryptography.hazmat.primitives.asymmetric.x25519 import (  # noqa: E402
    X25519PrivateKey,
    X25519PublicKey,
)

RAW_PRIV = dict(encoding=serialization.Encoding.Raw,
                format=serialization.PrivateFormat.Raw,
                encryption_algorithm=serialization.NoEncryption())
RAW_PUB = dict(encoding=serialization.Encoding.Raw,
               format=serialization.PublicFormat.Raw)

out["ed25519"] = []
for seed_hex, msg in [
    # RFC 8032 section 7.1 test vectors 1-3.
    ("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60", b""),
    ("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb", b"\x72"),
    ("c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7", b"\xaf\x82"),
    # Realistic HAP shapes: 32+36+32 byte "device info" blobs.
    ("00" * 31 + "01", bytes(range(100))),
    (H(bytes((i * 29 + 7) & 0xFF for i in range(32))), bytes((i * 5) & 0xFF for i in range(464))),
]:
    sk = Ed25519PrivateKey.from_private_bytes(binascii.unhexlify(seed_hex))
    out["ed25519"].append({
        "seed": seed_hex,
        "public": H(sk.public_key().public_bytes(**RAW_PUB)),
        "msg": H(msg),
        "sig": H(sk.sign(msg)),
    })

out["x25519"] = []
for a_hex, b_hex in [
    ("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a",
     "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb"),
    (H(bytes((i * 3 + 1) & 0xFF for i in range(32))),
     H(bytes((i * 7 + 2) & 0xFF for i in range(32)))),
]:
    a = X25519PrivateKey.from_private_bytes(binascii.unhexlify(a_hex))
    b = X25519PrivateKey.from_private_bytes(binascii.unhexlify(b_hex))
    a_pub = a.public_key().public_bytes(**RAW_PUB)
    b_pub = b.public_key().public_bytes(**RAW_PUB)
    out["x25519"].append({
        "a_priv": a_hex, "a_pub": H(a_pub),
        "b_priv": b_hex, "b_pub": H(b_pub),
        "shared": H(a.exchange(X25519PublicKey.from_public_bytes(b_pub))),
    })

# ---------------------------------------------------------------- SRP (HAP pair-setup)
# Fixed inputs so the whole exchange is reproducible.
auth_private = bytes((i * 11 + 3) & 0xFF for i in range(32))   # SRP 'a'
atv_salt = bytes((i * 13 + 5) & 0xFF for i in range(16))
pin = 1234

ctx = SRPContext("Pair-Setup", str(pin), prime=constants.PRIME_3072,
                 generator=constants.PRIME_3072_GEN, hash_func=hashlib.sha512)
# Derive a deterministic server public B by playing the server side with fixed b.
server_private = int.from_bytes(bytes((i * 17 + 9) & 0xFF for i in range(32)), "big")
password_hash = ctx.get_common_password_hash(atv_salt)
verifier = ctx.get_common_password_verifier(password_hash)
atv_pub = ctx.get_server_public(verifier, server_private)

session = SRPClientSession(ctx, binascii.hexlify(auth_private).decode())
session.process(binascii.hexlify(binascii.unhexlify("%x" % atv_pub if len(
    "%x" % atv_pub) % 2 == 0 else "0%x" % atv_pub)).decode(),
    binascii.hexlify(atv_salt).decode())

out["srp"] = {
    "a": H(auth_private),
    "salt": H(atv_salt),
    "pin": pin,
    "B": session.public if False else "%x" % atv_pub,
    # srptools' hex_from() returns bytes of hex ASCII for byte inputs, str for ints.
    "A": _s(session.public),
    "K": _s(session.key),
    "M1": _s(session.key_proof),
    "M2": _s(session.key_proof_hash),
}

# ---------------------------------------------------------------- RTI plist payloads
sess_uuid = bytes(range(16))
clear = get_rti_clear_text_payload(sess_uuid)
insert = get_rti_input_text_payload(sess_uuid, "hello")
out["rti"] = {
    "session_uuid": H(sess_uuid),
    "clear": H(clear),
    "insert_hello": H(insert),
    "insert_unicode": H(get_rti_input_text_payload(sess_uuid, "café — naïve")),
}

# A representative _tiD archive from the device, so the reader can be tested.
device_ti = plistlib.dumps({
    "$version": 100000,
    "$archiver": "RTIKeyedArchiver",
    "$top": {"sessionUUID": plistlib.UID(1), "documentState": plistlib.UID(2)},
    "$objects": [
        "$null",
        {"NS.uuidbytes": sess_uuid, "$class": plistlib.UID(5)},
        {"docSt": plistlib.UID(3), "$class": plistlib.UID(6)},
        {"contextBeforeInput": plistlib.UID(4), "$class": plistlib.UID(7)},
        "already typed",
        {"$classname": "NSUUID", "$classes": ["NSUUID", "NSObject"]},
        {"$classname": "RTIDocumentState", "$classes": ["RTIDocumentState", "NSObject"]},
        {"$classname": "TIDocumentState", "$classes": ["TIDocumentState", "NSObject"]},
    ],
}, fmt=plistlib.PlistFormat.FMT_BINARY, sort_keys=False)
out["rti"]["device_tid"] = H(device_ti)
out["rti"]["device_tid_expect_text"] = "already typed"

def _find(o, path="root"):
    if isinstance(o, bytes):
        print("BYTES AT", path, file=sys.stderr)
    elif isinstance(o, dict):
        for k, v in o.items():
            _find(v, f"{path}.{k}")
    elif isinstance(o, list):
        for i, v in enumerate(o):
            _find(v, f"{path}[{i}]")


_find(out)
print(json.dumps(out, indent=1))

# ---------------------------------------------------------------- MRP protobuf
# The MRP messages this app parses and sends, serialised by Google's own protobuf via
# pyatv's generated classes. A Kotlin codec that reproduces (for what we send) and reads
# (for what we parse) these bytes is wire-compatible with a real Apple TV.
#
# pyatv's protobuf package imports its own generated siblings by the full dotted path
# `pyatv.protocols.mrp.protobuf.*`, which would drag in pyatv/__init__ (and aiohttp). So
# the parent packages are faked as empty namespace packages pointing at the real source
# dirs, and only the protobuf leaf is imported -- same spirit as the _load() shim above.
import types as _types  # noqa: E402


def _fake_pkg(name, path):
    module = _types.ModuleType(name)
    module.__path__ = [path]
    sys.modules[name] = module


try:
    _mrp_base = f"{_P}"
    _fake_pkg("pyatv", _mrp_base)
    _fake_pkg("pyatv.protocols", f"{_mrp_base}/protocols")
    _fake_pkg("pyatv.protocols.mrp", f"{_mrp_base}/protocols/mrp")
    import importlib as _importlib  # noqa: E402

    pb = _importlib.import_module("pyatv.protocols.mrp.protobuf")

    out["mrp"] = {}

    # A full SET_STATE: now-playing metadata, timing, playback state, and a content item
    # carrying artwork bytes and its MIME type.
    _m = pb.ProtocolMessage()
    _m.type = pb.SET_STATE_MESSAGE
    _ss = pb.extract_inner(_m)
    _npi = _ss.nowPlayingInfo
    _npi.title = "Midnight City"
    _npi.artist = "M83"
    _npi.album = "Hurry Up, We're Dreaming"
    _npi.duration = 240.0
    _npi.elapsedTime = 63.5
    _npi.playbackRate = 1.0
    _ss.playbackState = pb.PlaybackState.Playing
    _q = _ss.playbackQueue
    _q.location = 0
    _item = _q.contentItems.add()
    _item.identifier = "item-1"
    _item.artworkData = bytes(range(16))
    _item.metadata.artworkMIMEType = "image/jpeg"
    _item.metadata.title = "Midnight City"
    out["mrp"]["set_state_full"] = H(_m.SerializeToString())

    # A partial SET_STATE that only flips playback state to Paused.
    _m2 = pb.ProtocolMessage()
    _m2.type = pb.SET_STATE_MESSAGE
    pb.extract_inner(_m2).playbackState = pb.PlaybackState.Paused
    out["mrp"]["set_state_paused"] = H(_m2.SerializeToString())

    # A SET_STATE that stops playback.
    _m2b = pb.ProtocolMessage()
    _m2b.type = pb.SET_STATE_MESSAGE
    pb.extract_inner(_m2b).playbackState = pb.PlaybackState.Stopped
    out["mrp"]["set_state_stopped"] = H(_m2b.SerializeToString())

    # SET_NOW_PLAYING_CLIENT naming the owning app.
    _m3 = pb.ProtocolMessage()
    _m3.type = pb.SET_NOW_PLAYING_CLIENT_MESSAGE
    _cl = pb.extract_inner(_m3).client
    _cl.bundleIdentifier = "com.spotify.client"
    _cl.displayName = "Spotify"
    out["mrp"]["set_now_playing_client"] = H(_m3.SerializeToString())

    # SET_NOW_PLAYING_CLIENT with no bundle -- the item went away.
    _m4 = pb.ProtocolMessage()
    _m4.type = pb.SET_NOW_PLAYING_CLIENT_MESSAGE
    pb.extract_inner(_m4).client.processIdentifier = 0
    out["mrp"]["set_now_playing_client_empty"] = H(_m4.SerializeToString())

    # The three messages the client sends to open a subscription, plus the artwork request,
    # so the Kotlin writer can be checked byte for byte.
    _dev = pb.ProtocolMessage()
    _dev.type = pb.DEVICE_INFO_MESSAGE
    _di = pb.extract_inner(_dev)
    _di.uniqueIdentifier = "AA:BB:CC:DD:EE:01"
    _di.name = "Light Phone"
    _di.localizedModelName = "iPhone"
    _di.applicationBundleIdentifier = "com.apple.TVRemote"
    _di.applicationBundleVersion = "344.28"
    _di.protocolVersion = 1
    _di.lastSupportedMessageType = 108
    _di.supportsSystemPairing = True
    _di.allowsPairing = True
    _di.supportsACL = True
    _di.supportsSharedQueue = True
    _di.supportsExtendedMotion = True
    _di.deviceClass = pb.DeviceClass.iPhone
    _di.logicalDeviceCount = 1
    # Field order in a serialized message is ascending field number regardless of set order,
    # so this matches the Kotlin writer as long as the same fields are present.
    out["mrp"]["device_info_fields"] = H(_dev.SerializeToString())

    _cs = pb.ProtocolMessage()
    _cs.type = pb.SET_CONNECTION_STATE_MESSAGE
    pb.extract_inner(_cs).state = pb.SetConnectionStateMessage.Connected
    # uniqueIdentifier omitted here: it is random in the real message. The test compares only
    # the inner message and type, not the whole envelope, for the ones we send.
    out["mrp"]["set_connection_state_inner"] = H(_cs.SerializeToString())

    _cu = pb.ProtocolMessage()
    _cu.type = pb.CLIENT_UPDATES_CONFIG_MESSAGE
    _cuc = pb.extract_inner(_cu)
    _cuc.artworkUpdates = True
    _cuc.nowPlayingUpdates = True
    _cuc.volumeUpdates = True
    _cuc.keyboardUpdates = False
    _cuc.outputDeviceUpdates = True
    out["mrp"]["client_updates_config_inner"] = H(_cu.SerializeToString())

    _pq = pb.ProtocolMessage()
    _pq.type = pb.PLAYBACK_QUEUE_REQUEST_MESSAGE
    _pqr = pb.extract_inner(_pq)
    _pqr.location = 0
    _pqr.length = 1
    _pqr.artworkWidth = 512.0
    _pqr.artworkHeight = 512.0
    _pqr.returnContentItemAssetsInUserCompletion = True
    out["mrp"]["playback_queue_request_inner"] = H(_pq.SerializeToString())
except Exception as _mrp_err:  # noqa: BLE001
    print("MRP vector generation skipped:", _mrp_err, file=sys.stderr)
    out["mrp"] = {}

# ---------------------------------------------------------------- flat output
# A .properties file rather than JSON: unit tests can read it with java.util.Properties
# and need no JSON dependency, which keeps them runnable under bare kotlinc too.
lines = []
for c in out["opack"]:
    lines.append(f"opack.{c['name']}={c['hex']}")
for c in out["opack_decode"]:
    lines.append(f"opackdec.{c['name']}={c['hex']}")
for c in out["tlv8"]:
    lines.append(f"tlv8.{c['name']}={c['hex']}")
    for tag, val in c["decoded"].items():
        lines.append(f"tlv8dec.{c['name']}.{tag}={val}")
for i, c in enumerate(out["hkdf"]):
    lines.append(f"hkdf.{i}={c['salt']}|{c['info']}|{c['ikm']}|{c['out']}")
for i, c in enumerate(out["chacha"]):
    lines.append(f"chacha.{i}={c['key']}|{c['nonce']}|{c['aad']}|{c['pt']}|{c['ct']}")
for i, c in enumerate(out["ed25519"]):
    lines.append(f"ed25519.{i}={c['seed']}|{c['public']}|{c['msg']}|{c['sig']}")
for i, c in enumerate(out["x25519"]):
    lines.append(f"x25519.{i}={c['a_priv']}|{c['a_pub']}|{c['b_priv']}|{c['b_pub']}|{c['shared']}")
s = out["srp"]
lines.append("srp.a=" + s["a"])
lines.append("srp.salt=" + s["salt"])
lines.append("srp.pin=" + str(s["pin"]))
lines.append("srp.B=" + s["B"])
lines.append("srp.A=" + s["A"])
lines.append("srp.K=" + s["K"])
lines.append("srp.M1=" + s["M1"])
lines.append("srp.M2=" + s["M2"])
for k, v in out["rti"].items():
    lines.append(f"rti.{k}={v}")
lines.append("hkdf.count=%d" % len(out["hkdf"]))
lines.append("chacha.count=%d" % len(out["chacha"]))
lines.append("ed25519.count=%d" % len(out["ed25519"]))
lines.append("x25519.count=%d" % len(out["x25519"]))
lines.append("opack.names=" + ",".join(c["name"] for c in out["opack"]))
lines.append("opackdec.names=" + ",".join(c["name"] for c in out["opack_decode"]))
lines.append("tlv8.names=" + ",".join(c["name"] for c in out["tlv8"]))
for _k, _v in out.get("mrp", {}).items():
    lines.append(f"mrp.{_k}={_v}")
if out.get("mrp"):
    lines.append("mrp.names=" + ",".join(out["mrp"].keys()))


import os  # noqa: E402
dest = os.environ.get("VEC_DEST", "vectors.properties")
with open(dest, "w") as fh:
    fh.write("# Generated by scripts/genvec.py -- do not edit by hand.\n")
    fh.write("\n".join(lines) + "\n")
print("wrote", dest, len(lines), "entries", file=sys.stderr)
