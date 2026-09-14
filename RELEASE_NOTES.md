## BrightRemote v1.29 — a reconnect that happens in your pocket no longer crashes the app

**A link that came back while the phone was in a pocket killed the app.** The network-back
reconnect added in v1.28 fires while the app is backgrounded — that is the whole point of it: the
Wi-Fi returns and the remote picks its television back up without you having to open the app. But
the moment that reconnect succeeded it started the foreground service that keeps the process alive,
and Android does not allow a backgrounded app to start a foreground service. The result was
`ForegroundServiceStartNotAllowedException` on the main thread, which takes the whole process down
— a remote that "closed itself" while the phone was sitting idle on the home screen.

**The service now waits until the app is back on screen.** The connection itself still comes up in
the background — the socket, the media session and the now-playing tunnel all live in the process
and need no service to exist. What needs the service is *survival*, and the service only does its
job once it is running, so it is brought up on the next resume instead of being attempted from the
background and throwing. A connect that happens while the app is actually open starts the service
immediately, exactly as before; only the background path defers.

Fixes [light-reports#403] — the app closed itself on the home screen after the Wi-Fi came back.
