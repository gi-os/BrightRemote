## LightRemote v1.17 — the connection stops dying, and the wheel stops arguing

**Three things were wrong at once, and two of them turned out to be the same bug.**

### The Apple TV was dropping the link, and it was our fault

Every button in the app runs in its own coroutine, so anything you do quickly overlaps. A frame
going out is sealed with ChaCha20-Poly1305, and that cipher is a counter: each frame takes the
next nonce and the television opens them strictly in order. The sealing happened *outside* the
lock that serialises the writes — so two overlapping commands could take nonces 7 and 8 and then
reach the socket in the other order. A stream cipher cannot resynchronise from that. The
television stops being able to read anything, and the connection is over.

Which looked exactly like the Apple TV dropping the link for no reason, a second after you
touched something. Sealing now happens inside the lock, so the nonce a frame takes and the
position it goes out in are decided together.

Spinning the wheel was the fastest way to trigger it, which is why it looked like a wheel problem.

### Turning the wheel jumped around

Two causes, on top of the one above.

A press is a DOWN and an UP, and they mean nothing apart — but each half waits for its own reply,
so two overlapping presses put DOWN, DOWN, UP, UP on the wire. The television reads the second
DOWN arriving under the first as a key repeat and the focus travels further than the two rows you
asked for. Presses are now serialised: one button at a time, halves kept together.

And the wheel is an optical sensor reading a moving surface, not a detented encoder, so a steady
turn is not a clean run of same-sign notches — a few percent come back inverted, most often at the
start and end of a spin where the surface is barely moving. Scrolling a list absorbs that. Walking
the tvOS focus does not: one stray notch is a whole row going the wrong way. While a turn is in
progress notches against it now do nothing, and a real change of direction commits on the third
one — three rather than two because strays cluster, so a pair of them back to back is ordinary.

The coast after a hard spin is halved too, from four rows to two, and the wheel now waits for each
step instead of paying them out on a timer regardless. That second one matters more than it
sounds: a spin whose round trip ran slower than the 110 ms timer was building a queue of presses
that kept walking the focus long after your thumb stopped, and clamping the notches did nothing
about presses already in the air. A slow link now slows the wheel down instead.

### Backing out and tapping the TV made you pair again

Not because the pairing was lost. Because a connect attempt already in flight blocked every new
one, and a failing attempt can grind through three TCP timeouts and a pair-verify that will never
answer. During those twenty-odd seconds, tapping your Apple TV in the list did *nothing at all* —
so the only button that still worked was Forget and pair again.

A tap now outranks whatever is already running. Alongside that:

- **The remote reconnects when you come back to it.** The socket cannot survive the phone
  sleeping and the process is frozen, so there is no moment at which the app could notice — but
  returning to it is a moment, and it now picks the connection back up instead of showing Retry
  and waiting to be asked.
- **Retry backs off properly.** Three attempts 1.2 s apart all landed inside the same refusal
  while the television tore down its old session — three failures that were really one. The delay
  doubles now.
- **Tapping the TV you are already connected to keeps the connection**, rather than tearing down a
  working session to rebuild it.
- **"Lost the connection" no longer appears when the app closed the link itself.** Closing a
  socket makes the reader throw — of course it does — and that was being reported as the
  television hanging up. Every reconnect starts by closing the old socket, so this was on the
  happy path: a failure banner on the way out, and an automatic reconnect of something the app had
  just deliberately closed.

### Reporting is a chip in the corner now

Shake-to-report no longer throws a sheet across the screen. A small **SEND ERROR?** chip appears
at the bottom left, and only a tap on it opens the sheet — ignore it for four seconds and it
fades, having cost you nothing. This matters more on a remote than anywhere else, because waving
the phone at the television is a shake.

A crash last run offers the same chip for eight seconds, and ignoring it is safe: the trace stays
on disk and is offered again next launch.

The app also reports itself now. A connection it could not rebuild after every retry, or a
television that kept dropping the link, raises the chip on its own — those are the failures that
otherwise never get filed, because an error banner disappears the moment the screen changes.

### Under the hood

The two protocol failures above are now regression tests against the fake Apple TV — twelve
overlapping requests, and eight overlapping presses checked for paired DOWN/UP on the wire. Both
fail on the previous build. The wheel guard was also pulled out of its Composable into a testable
`NotchGuard`, with eight tests over a synthetic clock, because there is no Android toolchain in
the sandbox this is written in and a test that runs without one is worth more than usual.
