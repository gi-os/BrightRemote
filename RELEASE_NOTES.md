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
