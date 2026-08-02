## Apple TV v1.14 — Stay open

**Leave the remote open and the phone will come back to it, instead of dropping you on the
LightOS home screen every time the display sleeps.**

Using a remote is minutes of picking the phone up, pressing one thing, and putting it back
down. The screen times out between every one of those, and LightOS brings its home screen
forward on wake — so pausing something meant waking the phone, finding Apple TV in the
tools list, waiting for it to reconnect, and only then pressing pause. Long enough that it
was usually quicker to go find the plastic remote.

**Stay open** is a new setting at the bottom of **Devices**, off until you turn it on. With
it on, the phone waking puts the remote back exactly where you left it — same screen, same
live connection to the television, a half-typed search still in the field.

It only applies to the screen going to sleep. **Leaving on purpose is still leaving:** press
home, back out of the app, or switch to something else, and the remote stays gone until you
open it again. That distinction is not a guess about how long the screen was off — Android
tells the app when the *user* walked away (`onUserLeaveHint`) and stays silent when the
system took the screen, so pressing home and the display timing out are two different events
rather than one event that has to be interpreted.

Switching it on asks for **Display over other apps**. That grant is doing one specific job
and nothing else: bringing an activity back from a broadcast receiver is a background
activity launch, which Android has blocked since 10 and tightened again in 14, and holding
that permission is the exemption that lets the relaunch land. The app draws no overlays and
never has. Revoke it in Settings and the setting turns itself back off rather than sitting
there reading "On" while doing nothing.

Two honest limits. The relaunch arrives about half a second after the screen lights up —
firing instantly is a race against the launcher that is lost as often as it is won, and
landing deliberately a beat later reads better than flickering. And if Android reclaims the
app's process while the phone sleeps, there is nothing left listening for the wake and that
one wake is missed; a remote you used minutes ago is normally still cached, but an overnight
sleep will not bring it back. Avoiding that would mean a permanent notification in the shade,
which is a worse trade on this phone.

Also in this release: a `check.yml` workflow, so a change can be compiled and unit-tested on
a branch before anything reaches a phone. Until now the only build that ever ran was the one
that also published a release.
