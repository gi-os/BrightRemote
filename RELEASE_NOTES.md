## BrightRemote v1.26 — Typing the code now actually finishes the pairing, and the journal learns what you watched

### "No credentials returned"

Field report, v1.25.40: Pair for now playing puts the code on the TV, the keypad takes it,
the SRP proof matches — and the app ends with "no credentials returned". Real tvOS was
refusing the final step of an exchange our own fake receiver had been happily accepting.

The divergence, found by diffing the client against pyatv's real implementation: the display
name in the encrypted M5 payload (TLV 0x11, the "Light Phone" that shows under Settings >
Remotes and Devices) must be an **OPACK dictionary** — `{"name": "Light Phone"}` — and the
client sent it as a bare UTF-8 string. Our Companion pairing has always packed it correctly;
the AirPlay path did not, and tvOS answers a malformed M5 with a bare state-6 TLV carrying
neither credentials nor an error code. The code was right, the crypto was right, and the
pairing still died at the last step, every time.

Three layers of that failure are fixed:

- **The client packs the name as OPACK**, byte-for-byte what pyatv has sent since its
  `aa23a11`. It also now verifies the accessory's M6 identity signature (warning-only, like
  the Companion path) and refuses non-2xx HTTP answers instead of reading an error page as
  an empty TLV.
- **The fake receiver is now as strict as the television.** It verifies the client's M5
  signature over the HKDF-derived controller material, decodes the name TLV as OPACK, and
  answers a malformed one exactly the way tvOS answered v1.25: a bare state with nothing in
  it. The v1.25 client fails three tests against it; the fix passes all of them.
- **The next field failure diagnoses itself.** Every pair-setup error now names its HAP step
  ("The TV's reply at step 6 couldn't be read"), the exchange narrates into the wire trace,
  and a pairing that breaks for any reason other than a mistyped code files a throttled
  auto-report with the trace tail attached — this bug cost an evening because the only
  record of it was one remembered sentence.

### The journal learns what you watched

With the tunnel actually able to pair, the now-playing stream becomes a record: BrightRemote
now assembles viewing sessions from the same SET_STATE messages it already parses — nothing
new is sent or subscribed — and serves them read-only to the LightNotebook journal bus at
`content://com.gios.lightremote.watched/sessions/<yyyy-MM-dd>`.

The session rules, each unit-tested over a synthetic clock: contiguous playback of one title
is one session; a pause under five minutes stays in the session but never counts toward its
duration; a title change or a longer gap closes it at the moment playback stopped; under two
minutes of actual playing is channel-surfing and is dropped. Sessions persist in a small
store capped at sixty days, written only when they close — so a crash or a dropped link can
lose at most the sitting in progress, and can never invent a phantom hour.

## BrightRemote v1.25 — Pair for now playing

### The pairing code came up on the TV, and there was nowhere to type it

Field report from the sofa: "the pairing came up but I didn't see anywhere to type it." Both
halves of that sentence were bugs.

**The code should never have appeared.** The now-playing tunnel (v1.23) probes the AirPlay
channel silently on every connect, using transient pairing — the no-code, nothing-stored mode.
But the probe opened with a POST to `/pair-pin-start`, copied from the shape of the interactive
flow, and that endpoint has exactly one effect on tvOS: it puts a four-digit pairing code on
the television. Against the test receiver, which answers 200 and moves on, it was invisible.
Against a real Apple TV it drew the prompt over whatever was playing, the transient exchange
was then refused (tvOS doesn't do transient pairing — that is a HomePod feature), the tunnel
quietly gave up as designed, and the code sat on the screen with nowhere to go. The probe no
longer sends it. The rule is now written down and tested: on connect, MRP may silently try
pair-verify with stored credentials and transient pairing, both invisible from the sofa —
and if neither works it stays off. No banner, no dialog, one trace line. Nothing on any
automatic path can put a code on a television; the regression test reads the fake receiver's
request log and fails if anything resembling pair-setup or pin-start went out uninvited.

**And when you *do* want the code, there is now somewhere to type it.** An Apple TV only opens
its MRP stream to a controller holding persistent AirPlay-HAP credentials — a second pairing,
separate from the Companion one that runs the buttons. The protocol side has been in since
v1.23; this build adds the way in. A quiet **Pair for now playing** row appears in two places:
on the remote, in the strip where the artwork will live, whenever a connected TV's tunnel
could not come up silently — and on each paired device's own screen (hold the device in the
list). Tapping the row is the moment the TV draws its code. The keypad that follows is the
same screen, the same four slots and the same wheel of digits as the Companion pairing, and
Pair finishes the exchange, stores the credentials and brings the tunnel up on the spot — no
reconnect, titles and artwork just arrive.

The details that make it honest:

- **A wrong code says so and starts over.** HAP tears the exchange down after a failed proof,
  so the retry is a fresh pair-setup — which means a fresh code on the TV. The screen says
  exactly that ("That code did not match. The TV will show a new one."), clears the slots and
  stays up.
- **Leaving the app aborts cleanly.** The exchange is stateful on both ends and the code goes
  stale, so backgrounding mid-pairing closes the socket and keeps nothing. Credentials are
  only ever written after the exchange completes; there is no half-paired state to recover
  or to clean up.
- **One id, two pairings.** The AirPlay credentials live beside the Companion ones, keyed by
  the same device, and forgetting a device forgets both.
- **The remote is never hostage to the metadata.** Everything here is on the optional path:
  a refused pairing, a dead AirPlay port, a TV that cancels — the Companion session never
  notices.

The fake AirPlay receiver grew the accessory half of PIN pair-setup and pair-verify to prove
the loop: pairing stores credentials, the next connect pair-verifies with them and pairs no
further, a refused transient with nothing stored sends nothing that could disturb the TV, and
a wrong PIN fails cleanly then succeeds on a fresh exchange.

## BrightRemote v1.24 — The v1.23 launch crash

### v1.23.38 died within a second of opening; this build fixes it

v1.23 added a media session so BrightControl's lock face grows a now-playing row over this
remote. What it got wrong was a thread. The view model builds that session lazily, on whichever
thread first needs it — and with a television paired the first thread to need it is the
Companion socket's reader, because tvOS starts pushing `_iMC` and `NowPlayingInfo` events the
moment the connection comes up. That reader is a coroutine IO worker with no `Looper`, and
`MediaSessionCompat.setCallback(callback)` creates a bare `Handler()` on the calling thread, so
the first event threw `RuntimeException: Can't create handler inside thread ... that has not
called Looper.prepare()`. The teardown that followed touched the same lazy session from the
same thread, threw the same way outside the reader's catch, and took the process down.

Since the app auto-connects on launch, that sequence ran to completion in under a second, every
time: open, connect, die. It never appeared in CI because the JVM test stubs no-op `Handler`,
and it filed no report because the crash-loop outran the reporter.

The fix is in `RemoteMediaSession`, which now owns its threading instead of trusting its
callers: the callback is registered with an explicit main-looper handler, and `update`,
`deactivate` and `release` hop to the main thread before touching the platform session. Any
future caller, from any thread, gets the safe path — that is the regression guard.

Everything v1.23 added — the foreground service that keeps the link alive with the screen off,
the lock-face media row, the MRP-over-AirPlay now-playing with title, artist and artwork — is
unchanged and still in. Updating over v1.23.38 (or any earlier build) is a plain Obtainium
update; nothing needs uninstalling.

## BrightRemote v1.22 — Skip, without opening the drawer

### The D-pad's dead corners are now back-15 and forward-15

The direction pad had two empty cells, one either side of Down, and they were the wrong two to
leave empty. Skipping fifteen seconds is the thing you reach for most while something is actually
playing — the line you missed, the recap you have already seen — and until now it was three taps
behind the drawer: open it, aim at a small icon, close it, every single time.

They live on the face now. **Back 15** to the left of Down, **Forward 15** to the right, so the
bottom row reads as a timeline: back, down, forward. Both are full-height cells the size of the
left and right arrows, which is to say big enough to hit while looking at the television rather
than at the phone. The two cells beside Up stay blank on purpose — six live targets is still a
face you can work blind, and nothing else earned a seventh.

The icons are drawn smaller than the arrows. The four directions and OK are what the pad is for,
and a skip drawn at arrow weight would compete with Down for the same glance.

### They go dark when there is nothing to skip

Same rule the drawer's copies already follow: when the television reports no playback controls —
the home screen, a menu, anything that is not playing — both buttons are dimmed and dead. On the
home screen a skip genuinely does nothing, and a button that looks live and does nothing is worse
than one that admits it.

### Also in this build

The now-playing progress sliver under the top bar, which went out quietly in v1.21.36: a two-point
fill showing how far into the current title you are, extrapolated from the playback rate between
the television's pushes so it keeps walking forward while tvOS is silent. Visibility only — there
is nothing to scrub.

## BrightRemote v1.21 — Every failure files itself

### The app reports its own disconnects now, without asking

Before this build, a dropped connection *offered* to be reported: a chip in the corner, or a
**Send error** row on the disconnected screen. Both are one tap away from being dismissed, and
both appear at the exact moment you are standing in front of a television that has stopped
working — which is the worst possible time to be asked for paperwork. So several of these
disconnects were only ever diagnosed by reading logcat over somebody's shoulder.

Now the app sends it. Every failure it can detect itself:

- the television hanging up mid-session (the Plex play-press drop),
- a connect or reconnect that fails every attempt,
- three button presses in a row going unacknowledged.

Each report carries what was tried, what came back, how it ended, and the last 150 lines of the
wire trace — frame types, steps and timings, never payload bytes. A banner at the top of the
screen says what was sent, stays ten seconds, and goes away on a tap. Nothing is filed silently.

### One problem does not become thirty issues

Auto-sending needs completely different manners from offering, and the version of this idea in
another app on this phone learned that the expensive way: nine failures on one dead socket filed
thirty separate issues, because the throttle keyed on the message text and every message named a
different command. Three rules keep that from happening here, all in a new `DropWatch` that is
plain Kotlin and covered by seven tests over a synthetic clock:

- **One report per episode.** A drop, its two automatic reconnects, and their failures are one
  event. The report waits nine seconds for the dust to settle and then goes out once — which is
  also what lets it say how it ended: *dropped, picked itself back up after 4.1s* is the sentence
  that identifies this bug.
- **A hard floor of one minute between any two reports**, whatever they say, so a key that turns
  out to be accidentally unique cannot flood through it.
- **Escalating backoff per fault** — now, then two minutes, ten, thirty, then hourly, reset after
  six quiet hours. The second report comes soon on purpose: two traces ten minutes apart are how
  a drop like this actually gets diagnosed. Everything held back in between is counted and
  carried into the next report that does go out, so a body can say *2 more like this went
  unreported*, and nothing disappears.

The fault key is the failure kind plus the exception class, deliberately *not* the message: ports,
byte counts and transaction ids are exactly what made the old key useless.

The throttle lives in preferences rather than memory, so an app that dies on launch, or a phone
rebooting into the same broken Wi-Fi, does not get to treat every launch as a first offence.

### The manual row is still there, for the one the throttle refused

If a report was held back, **Send error anyway** appears on the disconnected screen. Your
judgement about whether this particular drop matters beats any backoff in the code, and forcing
it skips both throttles.

## LightRemote v1.20 — The Plex disconnect gets caught, reported, and reconnected; the wheel goes sideways

### A television that hangs up no longer looks like the app hanging up on itself

The disconnect that hits when a show starts playing — open something in Plex, press play, and
the remote drops — turned out to be handled exactly backwards. tvOS closes Companion links on
its own schedule, and starting playback is one of the moments it does it. That close arrives as
a clean end of stream, not an error, and the session's teardown read "no error" as "we closed
this ourselves". So the one kind of disconnect that most needed noticing got the silent
treatment: no banner, no automatic reconnect, no offer to report it. The remote just sat there
disconnected until you backed out and tapped the TV again.

Only the session's own `closing` flag can say a teardown was deliberate now; an EOF the
television caused is a failure like any other. Which means the existing recovery machinery
finally applies to it: the link is picked back up automatically, twice, before Retry becomes
your problem — so a play-press drop should now heal itself before you notice it happened.
Two new regression tests pin the distinction down against the fake Apple TV, and the hangup
test fails on v1.19.

### Every disconnect can be sent as an error report

Reporting used to wait until both automatic reconnects were spent, on the theory that a drop
that healed itself was noise. Then the link died on every play press for weeks and not one
report went out — each drop reconnected, and disqualified itself. So: every unexpected drop now
raises the report chip (still rationed to once an hour, that part is manners), and the
disconnected screen has a **Send error** row with no such manners — every single drop can be
filed from right there, next to Retry, including the second one in five minutes, which is
exactly the one worth sending.

The report is finally worth reading, too. The protocol layer now keeps the last 150 trace
lines — frame types, lengths, steps, timings, never payload bytes — in a ring, and the whole
wire narrative rides along in the report body. "It disconnects when Plex starts playing" stops
being a shrug and becomes a diagnosis.

### The wheel can walk sideways

A new toggle in the top bar, next to power, switches the scroll wheel between vertical and
horizontal. Vertical is still the default — most of tvOS is lists — but the home screen's rows
and every app's shelf run the other way, and crossing a row by reaching for the pad defeated
the point of having a wheel. The icon shows what the wheel will do *right now* (↕ or ↔), a tap
flips it, and the choice is remembered. Rolling the wheel up goes Left, the same "back the way
you came" that up means in a list.

## LightRemote v1.19 — Back works, the pad is twice the size, and it can find the TV without mDNS

### "no answer to _hidC" was the app calling a working button broken

A press is a DOWN frame and an UP frame, and the app waited for the television to acknowledge
each one. It does acknowledge them — but not always, and not promptly: a set that is busy,
redrawing, or waking a screensaver takes the key and says nothing about it. That silence was
being treated as a failure, so a Back that had actually worked came back as an error.

Worse, the error stopped the press halfway. The UP never went out, so the television believed
Back was still held down and started repeating it. That is the "sometimes Back causes issues":
not a press that did nothing, a press that never ended.

So the acknowledgement is now optional — the frame going out is the part that matters — and the
release goes out in a `finally`, uncancellable, so a key cannot be left down no matter what
happens to the press. The wait is two seconds instead of eight, because presses are serialised
and one stuck press was holding up every button behind it. If a run of three presses in a row
goes unanswered you get one banner saying so, rather than a remote that has quietly stopped
working.

Power is the exception and still reports failures loudly: a TV that refuses Sleep — common where
the sound goes out to a receiver — has to say so, since the whole point of a three-second hold is
that something happens across the room.

### Back is a tap and nothing else

It carried Control Centre on a long press, which was wrong twice. Wrong on the television, where
Control Centre is a held *Home*. And wrong in the hand: Compose calls anything past half a second
a long press, which is an ordinary careful tap on a small icon while you are looking at the TV
rather than at the phone — so Back opened an overlay instead of going back. Control Centre has
moved to hold-Home, where tvOS has it.

### The D-pad fills the screen

The buttons were a fixed size floating in the middle of the panel, so most of the pad area did
nothing and the arrows were small enough to miss without looking. It is a proper three-by-three
now, every cell as large as the space allows. The box around OK is gone — on a target that fills
a third of the screen an outline is decoration.

### Swiping

Two things were wrong. The panel was mapped one-to-one onto the television's touch surface, but a
Siri Remote's pad is about 35 mm across and stroked with a thumb, while the LPIII panel is nearly
twice that and dragged with a whole finger — the same gesture arrived as a much longer, much
faster one, and tvOS reads a long fast stroke as a flick, which throws the list rather than
moving a row. A drag now covers half the surface, which halves the velocity the TV infers too.

And the lag: samples are sent in the order your finger made them, but nothing kept them
*current*, so a link slower than the finger built a backlog and the pointer replayed where your
thumb had been a second ago — still moving after you lifted it. Stale positions are now skipped;
Press, Release and Click never are. The further behind the link falls the more it catches up,
instead of drifting further behind for as long as the gesture lasts. A drag interrupted partway —
by the connection dropping, or the drawer opening — now always sends the release.

### Finding the TV

**The search holds a multicast lock now.** Wi-Fi hardware drops multicast frames not addressed
to it so the radio can sleep, and mDNS is multicast — without a lock the browse starts, reports
no error, and never hears a reply. Android normally takes this lock for you, which is why the
gap is easy to miss and why it shows up on LightOS. "No Apple TV found — are you on the same
Wi-Fi?" on a phone that plainly was, is what it looks like from the sofa.

**You can type the address in.** Some networks will never carry the search: guest and IoT
networks with client isolation drop multicast outright, mesh systems often will not forward it
between nodes, and plenty of routers ship with multicast filtering on. On all of them the phone
and the television can reach each other perfectly well and simply cannot *find* each other.
Devices → Address takes the IP from the TV's Settings → General → About, and pairs normally from
there. Add `:port` if the default one does not answer — Companion takes the first free port from
49152 up, so a set that has been on a long time can be higher.

**A TV that changed address is found again.** When every retry fails with nothing answering, the
app looks the television up by name once more, and if the router has given it a new address it
saves that and tries again there. Which is the ordinary way a remote stops working: a lease
expires while the set is unplugged, and the pairing is still perfectly good at a number nothing
had told the app about.

### Under the hood

67 unit tests, up from 61 — the address parser is covered, including the half-typed states that
used to light the Pair button up. A cancelled connect no longer writes its own cancellation into
the error banner on top of the connect that replaced it, and a press queued behind more than a
second of stalled ones is dropped rather than fired late at a screen that has moved on.
