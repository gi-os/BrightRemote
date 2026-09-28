## BrightRemote v1.30: a TV hangup that heals itself no longer files a report

**tvOS hangs up idle connections unprompted, and the app already picks them back up.** light-reports#541 and #546 are both this shape: the television closed the connection itself after a healthy session and the app reconnected in 7.9 and 0.3 seconds. There was no app fault in either, and the reports carried nothing actionable.

**Self-filed reports now stay quiet for a clean tvOS hangup.** When the drop is the television closing the connection itself and the link is back inside the settle window, the episode is counted against its signature instead of filed. The count rides into the next report that does go out, so a real pattern is never silently lost. Drops that stay down, recover slowly, have another cause, or arrive with unanswered presses still file exactly as before.

Fixes [light-reports#541], [light-reports#546]: the Apple TV dropped the connection and the app recovered on its own.
