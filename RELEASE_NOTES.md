## BrightRemote v1.28 — a drop that outlives the Wi-Fi is picked back up, and reports say which exception

**A dropped link gets three connect attempts inside four seconds, and then the app waits for you to
notice.** That ladder was written for one failure and it is right about it: a television tearing
down its own session refuses the next pair-verify for a moment, so one failure proves nothing and
1.2 seconds later it usually works. It is wrong about the other failure, and
[light-reports#253] is the other failure written out in full — the drop, then
`ConnectException: ... ENETUNREACH (Network is unreachable)`. The phone had no network at all. All
three attempts were spent in four seconds against a radio that was down, and the app then sat
disconnected with the remote screen open and the user still pressing buttons.

Returning to the app already triggers a reconnect. That is no help here, because nobody left.

**The network coming back is now worth one more attempt.** A default-network watcher reports the
edge — no network to some network — and on that edge the app tries again once, with a fresh budget,
because attempts spent against a missing radio are not evidence about the television.

The obvious repair was a longer ladder, and it is the wrong one. An earlier build retried on every
state change and ground away at a television that was simply switched off, which is why the attempts
are counted and bounded at all. A timer cannot tell a set that is off from a network that is down;
an edge can. A television that is off produces no edge and costs nothing, which is the property the
bounded ladder was there to protect. There is a thirty-second floor between two network-triggered
attempts, so Wi-Fi that flaps cannot turn one edge into a stream of them.

Availability is the test, not validation. `NET_CAPABILITY_VALIDATED` and `NET_CAPABILITY_INTERNET`
both mean the internet is reachable, and the Apple TV is on the far side of the room rather than the
far side of the internet. Waiting for validation would mean not reconnecting on exactly the networks
where the remote still works.

**And every report of a drop now names the exception it was.** A shake-to-report drop is headed with
`cause::class.java.simpleName`. Platform types survive minifying and read as themselves; this app's
own throwables were renamed to one letter, so every clean disconnect arrived headed `a: connection
closed by the Apple TV` and every failed metadata tunnel `a: transient pairing: no salt`. Seventeen
open reports whose only grouping key was the letter a. A `-keepnames` rule for this app's throwables
fixes it, names only — the classes are still shrunk and their members still renamed.

Addresses [light-reports#253], [#268], [#246], [#225] and [#224] — a drop the reconnect ladder
could not outlive.

Already fixed in v1.27, before these were read: [light-reports#226], the three
`NetworkOnMainThreadException` lines that silently swallowed the now-playing subscription.

Still open and needing the television: the metadata tunnel refusing with
`transient pairing: no salt` ([light-reports#268], [#263]) wants the AirPlay pairing UI, which is
its own piece of work.
