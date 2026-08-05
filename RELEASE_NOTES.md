## LightRemote v1.18 — your pairings survive the phone, and the app is a good deal smaller

### LightSync can back this app up now, pairings and all

Set up a backup in LightSync and **Remote** appears in the list. What travels is the whole of
what the app knows: the televisions you have paired with, the identity your Apple TV keys its
paired-controller list on, whether you prefer the pad or the D-pad, and the apps you pinned.

The pairing keys really are in there, which is worth saying because the honest answer for some
of the other apps is that they are not. This app has never sealed its credentials with a
device-bound key — they are stored the same way `atvremote` stores them, in the app's private
storage, because what they grant is permission to press buttons on a television on the same
network. That decision was made for other reasons and it pays off here: restore onto a new
phone and the remote reconnects to your television without pairing again, and the television
does not end up listing a second dead controller.

Queued bug reports are deliberately left out of the backup. They are about a phone that no
longer exists.

### The app is smaller and starts faster

The release build is now minified and shrunk, with R8 in full mode, and it carries a startup
profile. On the LPIII the cold start is the part you actually feel, and this is the cheapest
thing that helps.

**This is the risk in the release.** Minifying rewrites and deletes code the build believes
nothing reaches, and the way it goes wrong is not a compile error — it is one screen misbehaving
at runtime. The parts most likely to be affected are the pairing handshake and mDNS discovery,
so if a television that used to be found stops being found, or pairing fails at the PIN, shake
the phone and send it. The keep rules for those are written down with the reason for each one,
which is what makes a report like that fixable in one go.

### The plumbing is shared with the other Light apps now

Shake-to-report, the crash offer and the hardware-key reader are no longer a copy of the same
files living in this repo — they come from `light-common`, which every Light app on the phone
uses. A fix to the report queue or the shake gesture now reaches all of them at once instead of
being ported by hand nine times. Nothing about it looks or behaves differently.

One thing did not move: the wheel. The library's wheel arms on two notches and then passes
everything through, which is right for scrolling a list and wrong here — walking the tvOS focus
turns a single stray notch into a whole row going the wrong way, which is exactly the bug v1.17
fixed. LightRemote keeps its own guard until that fix is upstream.
