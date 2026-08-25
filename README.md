# BrightRemote

Apple TV remote for the **Light Phone III**. Launcher label: **Apple TV**. Current
released version: **v1.14.x**; the tracked source is at `1.15.0` pending its release tag.

## Install via BrightMarket

<p align="center">
  <img src="https://gi-os.github.io/brightmarket-index/assets/qr/BrightRemote.png" alt="Scan to open BrightRemote in BrightMarket" width="180" />
</p>

Scan the code above with **BrightMarket** installed to open BrightRemote there and
install or update it directly. Don't have BrightMarket yet? Get it, and browse
every Bright app, at
**[brightmarket.gzl.dev](https://brightmarket.gzl.dev)**.

Speaks Apple's **Companion** protocol directly — mDNS discovery, HAP pairing with the
four-digit code on screen, then an encrypted session. No server, no bridge, no companion
app on another device, no SDK: the Light SDK sandbox has no raw-socket or mDNS access, so
a protocol client cannot live inside it. This is a plain sideloaded APK, package
`com.gios.lightremote`, Compose + Material3, single `app/` module, 18.3 MB, arm64 only.

## Why this exists

MRP (the old iOS Remote protocol) was folded into an AirPlay 2 stream in tvOS 15, so a
current Apple TV only takes standalone control over **Companion**
(`_companion-link._tcp`, HAP pair-setup, ChaCha20-Poly1305 frames). Companion gives
buttons, HID touch, power, the app list and text input — it does not carry now-playing
title or artwork; that needs MRP tunnelled inside a second AirPlay 2 session (binary
plists, an RTSP-ish setup exchange, two more HAP-verified channels, MRP's own protobuf
schema). Nothing off-the-shelf on Android speaks Companion, so it was hand-ported to
Kotlin from [pyatv][pyatv]'s Python implementation — no existing Kotlin client to build
on, no crypto library pulled in either (see [Protocol layer](#protocol-layer)).

## Quick start

1. `git clone https://github.com/gi-os/BrightRemote.git && cd BrightRemote`
2. Grab the signed APK from [Releases](../../releases/latest), or build it:
   ```
   ./gradlew :app:assembleRelease
   ```
3. `adb install -r app/build/outputs/apk/release/app-release.apk` (or track the repo in
   Obtainium — every push to `main` cuts a release).
4. On the phone: TV and phone on the same Wi-Fi, open the app — **Devices** shows the TV
   under **Found** — tap it, type the on-screen code. Once paired it opens straight to
   the remote next time.

Pairing must be enabled on the TV (Settings > AirPlay and HomeKit); a device with it off
is listed but not tappable.

## What it does

Nearly the whole panel is the thing you touch. **Swipe** to move, **tap** to select — the
pad has no border and nothing drawn on it, because a remote you glance at while looking
at a television should not present a dozen targets.

Three buttons along the bottom, and as of the latest commit **every one of them carries a
hold**:

- **Back** — tap for back, hold for the menu overlay (tvOS has no separate menu button,
  so holding back is how you get it).
- **Home** — tap for home, hold for the app switcher.
- **More** — tap slides up everything else: play/pause and skip ±15s, then volume down,
  mute and volume up as their own row, then the D-pad/trackpad toggle, apps and the
  keyboard. Playback buttons dim when the TV reports no media controls. **Hold it** to
  skip the drawer and jump straight to typing — closing the drawer on the way so the trip
  back from Type lands on the pad, not on a panel left open behind it. Searching is the
  one thing you pick the phone up already meaning to do, so it shouldn't be two taps deep.

**Mute** (added in v1.8.12) is volume zero with the previous level remembered — Companion
has no mute command, the HID table only has volume up/down. The level is re-read from the
TV before muting rather than trusted from cache, and nudging volume by hand clears the
remembered level (an unmute by any reasonable definition). It only works on a TV that
reports volume control at all — over HDMI to a receiver, a set often refuses `SetVolume`,
which surfaces as an error rather than silence. Volume down got its own icon in the same
change: it had been wearing the crossed-out speaker mark, which reads as mute, not
quieter. The three volume icons are now a generated family — one speaker cone, one wave
for down, two for up, a cross for mute — so only the part that carries meaning differs.

Also: the volume rocker drives the *television*, not the phone, while a TV is connected
and the remote is on screen; **Type** sends text to the focused field on the TV; the D-pad
is still reachable behind **More** for stepping through tile grids. Names follow tvOS, not
the protocol: the HID command Apple calls "Menu" is what tvOS treats as back, so it is
labelled Back here.

**Power** is held for three seconds rather than tapped. It sits where a thumb passes on the
way to the back chevron, and putting the television to sleep by accident is the most annoying
thing this app could do. The vibration is the progress bar: it starts the instant your thumb
lands, climbs across the three seconds, stops dead if you let go early, and ends in a heavy
thump when it fires. That is what makes three seconds bearable rather than broken-feeling —
otherwise the usual way to check whether a remote did anything is to look at the television,
and the television is the thing being switched off. Motors without amplitude control climb by
shortening the gaps between pulses instead, and the pattern is built from the hold duration so
it cannot stop buzzing early. The icon also shows the current power state by how it is lit.

Every button ticks on finger-*down*, not release — eyes are on the television, so the press
confirms in your hand the instant it lands. This needs `android.permission.VIBRATE`, and it
needs every clickable to go through `lightClickable` or `lightCombinedClickable`: reaching for
Compose's `combinedClickable` directly is what left the three bottom-bar buttons silent, since
they were the only ones carrying a hold.

**Reconnecting** takes three goes before it becomes your problem: one attempt plus two
automatic retries, backing off 1.2s then 2.4s, because a TV that has just dropped the link
refuses pair-verify for as long as it takes to tear down its own session — three attempts at a
fixed 1.2s all landed inside the same refusal, which is three failures that are really one. A
link that drops on its own is picked back up automatically twice more, and coming back to the app
reconnects if it is not connected: the socket cannot survive the phone sleeping and a frozen
process has no moment at which to notice, but returning to the app is a moment.

**A tap outranks an attempt in flight.** Both Retry and tapping a device row cancel whatever is
running first. Without that, a connect grinding through three TCP timeouts made tapping your
Apple TV do nothing at all for twenty seconds — leaving Forget-and-pair-again as the only button
that still worked, which is why the symptom reported was "it always re-pairs" rather than "it
fails to reconnect". Automatic attempts still defer to each other, so a dropped link cannot stack
sockets, and tapping the TV you are already connected to keeps that connection rather than
rebuilding it.

**Frames are sealed inside the write lock, not before it.** The output cipher is a counter and
the device opens frames strictly in order, so sealing outside the lock let two overlapping
commands take nonces *n* and *n+1* and reach the socket in the other order — which a stream
cipher cannot recover from, so the television stopped being able to read anything and the
connection ended. It presented as the Apple TV dropping the link for no reason a moment after you
touched something, and spinning the wheel was the fastest way to cause it. Button presses are
serialised for a related reason: a press is a DOWN and an UP, each waiting for its own reply, and
two overlapping presses put DOWN, DOWN, UP, UP on the wire, which the television reads as a key
repeat.

**Discovery** browses `_companion-link._tcp` while holding a `WifiManager.MulticastLock`. Wi-Fi
hardware drops multicast frames not addressed to it so the radio can sleep, and mDNS is
multicast — without the lock the browse starts, reports no error and hears nothing. `NsdManager`
takes one itself on most builds, which is why the gap only shows on a ROM like this one.

Some networks will never carry it regardless: client isolation on guest and IoT networks drops
multicast outright, mesh systems often will not forward it between nodes, and multicast filtering
is on by default in plenty of routers. **Devices → Address** is the way through — the IP from the
TV's Settings → General → About, with an optional `:port`, pairing normally from there. And when
a connect fails with nothing answering at the stored address, the app re-browses for the device
by name once and retries at whatever address it finds, which is the ordinary way this breaks: a
DHCP lease expires while the set is unplugged.

**Buttons do not require an acknowledgement.** The television answers `_hidC`, but not always and
not promptly, and treating that silence as failure reported working presses as broken — then
skipped the key-up, leaving tvOS repeating a key it believed was held. The release now goes out
in an uncancellable `finally`, the wait is two seconds rather than eight (presses are serialised,
so one stall blocks the rest), and a run of three unanswered presses raises a banner. Power is
the exception and still throws: a set that refuses Sleep has to say so.

**Not implemented:** now-playing title or album art — that needs the MRP/AirPlay 2 stack above.

## Configuration and usage

### The wheel

Turning the wheel scrolls **Devices** and **Apps** — the two screens that can run past the
bottom of the panel. LightOS relabels the wheel sensor's two scancodes as `WHEEL_CCW`/
`WHEEL_CW` in `/system/usr/keylayout/Generic.kl`, and nothing intercepts them, so they
land in the focused window as ordinary key events. `MainActivity` reads them in
`dispatchKeyEvent` — early enough to beat the **Type** screen's text field, otherwise a
turn there would type a letter at the TV. No service, no permission, no root, and
**nothing else needs installing for this**. Notches are paid off a fraction per frame
(the sensor fires faster than the display refreshes) and the first notch after a pause
waits for a second to confirm it, since the wheel sits under a thumb.

On the remote the wheel drives the *television*, moving the tvOS focus up and down. Two
notches per row: one-to-one was the obvious first guess and it overshot by exactly double,
because the sensor is optical, so a "notch" is a sampling artefact rather than a detent you can
feel, and what reads as one flick of the thumb is two of them. The remainder is kept between
turns rather than discarded — throw it away and a wheel turned one notch at a time never moves
anything, since no single notch reaches the threshold on its own.

**A stray notch is not a change of direction.** The sensor reads a moving surface rather than a
detent, so a steady turn is not a clean run of same-sign notches — a few percent come back
inverted, most often at the start and end of a spin where the surface is barely moving. A
scroller absorbs those, since the debt is signed and they mostly cancel; walking the tvOS focus
does not, and one stray notch there is a whole row going the wrong way. So while a turn is in
progress notches against it do nothing, and a real reversal commits on the third one through —
three rather than two because strays cluster where the surface is barely moving, which makes two
in a row ordinary, and at a threshold of two the guard would hand one through *and* flip the
direction it is protecting. The rules live in `NotchGuard`, pulled out of its Composable so
`NotchGuardTest` can
drive them over a synthetic clock — the rest of `hw/Wheel.kt` needs a real sensor and a real
frame clock, and there is no Android toolchain in the sandbox this is written in.

Steps are also paid out no faster than one per 110ms — a floor on the rate rather than an addition
to it, since each step is now *awaited* and a round trip that already took longer has paced
itself. Firing them on the timer regardless was how a slow link built a queue of presses that kept
walking the focus after the thumb stopped; clamping the bank does nothing about steps already in
the air. The bank is clamped at two rows' worth, down from four, which outlived the gesture by most
of a second. This was deliberately not wired up at all at first, on the grounds that a notch is a
scroll gesture rather than a D-pad press; that objection is real, and the rate limit is the
answer to it. Feed notches straight through and a flick sends a dozen presses and the highlight
ends up somewhere nobody chose.

The volume rocker *is* forwarded to the TV, through the same `dispatchKeyEvent` override,
only while a TV is connected and the remote is the visible screen (otherwise the phone's
own volume would be unreachable). Both halves of the press are swallowed, since leaving
the UP to Android pops its own volume panel over the remote.

Optional, separate install for wheel-click and the camera button:
[BrightControl](https://github.com/gi-os/BrightControl) gives the rest of the phone
brightness (hold wheel + turn), flashlight (tap), and camera (camera button), each
rebindable. It does not take this app's own scrolling away — bare turns pass straight
through to `com.gios.*`.

```bash
adb install -r LightControl-v1.0.x.apk

# NOTE: this replaces the accessibility-service list — colon-join if you also run
# LightVoice's push-to-talk.
adb shell settings put secure enabled_accessibility_services \
  com.gios.lightcontrol/com.gios.lightcontrol.keys.ControlService
adb shell settings put secure accessibility_enabled 1

adb shell appops set com.gios.lightcontrol WRITE_SETTINGS allow
adb shell appops set com.gios.lightcontrol SYSTEM_ALERT_WINDOW allow
```

Haptics need `android.permission.VIBRATE`; without it `Vibrator.vibrate` throws a
`SecurityException` the helper swallows, so every button goes silently mute.

### Protocol layer

Everything under `proto/` and `crypto/` is hand-written, no dependencies:

| Piece | Where |
| --- | --- |
| OPACK serialisation, incl. back-reference table | `proto/Opack.kt` |
| TLV8 | `proto/Tlv8.kt` |
| Binary property lists | `proto/BPlist.kt` |
| NSKeyedArchiver payloads for text input | `proto/RtiPayloads.kt` |
| SRP-6a, 3072-bit, SHA-512 | `crypto/Srp.kt` |
| Ed25519, X25519 | `crypto/Curve25519.kt` |
| ChaCha20-Poly1305 | `crypto/ChaCha20Poly1305.kt` |
| HKDF-SHA512 | `crypto/Digest.kt` |

No BouncyCastle (≈8 MB for the dozen operations one pairing performs), and not the
platform `Ed25519`/`XDH` providers either — those only exist from API 33 and differ
between Conscrypt and OpenJDK, which would mean the unit tests exercise different code
than the phone does. `Curve25519.kt` is BigInteger-based and **not constant time**;
measured cost is ~25ms sign / ~20ms verify, and the only secrets are an ephemeral X25519
key and a long-term Ed25519 key used against a TV on the local network — no remote timing
oracle here.

### How any of this is verified with no Apple TV in CI

A wrong byte anywhere in this stack shows up only as a device that refuses to pair, never
as anything visible on screen — so verification is two-layered and both layers gate CI:

1. **Golden vectors.** `scripts/genvec.py` generates
   `app/src/test/resources/vectors.properties` from *the same libraries pyatv uses*:
   pyatv's own `opack.py`/`hap_tlv8.py`, `srptools`, CPython `plistlib`, and
   `cryptography` for HKDF/ChaCha20-Poly1305/Ed25519/X25519. Matching those bytes means
   being wire-compatible with a client known to work; the Ed25519/X25519 vectors also
   reproduce RFC 8032 §7.1 and RFC 7748 §6.1 exactly, which cross-checks the generator
   itself.
   ```
   python3 scripts/genvec.py
   VEC_DEST=app/src/test/resources/vectors.properties python3 scripts/genvec.py
   ```
2. **A fake accessory.** `app/src/test/kotlin/com/gios/lightremote/FakeAppleTv.kt`
   implements the device half of the handshake; `HandshakeTest.kt` drives the *real*
   client at it over a loopback socket — pair, drop, reconnect with saved credentials,
   send encrypted commands. This is what catches what vectors can't: which frame type
   answers which, which HKDF label belongs to which direction, when the ChaCha counters
   start, and the order the connect sequence has to run in.

Untested on hardware: pairing against a real Apple TV, and whether `NsdManager` needs
`NEARBY_WIFI_DEVICES` (requested defensively on API 33+).

### Wire gotchas worth not relitigating

- **OPACK has no negative integers** — skip-by-seconds must be a `Double`.
- **The connect sequence is load-bearing**: `_systemInfo` first or no status events;
  `_sessionStart` before any button or presses do nothing; newer tvOS leaves
  `TVRCSessionStart` power queries unanswered (older tvOS has no handler at all, so it's
  sent tolerantly).
- A pairing reply arrives as the `_Next` frame, never another `_Start`.
- `srptools` serialises integers minimally (leading zero bytes dropped) except the two
  RFC 5054 `PAD()` sites — `Srp.minimalBytes` reproduces that exactly.
- Touch samples need ~16ms throttling or a full stream reads as a flick.
- A trackpad needs a drag *threshold*, not just a tap-or-swipe verdict at the end. Opening the
  gesture on touch-down and forwarding every wobble means a thumb rolling a few pixels during a
  tap sends the TV a swipe. Nothing goes out until the finger passes
  `viewConfiguration.touchSlop`; the drag then opens from where the finger *started*, not from
  where it crossed the line.
- **Touch samples must go out on one consumer.** This was the real cause of the trackpad
  scrolling at random, and it is an easy one to write twice: a coroutine per sample dispatches
  them in order, but each then suspends on its way to the socket, so they arrive in whatever
  order they finish. Out-of-order samples make the finger look like it jumped backwards and the
  television reads that as a flick — a steady drag makes the selection shoot up and down.
  `CompanionClient` queues samples on a `Channel` drained by a single coroutine, timestamping
  each at *enqueue* time. Under backpressure an intermediate `Hold` is droppable because the
  next supersedes it; `Press`, `Release` and `Click` are not, since a lost `Release` leaves the
  TV believing a finger is still down.
- `_idsID` in `_systemInfo` is the *pairing* identifier, not the client's device id —
  getting it wrong completes the handshake and fails the first request, which presents as
  "paired but won't connect."
- The JVM masks `Long` shift distances to six bits, so `counter ushr 64` is
  `counter ushr 0`, not zero — a 12-byte nonce built with a full-width shift stamped a
  copy of the counter's low byte into byte 8. Counter 0 (the first frame of a session)
  worked either way, so every session's *first* frame succeeded and everything after it
  was silently dropped. The fake accessory used the same buggy helper, so round-trip
  tests didn't catch it either; nonce derivation is now pinned against an independent
  reference.
- Only `.xml`/`.png` may live under `res/` — an attribution `README.md` in
  `res/drawable/` failed `mergeDebugResources`.

## When something goes wrong

The link is encrypted after pair-verify and can't be watched from outside, so the app
narrates itself:

```
adb logcat -s LightRemote
```

Every frame in/out (type, length, whether encrypted), every request's transaction id, and
every connect step — payload bytes are never logged, since they carry the pairing and
session keys. `!! unmatched …` means the device answered a frame/transaction id nobody was
waiting for (becomes a timeout); `!! could not decrypt …` means the ChaCha counters are out
of step and the session is unrecoverable — reconnect. Timeouts name the frame they were
waiting for (`no answer to _sessionStart`) rather than saying only that something timed
out.

## Building

Requires JDK 17 and the Android SDK. CI (`.github/workflows/build.yml`) runs the unit
tests (45 tests, ~22s), builds a signed release APK, checks the signing certificate
against `signing-fingerprint.txt`, verifies a launcher icon is declared, and publishes one
GitHub Release per push to `main` — **a push is a release, not a cosmetic action**. The
keystore is committed at `keystore/lightremote.jks` on purpose: a stable certificate is
what lets `adb install -r` upgrade in place and keeps Obtainium updates from failing with
an opaque `Failure: Invalid`.

The release tag is `v<versionName>.<run>` — CI derives `<run>` from the workflow run
number and stamps it into `app/build.gradle.kts` at build time (not committed back).
**Bump the `major.minor` base of `versionName` in the tracked source** when a change
warrants it; the run number alone keeps every tag unique even without a bump.

The five bottom-bar icons with no SDK equivalent (D-pad, trackpad, 3x3 app grid,
keyboard, power) come from `scripts/generate_ui_icons.py`, which holds the geometry once
and renders a contact sheet at the size they're actually used — about 24 device pixels,
the only size worth judging them at:

```
python3 scripts/generate_ui_icons.py --preview /tmp/icons.png
```

Everything else visual is ported from [`lightphone/light-sdk`][sdk] rather than
approximated: 27x31 grid, type scale against a 600px vertical baseline, Akkurat from
`SystemFonts`, no ripples, 45ms haptic on finger-down.

## Contributing

Issues and PRs welcome. Before sending a change:

- `./gradlew :app:testDebugUnitTest` — the protocol layer is the whole app and none of it
  is checkable by eye; a wrong byte in OPACK, SRP, ChaCha20-Poly1305, Ed25519 or the plist
  writer shows up only as a TV that refuses to pair, so these tests gate CI and should
  gate a PR too.
- If you touch anything under `proto/` or `crypto/`, regenerate the golden vectors
  (`python3 scripts/genvec.py`) against a freshly cloned `pyatv` and confirm
  `HandshakeTest` still passes — that's the only way to be sure a change is still
  wire-compatible with no Apple TV on hand.
- Keep `res/` to `.xml`/`.png` only (see the wire gotchas above).
- CI publishes a release on every push to `main` — verify locally before pushing there.

## Version history

Tags are `v<major.minor>.<CI run number>`; the base only moves when the source is bumped,
so consecutive tags can share a `major.minor` across several unrelated changes.

| Version | Change |
| --- | --- |
| *(pending)* | Back is a tap, unacknowledged presses are not failures, full-size D-pad, swipe gain, multicast lock, typed address (source bumped to `1.19.0`) |
| *(pending)* | Seal frames inside the write lock, serialise presses, wheel reversal guard, tap-beats-in-flight reconnect, report chip (source bumped to `1.17.0`) |
| v1.16.x | Shake the phone to report a bug |
| *(pending)* | Remove Stay open — it could not work, and BrightControl now does it properly (source bumped to `1.15.0`) |
| v1.14.x | Stay open (withdrawn in v1.15 — see the release notes) |
| *(pending)* | Hold More to jump straight to typing — every bottom-bar button now has a hold action (source bumped to `1.9.0`) |
| v1.8.13 | Answer whether the wheel needs anything else installed (docs) |
| v1.8.12 | Add mute, and give volume down its own icon |
| v1.7.11 | Make the bars opaque so the drawer slides behind them |
| v1.7.10 | Empty the remote out: pad, three buttons, everything else in a drawer |
| v1.6.9 | Two rows of keys on the keyboard, and empty the trackpad |
| v1.5.8 | Custom bottom-bar icons: D-pad, trackpad, app grid, keyboard |
| v1.4.7 | Rework the remote face: icon bar, real power icon, pinned apps |
| v1.3.6 | Scroll the device and app lists with the hardware wheel |
| v1.3.5 | Fix the session nonce, which broke every frame after the first |
| v1.2.4 | Make the link traceable, and stop a stale reader killing a live connection |
| v1.1.3 | Fix the crash on pairing, the failure to connect, and open on the remote |
| v1.0.2 | Drop the attribution note out of res/drawable |
| — | Apple TV remote over the Companion protocol (initial commit) |

## Licence

MIT. The design tokens and vector icons come from [`lightphone/light-sdk`][sdk], also MIT.
The protocol was implemented against [pyatv][pyatv]'s source (MIT) as the reference.

[sdk]: https://github.com/lightphone/light-sdk
[pyatv]: https://github.com/postlund/pyatv

<!-- bright-footer:begin -->
---

## Bright\*

26 open-source apps for the **Light Phone III** — camera, music, maps, messages,
reading, transit, games. The phone has no app store, so they install by sideload: scan one
code from **[brightmarket.gzl.dev](https://brightmarket.gzl.dev)** and BrightMarket keeps them updated.

[Roll](https://github.com/gi-os/Roll) · [BrightMusic](https://github.com/gi-os/BrightMusic) · [BrightWay](https://github.com/gi-os/BrightWay) · [BrightChat](https://github.com/gi-os/BrightChat) · [BrightControl](https://github.com/gi-os/BrightControl) · **BrightRemote** (you are here) · [browse all 26 →](https://brightmarket.gzl.dev)

The Light Phone does not sponsor or endorse any of these. Built by
[Giovanni Lupo](https://github.com/gi-os) — if this one is useful to you, a ⭐ helps the next
person find it.
<!-- bright-footer:end -->
