package com.gios.lightremote.proto

/**
 * NSKeyedArchiver payloads for the Companion protocol's Remote Text Input channel.
 *
 * These are hand-built object graphs rather than the output of a general archiver. A full
 * NSKeyedArchiver implementation is a lot of machinery for two fixed messages, and the
 * shapes below are the ones tvOS is known to accept.
 *
 * Object table layout for both payloads, which is why the [Uid] indices look arbitrary:
 *
 * ```
 * 0  "$null"
 * 1  RTITextOperations   -> keyboardOutput(2), targetSessionUUID(5), $class(7)
 * 2  TIKeyboardOutput    -> insertionText(3) for insert, $class only for clear
 * 5  NSUUID              -> NS.uuidbytes, $class(6)
 * 6  NSUUID class
 * 7  RTITextOperations class
 * ```
 */
object RtiPayloads {

    private fun classDescriptor(name: String): Map<String, Any?> = linkedMapOf(
        "\$classname" to name,
        "\$classes" to listOf(name, "NSObject"),
    )

    private fun envelope(objects: List<Any?>): Map<String, Any?> = linkedMapOf(
        "\$version" to 100000L,
        "\$archiver" to "RTIKeyedArchiver",
        "\$top" to linkedMapOf<String, Any?>("textOperations" to Uid(1)),
        "\$objects" to objects,
    )

    /** Replace whatever is in the focused field with nothing. */
    fun clearText(sessionUuid: ByteArray): ByteArray {
        require(sessionUuid.size == 16) { "RTI session UUID must be 16 bytes" }
        return BPlist.write(
            envelope(
                listOf(
                    "\$null",
                    linkedMapOf<String, Any?>(
                        "\$class" to Uid(7),
                        "targetSessionUUID" to Uid(5),
                        "keyboardOutput" to Uid(2),
                        "textToAssert" to Uid(4),
                    ),
                    linkedMapOf<String, Any?>("\$class" to Uid(3)),
                    classDescriptor("TIKeyboardOutput"),
                    "",
                    linkedMapOf<String, Any?>(
                        "NS.uuidbytes" to sessionUuid,
                        "\$class" to Uid(6),
                    ),
                    classDescriptor("NSUUID"),
                    classDescriptor("RTITextOperations"),
                ),
            ),
        )
    }

    /** Append [text] at the cursor in the focused field. */
    fun insertText(sessionUuid: ByteArray, text: String): ByteArray {
        require(sessionUuid.size == 16) { "RTI session UUID must be 16 bytes" }
        return BPlist.write(
            envelope(
                listOf(
                    "\$null",
                    linkedMapOf<String, Any?>(
                        "keyboardOutput" to Uid(2),
                        "\$class" to Uid(7),
                        "targetSessionUUID" to Uid(5),
                    ),
                    linkedMapOf<String, Any?>(
                        "insertionText" to Uid(3),
                        "\$class" to Uid(4),
                    ),
                    text,
                    classDescriptor("TIKeyboardOutput"),
                    linkedMapOf<String, Any?>(
                        "NS.uuidbytes" to sessionUuid,
                        "\$class" to Uid(6),
                    ),
                    classDescriptor("NSUUID"),
                    classDescriptor("RTITextOperations"),
                ),
            ),
        )
    }

    /**
     * Pull the session UUID and the text already in the field out of the `_tiD` archive
     * the device sends back from `_tiStart`.
     *
     * Rather than deserialise the graph properly, follow the same two paths pyatv does and
     * resolve [Uid]s against `$objects` as they appear.
     */
    fun readSessionState(archive: ByteArray): Pair<ByteArray, String>? {
        val root = BPlist.read(archive) as? Map<*, *> ?: return null
        val objects = root["\$objects"] as? List<*> ?: return null
        val top = root["\$top"] as? Map<*, *> ?: return null

        fun resolve(start: Any?, path: List<String>): Any? {
            var element: Any? = start
            for (key in path) {
                if (element is Uid) element = objects.getOrNull(element.value)
                val map = element as? Map<*, *> ?: return null
                element = map[key] ?: return null
            }
            if (element is Uid) element = objects.getOrNull(element.value)
            return element
        }

        val uuid = resolve(top, listOf("sessionUUID")).let {
            (it as? Map<*, *>)?.get("NS.uuidbytes") as? ByteArray ?: it as? ByteArray
        } ?: return null

        // A field that has never been typed into simply has no contextBeforeInput.
        val text = resolve(top, listOf("documentState", "docSt", "contextBeforeInput")) as? String ?: ""
        return uuid to text
    }
}
