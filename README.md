# LightRemote

An Apple TV remote for the Light Phone III. Launcher label: **Apple TV**.

Speaks Apple's **Companion** protocol directly — mDNS discovery, HAP pairing with the
four-digit code on screen, then an encrypted session. No server, no bridge, no companion
app on another device.

## What it does

- **D-pad** — up/down/left/right, select, menu, home, control centre. Menu and Home
  respond to a hold as well as a tap.
- **Swipe pad** — a trackpad mapped onto the TV's 1000x1000 touch surface, tap to select.
- **Transport** — play/pause, skip back and forward 15s, volume up and down. The playback
  buttons dim when the TV reports it has no media controls to offer.
- **Power** — sleep and wake, with the current state shown in the top bar.
- **Apps** — list everything launchable and open one by name.
- **Type** — send text to the focused search field on the TV.

## What it does not do

No now-playing title or album art. Companion carries control but no metadata; that needs
MRP tunnelled inside an AirPlay 2 session, which is a second protocol stack (binary plists,
an RTSP-ish setup exchange, two more encrypted channels, and MRP's protobuf schema). It is
the obvious next thing to build, not an oversight.

## Getting connected

1. TV and phone on the same Wi-Fi.
2. First run opens **Devices**; the TV appears under **Found**.
3. Tap it, then type the code the TV shows.
4. Once paired it connects straight away.

After that the app opens directly onto the remote for the TV you used last. The chevron in
the top left goes to **Devices** to switch or add one; long-press a paired device to forget
it. Browsing devices does not drop the connection.

Pairing must be enabled on the TV — Settings > AirPlay and HomeKit. A device with pairing
switched off is listed but not tappable.

## How it is built

Plain sideloaded APK, single `app/` module, package `com.gios.lightremote`, Compose +
Material3. Not a Light SDK tool: the SDK sandbox has no raw-socket or mDNS access, so a
protocol client cannot live inside it.

The LightOS design tokens are ported from [`lightphone/light-sdk`][sdk] (MIT) rather than
approximated — 27x31 grid, type scale against a 600px vertical baseline, Akkurat pulled from
`SystemFonts`, no ripples, 45ms haptic on finger-down. The vector icons in
`app/src/main/res/drawable/ic_*_white.xml` are the SDK's own.

### The protocol layer

Everything under `proto/` and `crypto/` is hand-written with no dependencies:

| Piece | Where |
| --- | --- |
| OPACK serialisation, incl. the back-reference table | `proto/Opack.kt` |
| TLV8 | `proto/Tlv8.kt` |
| Binary property lists | `proto/BPlist.kt` |
| NSKeyedArchiver payloads for text input | `proto/RtiPayloads.kt` |
| SRP-6a, 3072-bit, SHA-512 | `crypto/Srp.kt` |
| Ed25519, X25519 | `crypto/Curve25519.kt` |
| ChaCha20-Poly1305 | `crypto/ChaCha20Poly1305.kt` |
| HKDF-SHA512 | `crypto/Digest.kt` |

No BouncyCastle: it would add roughly 8 MB of APK for the dozen operations one pairing
performs. Not the platform providers either — `Ed25519` and `XDH` only exist from API 33,
and their raw-key handling differs between Conscrypt and OpenJDK, which would mean the unit
tests exercise different code than the phone does.

`crypto/Curve25519.kt` uses `BigInteger` and is **not constant time**. Measured cost is
~25ms to sign and ~20ms to verify on a laptop, so a connection spends a fraction of a second
in crypto. The only secrets are an ephemeral X25519 key and the long-term Ed25519 key, both
used against a television on the local network; there is no remote timing oracle here.

### How any of this is verified

There is no Apple TV in CI, and a wrong byte anywhere in this stack shows up as a device
that refuses to pair — not as anything visible on screen. So verification is two-layered,
and both layers gate the build:

1. **Golden vectors.** `scripts/genvec.py` generates
   `app/src/test/resources/vectors.properties` using the *same libraries pyatv uses*:
   pyatv's own `opack.py` and `hap_tlv8.py`, `srptools` for SRP, CPython `plistlib` for the
   text-input payloads, and `cryptography` for HKDF, ChaCha20-Poly1305, Ed25519 and X25519.
   Matching those bytes means being wire-compatible with a client known to work. The
   Ed25519 and X25519 vectors reproduce RFC 8032 §7.1 and RFC 7748 §6.1 exactly, which
   cross-checks the generator itself.

2. **A fake accessory.** `FakeAppleTv` implements the device half of the handshake and
   `HandshakeTest` runs the real client against it over a loopback socket: pair with a PIN,
   drop the connection, reconnect with the saved credentials, send encrypted commands. This
   is what catches the mistakes vectors cannot — which frame type answers which, which HKDF
   label belongs to which direction, when the ChaCha counters start, and the order the
   connect sequence has to run in.

Regenerate the vectors after cloning pyatv to `/tmp/pyatv`:

```
python3 scripts/genvec.py            # writes vectors.properties next to it
VEC_DEST=app/src/test/resources/vectors.properties python3 scripts/genvec.py
```

### Notes from the wire

Things that cost time to work out and are easy to undo by accident:

- **OPACK has no negative integers.** Skip-by-seconds has to go out as a `Double` or it
  cannot be encoded at all. `Opack.pack` throws with that advice rather than emitting
  something the TV will reject.
- **The connect sequence is load-bearing.** `_systemInfo` before anything else or the device
  never pushes status events; `_sessionStart` before any button or presses do nothing;
  `TVRCSessionStart` or newer tvOS leaves power queries unanswered. Older tvOS has no
  handler for that last one, so it is sent tolerantly.
- **A pairing reply arrives as the `_Next` frame**, never as another `_Start`.
- **`srptools` serialises integers minimally** — leading zero bytes dropped — everywhere
  except the two RFC 5054 `PAD()` sites. `Srp.minimalBytes` reproduces that, including the
  sign byte `BigInteger.toByteArray()` prepends.
- **Touch samples need throttling.** Roughly one per 16ms; a full stream of pointer events
  reads as a flick and overshoots by several rows.
- **Text input restarts its session per send.** The `_tiStart` archive is a snapshot, and an
  old session UUID silently targets a field that may be gone.
- **`_idsID` in `_systemInfo` is the *pairing* identifier**, not the client's own device id.
  The device checks it against its paired-controller list, so getting it wrong completes the
  handshake and then fails the first request — which presents as "paired but won't connect".
- **`_x` belongs on pairing frames too**, even though nothing dispatches on it. A real Apple
  TV tolerates its absence; the reference client sends it, so this does too.
- **LazyColumn keys must be unique across the whole list.** Paired and discovered devices are
  updated by different events, so for an instant after pairing one device can be in both —
  and an overlapping key is a crash, not a duplicate row. Hence namespaced keys plus a filter
  at render time.

## Building

Requires JDK 17 and the Android SDK. CI (`.github/workflows/build.yml`) runs the tests,
builds a signed release APK, checks the signing certificate against
`signing-fingerprint.txt`, verifies the package declares a launcher icon, and publishes one
GitHub Release asset per push to `main`.

The keystore is committed at `keystore/lightremote.jks` on purpose: every build has to carry
one stable certificate or Obtainium updates fail with an opaque `Failure: Invalid`.

**Bump `versionName` when pushing.** The release workflow tags `v${versionName}.${run}`, so
the run number keeps tags unique — but keep the base version moving as features land.

## Licence

MIT. The design tokens and vector icons come from [`lightphone/light-sdk`][sdk], also MIT.
The protocol was implemented against [pyatv][pyatv]'s source (MIT) as the reference.

[sdk]: https://github.com/lightphone/light-sdk
[pyatv]: https://github.com/postlund/pyatv
