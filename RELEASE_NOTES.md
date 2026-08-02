## Apple TV v1.15 — Stay open removed, and done properly elsewhere

**v1.14's Stay open setting could not work. It is gone, along with the overlay permission it
asked for. The feature now lives in LightControl, where it actually functions.**

Stay open was meant to bring the remote back when you woke the phone, by listening for
`ACTION_SCREEN_ON` and relaunching itself. On Android 14 that is not possible. A backgrounded
app is cached, a cached app is frozen, and context-registered broadcasts to a frozen app are
**queued until it is unfrozen** — so the broadcast arrives only once something has already
brought the app forward, which is precisely the thing it was supposed to do. Nothing was ever
going to unfreeze it. `ACTION_SCREEN_ON` cannot be declared in a manifest either, which is the
one form of receiver that *does* unfreeze an app on delivery.

So the setting sat there reading "On" and doing nothing, and asking for **Display over other
apps** in order to do it. An app holding a permission it cannot use is worse than an app without
the feature, so both are out: the setting, the receiver, the `Application` subclass and the
`SYSTEM_ALERT_WINDOW` declaration. Nothing else about the app changed — this release restores
v1.13's behaviour exactly.

**Where it went.** LightControl v1.4 has a home-button action called *Back to where you were*.
Its `ControlService` is an `AccessibilityService`, bound by the system, so its process is never
cached and never frozen; it already watches which app is in front and already owns the home
button. Bind **Home button opens → Back to where you were**, tick Apple TV under **Resume
apps**, and the first home press after a wake brings the remote back with its session intact.
The press after that goes home.

v1.14 also had a real bug worth recording, since it is the kind that hides a working feature:
tapping the setting without the overlay grant sent you to Settings but never turned the setting
on, so you came back to a row still reading "Off" and had to tap it a second time.

Kept from v1.14: the `check.yml` workflow, which compiles and unit-tests a branch without
publishing. That one was worth having.
