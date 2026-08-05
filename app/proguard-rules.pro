# Keep rules for the release build, which is minified and shrunk with R8 in full mode.
#
# Every rule below names the mechanism that reaches the code, because a rule with no reason
# attached is a rule nobody can ever delete. There is deliberately no blanket
# `-keep class com.gios.lightremote.**`: the protocol layer is most of the app, none of it is
# reached by anything but a call, and keeping it would give up most of what minifying buys.
#
# light-common ships its own consumer rules for the report queue, the LightSync provider and
# the crash attributes, so nothing here repeats them.

# ---------------------------------------------------------------- crash reports

# Full mode strips these unless asked. Without them a stack trace in a shake-to-report issue is
# a wall of `a.a.a` with no line numbers, which makes the whole reporting feature worthless.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------- LightSync

# The provider is named as a string in AndroidManifest.xml, so no code refers to it. aapt2
# generates a keep rule from the manifest, and light-common keeps subclasses of LightSyncBackup
# — this makes the no-arg constructor explicit, because in full mode a `-keep` on a class no
# longer implies keeping its members and a ContentProvider is instantiated reflectively.
-keep class com.gios.lightremote.backup.Backup {
    public <init>();
}

# ---------------------------------------------------------------- notes, not rules
#
# Things that look like they need a rule and do not. Written down so the next person reading a
# full-mode failure does not add them speculatively.
#
#  * The Companion crypto. `MessageDigest.getInstance("SHA-512")` and `Mac.getInstance(
#    "HmacSHA512")` are algorithm-name lookups, but they resolve inside the platform's JCA
#    providers — no class of this app's is named by a string, so nothing here can be shrunk out
#    from under them. SRP-6a, Curve25519, Ed25519 and ChaCha20-Poly1305 are plain Kotlin in
#    `crypto/`, called normally, and may be renamed freely.
#
#  * OPACK, TLV8 and the binary plist writer. They are parsed and written by *wire* tag, and the
#    dictionary keys are string literals in the source. Nothing is mapped from a Kotlin field or
#    class name, so no field has to keep its name.
#
#  * The mDNS listeners in `discovery/`. `NsdManager.DiscoveryListener` and `ResolveListener`
#    are android.jar interfaces, so R8 treats their methods as reachable and the anonymous
#    classes implementing them are allocated in code it can see. The framework calls back
#    through the interface, never by name.
#
#  * The frame and command enums in `companion/`. Every one of them carries its wire value as a
#    field; `.name` is used only in logcat tracing. No enum is looked up by `valueOf`, so none
#    of them needs its constant names kept.
#
#  * There are no Services, Receivers or Workers in this app — the manifest names one Activity
#    and one provider, both handled above.
