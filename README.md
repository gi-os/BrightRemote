# LightRemote

An Apple TV remote for the Light Phone III. Launcher label: **Apple TV**.

Speaks Apple's **Companion** protocol directly — mDNS discovery, HAP pairing with the
four-digit code on screen, then an encrypted session. No server, no bridge, no companion
app on another device.

## What it does

Nearly the whole panel is the thing you touch. **Swipe** to move, **tap** to select — the pad
has no border and nothing drawn on it, because a remote you glance at while looking at a
television should not present a dozen targets.

Three buttons sit along the bottom:

- **Back** — tap for back, hold for the menu overlay. tvOS has no separate menu button —
  holding back is how you get the overlay — so this app does not carry one either.
- **Home** — tap for home, hold for the app switcher.
- **More** — tap slides up everything else: play/pause and skip back/forward 15s, then volume
  down, mute and volume up, then the D-pad/trackpad toggle, apps and the keyboard. The
  playback buttons dim when the TV reports it has no media controls to offer. **Hold it** to
  go straight to typing — searching is the one thing you pick the phone up already meaning to
  do, so it should not be two taps deep.

Mute is volume zero with the previous level remembered, because Companion has no mute command
— the HID table has volume up and down and nothing else. So it only works on a TV that
reports volume control at all: where sound leaves over HDMI to a receiver the set often
refuses `SetVolume`, and that surfaces as an error rather than as silence. Nudging volume by
hand counts as unmuting.

Also:

- **The volume rocker drives the television**, not the phone, while a TV is connected and the
  remote is on screen. Elsewhere in the app the keys do what they normally do.
- **Power** — sleep and wake, top right, with the current state shown by how the icon is lit.
- **Apps** — everything launchable, with the ones you use pinned to the top. Hold a row to
  pin or unpin it.
- **Type** — send text to the focused search field on the TV.
- **The D-pad** is still there behind More, for stepping through a grid of tiles.

Every button ticks the motor on finger-*down*, not on release: your eyes are on the
television, so the press has to be confirmed in your hand the instant it lands.

Names follow the television rather than the protocol. The HID command Apple calls "Menu" is
what tvOS treats as *back*, which is why it is labelled Back.

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
`SystemFonts`, no ripples, 45ms haptic on finger-down. Most vector icons in
`app/src/main/res/drawable/` are the SDK's own.

Five are not, because the set has no equivalent: the D-pad, the trackpad, the 3x3 app grid,
the keyboard and the power mark. Those come from `scripts/generate_ui_icons.py`, which holds
the geometry once and can emit a contact sheet rendered at the size the icons are actually
used — about 24 device pixels — because that is the only size at which it is worth judging
them. Everything interesting about these was decided there: the trackpad's first drag trail
was a curve and read as the share arrow, then the fingertip went too because inside a 24px
box it fought the border rather than explaining it. The keyboard needs *two* rows of keys —
one row plus a space bar reads as a card — and it needs the case, because bare rows of keys
(which is what Apple's own glyph uses) blur into a smudge at this scale.

```
python3 scripts/generate_ui_icons.py --preview /tmp/icons.png
```

### The wheel

Turning the wheel scrolls the **Devices** and **Apps** lists, which are the two screens that
can run past the bottom of the panel. Nothing exotic is involved, and nothing else has to be
installed: Light relabelled the wheel sensor's two scancodes in
`/system/usr/keylayout/Generic.kl` and nothing in the system intercepts them, so they land in
the focused window as ordinary key events, and this app reads them itself. No service, no
permission, no root. `MainActivity` takes them in `dispatchKeyEvent`, which is early enough to
win against the focused text field on the **Type** screen — otherwise a turn there would type
a letter at the TV.

Notches are paid off a fraction per frame rather than applied as they arrive. The sensor
fires faster than the display refreshes, so a spin applied notch-by-notch is a stack of jumps
instead of a scroll. The first notch after a pause is also held back until a second confirms
it, because the wheel sits under a thumb and catches stray brushes. Both live in `hw/`; the
long version is in
[LightNews](https://github.com/gi-os/LightNews#the-wheel-and-the-camera-button).

The wheel does not drive the TV. It scrolls this app's own lists and nothing else — a notch is
a scroll gesture, so mapping it onto D-pad presses would send a burst of them. The click and
the camera button are not read here either.

The volume rocker *is* forwarded, through the same `dispatchKeyEvent` override and a bus of
its own. Both halves of the press are swallowed — leaving the UP to Android is what pops its
volume panel over the remote — and it is only intercepted while a television is connected and
the remote is the visible screen, because grabbing the keys for as long as the app is open
would leave the phone's own volume unreachable.

Haptics need `android.permission.VIBRATE` in the manifest. Without it `Vibrator.vibrate`
throws a `SecurityException` that the helper swallows, so every button stays silent and
nothing anywhere explains why.

Giving the rest of the wheel a job is a separate, optional install. With
[LightControl](https://github.com/gi-os/LightControl), holding the wheel in and turning it
changes brightness, a tap toggles the flashlight, and the camera button opens the camera — each
of those rebindable, tap and hold separately, to any installed app. It also lends brightness or
a synthetic-swipe scroll to apps that don't read the wheel for themselves. It does not take
this app's scrolling away: bare turns are passed straight through to `com.gios.*` deliberately,
because a per-notch scroll decided inside the app beats anything a service outside it can fake.

```bash
# Optional: LightControl, for brightness, the flashlight and the camera button
adb install -r LightControl-v1.0.x.apk

# The key service. NOTE: this setting is a list, and this command REPLACES it —
# if you also run LightVoice's push-to-talk, colon-join both components instead.
adb shell settings put secure enabled_accessibility_services \
  com.gios.lightcontrol/com.gios.lightcontrol.keys.ControlService
adb shell settings put secure accessibility_enabled 1

# Brightness, and the level readout + opening apps from the service
adb shell appops set com.gios.lightcontrol WRITE_SETTINGS allow
adb shell appops set com.gios.lightcontrol SYSTEM_ALERT_WINDOW allow
```

Latest APK: <https://github.com/gi-os/LightControl/releases/latest>

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
- **The JVM masks Long shift distances to six bits**, so `counter ushr 64` is `counter ushr 0`,
  not zero. Building a 12-byte nonce with a loop over the full width therefore stamped a copy
  of the counter's low byte into byte 8. Counter 0 is all zeros either way, so the *first*
  encrypted frame of every session worked and every frame after it was dropped by the device
  without a word — which reads as a TV that stopped answering. Round-trip tests could not see
  it, because the fake accessory derived its nonces with the same helper and was wrong in the
  same way. Nonce derivation is now pinned against an independent reference.
- **LazyColumn keys must be unique across the whole list.** Paired and discovered devices are
  updated by different events, so for an instant after pairing one device can be in both —
  and an overlapping key is a crash, not a duplicate row. Hence namespaced keys plus a filter
  at render time.

## When something goes wrong

The link is encrypted after pair-verify and there is no way to watch it from outside, so the
app narrates itself:

```
adb logcat -s LightRemote
```

Every frame in and out (type, length, whether it was encrypted), every request with its
transaction id, and every connect step. Payload bytes are deliberately never logged — they
carry the pairing and session keys.

Two lines matter most:

- `!! unmatched …` — the device answered with a frame or transaction id nobody was waiting
  for. This is what becomes a timeout a few seconds later, and it says whether the TV replied
  with something unexpected or never replied at all.
- `!! could not decrypt a … frame; session keys out of step` — the two ends disagree about the
  ChaCha counters. Unrecoverable; reconnect.

Timeouts name the frame they were waiting for (`no answer to _sessionStart`) rather than
saying only that something timed out.

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
