package com.gios.lightremote

import com.gios.lightremote.companion.AuthenticationException
import com.gios.lightremote.companion.ClientIdentity
import com.gios.lightremote.companion.CompanionClient
import com.gios.lightremote.companion.CompanionConnection
import com.gios.lightremote.companion.CompanionSession
import com.gios.lightremote.companion.PairStart
import com.gios.lightremote.companion.ProtocolException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Asking the television for a pairing code: what M1 looks like, which failures earn a second
 * try, and that a second try is all they earn.
 *
 * The four Pair-screen reports behind this (light-reports#676, #657, #631, #590) were all filed as
 * "the Apple TV dropped the connection". Two are a television that never sent M2, two are one that
 * hung up with the code on screen.
 */
class PairStartTest {

    private val identity = ClientIdentity(deviceId = "AA:BB:CC:DD:EE:02")

    private fun <T> withClient(block: suspend (CompanionClient) -> T): T {
        val scope = CoroutineScope(Dispatchers.IO)
        return try {
            runBlocking { block(CompanionClient(identity, scope)) }
        } finally {
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------ the rules

    @Test
    fun `failures are told apart`() {
        val closed = ProtocolException(CompanionSession.CLOSED_BY_TV)
        val silent = ProtocolException("${CompanionSession.NO_ANSWER}PairSetupStart")
        assertEquals(PairStart.Failure.ClosedBeforeCode, PairStart.classify(closed))
        assertEquals(PairStart.Failure.NoAnswer, PairStart.classify(silent))
        assertEquals(
            PairStart.Failure.ClosedBeforeCode,
            PairStart.classify(java.net.SocketException("Connection reset")),
        )
        assertEquals(
            PairStart.Failure.ClosedBeforeCode,
            PairStart.classify(IllegalStateException(CompanionConnection.NOT_CONNECTED)),
        )
        assertEquals(
            PairStart.Failure.Unreachable,
            PairStart.classify(java.net.ConnectException("ECONNREFUSED")),
        )
        assertEquals(
            PairStart.Failure.Unreachable,
            PairStart.classify(java.net.SocketTimeoutException("connect timed out")),
        )
        assertEquals(
            PairStart.Failure.Refused,
            PairStart.classify(AuthenticationException("device is busy")),
        )
    }

    @Test
    fun `only a hang-up or silence before the code is worth another try`() {
        assertTrue(PairStart.worthRetrying(PairStart.Failure.ClosedBeforeCode))
        assertTrue(PairStart.worthRetrying(PairStart.Failure.NoAnswer))
        // The television said no, or nothing is there: a second try cannot change either.
        assertFalse(PairStart.worthRetrying(PairStart.Failure.Refused))
        assertFalse(PairStart.worthRetrying(PairStart.Failure.Unreachable))
        assertFalse(PairStart.worthRetrying(PairStart.Failure.Other))
    }

    @Test
    fun `a hang-up is retried once after the pause, and then it works`() = runBlocking {
        var attempts = 0
        val pauses = mutableListOf<Long>()
        val retried = mutableListOf<PairStart.Failure>()
        val result = PairStart.withRetry(
            attempt = {
                attempts++
                if (attempts == 1) throw ProtocolException(CompanionSession.CLOSED_BY_TV)
                "code"
            },
            pause = { pauses += it },
            onRetry = { failure, _ -> retried += failure },
        )
        assertEquals("code", result)
        assertEquals(2, attempts)
        assertEquals(listOf(PairStart.PAUSE_MS), pauses)
        assertEquals(listOf(PairStart.Failure.ClosedBeforeCode), retried)
    }

    @Test
    fun `a television that keeps hanging up gets one second try, not a loop`() = runBlocking {
        var attempts = 0
        val error = assertFailsWith<ProtocolException> {
            PairStart.withRetry<String>(
                attempt = {
                    attempts++
                    throw ProtocolException("${CompanionSession.NO_ANSWER}PairSetupStart")
                },
                pause = { },
            )
        }
        assertEquals(1 + PairStart.RETRIES, attempts)
        assertEquals(PairStart.Failure.NoAnswer, PairStart.classify(error))
    }

    @Test
    fun `a refusal is not retried`() = runBlocking {
        var attempts = 0
        assertFailsWith<AuthenticationException> {
            PairStart.withRetry<String>(
                attempt = {
                    attempts++
                    throw AuthenticationException("device has reached its maximum number of paired controllers")
                },
                pause = { },
            )
        }
        assertEquals(1, attempts)
    }

    @Test
    fun `cancelling is never retried`() = runBlocking {
        var attempts = 0
        assertFailsWith<CancellationException> {
            PairStart.withRetry<String>(
                attempt = {
                    attempts++
                    throw CancellationException("Cancel on the keypad")
                },
                pause = { },
            )
        }
        assertEquals(1, attempts)
    }

    @Test
    fun `a lost code is asked for again once, and only with the screen up`() {
        assertTrue(PairStart.restartAfterCodeLost(restartsSoFar = 0, onScreen = true))
        assertFalse(PairStart.restartAfterCodeLost(restartsSoFar = 1, onScreen = true))
        // A code put up for a phone in a pocket is a code nobody asked for.
        assertFalse(PairStart.restartAfterCodeLost(restartsSoFar = 0, onScreen = false))
    }

    @Test
    fun `the explanation names what happened, and only for the television's failures`() {
        assertNotNull(PairStart.explain(ProtocolException(CompanionSession.CLOSED_BY_TV)))
        assertNotNull(PairStart.explain(ProtocolException("${CompanionSession.NO_ANSWER}PairSetupStart")))
        assertNull(PairStart.explain(java.net.ConnectException("ENETUNREACH")))
    }

    // ------------------------------------------------------------------ against the fake television

    /**
     * Regression guard for step one of the diagnosis: M1 is what pyatv sends, byte for byte.
     * pyatv: `{"_pd": write_tlv({Method: b"\x00", SeqNo: b"\x01"}), "_pwTy": 1}` plus `_x`.
     */
    @Test
    fun `M1 is byte for byte what pyatv sends`() {
        FakeAppleTv().use { tv ->
            tv.start()
            withClient { client -> client.startPairing("127.0.0.1", tv.port) }
            val m1 = synchronized(tv.authFrames) { tv.authFrames.first() }
            assertContentEquals(byteArrayOf(0x00, 0x01, 0x00, 0x06, 0x01, 0x01), m1["_pd"] as ByteArray)
            assertEquals(1L, m1["_pwTy"])
            assertEquals(setOf("_pd", "_pwTy", "_x"), m1.keys)
        }
    }

    /** light-reports#676 on the wire: M1 out, the socket closed, no M2. Then one more try. */
    @Test
    fun `a television that hangs up on the first M1 is asked once more and pairs`() {
        FakeAppleTv(hangUpsOnSetupStart = 1).use { tv ->
            tv.start()
            val retried = mutableListOf<PairStart.Failure>()
            val credentials = withClient { client ->
                val setup = PairStart.withRetry(
                    attempt = { client.startPairing("127.0.0.1", tv.port) },
                    pause = { },
                    onRetry = { failure, _ -> retried += failure },
                )
                client.finishPairing(setup, "1234")
            }
            tv.failure?.let { throw AssertionError("device rejected the handshake", it) }
            assertEquals(listOf(PairStart.Failure.ClosedBeforeCode), retried)
            assertEquals(2, tv.connections, "the second try is a fresh socket")
            assertTrue(credentials.devicePublicKey.contentEquals(tv.devicePublicKey))
        }
    }

    @Test
    fun `a television that hangs up on every M1 is given up on after the second`() {
        FakeAppleTv(hangUpsOnSetupStart = 5).use { tv ->
            tv.start()
            withClient { client ->
                val error = assertFailsWith<Exception> {
                    PairStart.withRetry(
                        attempt = { client.startPairing("127.0.0.1", tv.port) },
                        pause = { },
                    )
                }
                assertEquals(PairStart.Failure.ClosedBeforeCode, PairStart.classify(error))
            }
            assertEquals(2, tv.connections)
        }
    }

    /**
     * light-reports#631 and #657: M2 arrives, the code is up, and the television hangs up. That has
     * to reach the pairing, and must not look like the remote losing its connection — which is what
     * filed these as drops and sent the app off reconnecting to another television mid-pairing.
     */
    @Test
    fun `a pairing the television abandons is a pairing drop, not a lost remote`() {
        FakeAppleTv(hangUpAfterCode = true).use { tv ->
            tv.start()
            withClient { client ->
                val lostRemote = CompletableDeferred<Throwable?>()
                val pairingDropped = CompletableDeferred<Throwable>()
                client.onDisconnected = { lostRemote.complete(it) }
                client.onPairingDropped = { pairingDropped.complete(it) }

                client.startPairing("127.0.0.1", tv.port)

                withTimeout(5_000) { pairingDropped.await() }
                assertFalse(lostRemote.isCompleted, "a pairing socket is not the remote's link")
                assertFalse(client.isConnected)
            }
        }
    }

    /** And our own close of a pairing socket — Cancel, or the end of a pairing — is silent. */
    @Test
    fun `closing a pairing ourselves reports nothing`() {
        FakeAppleTv().use { tv ->
            tv.start()
            withClient { client ->
                val lostRemote = CompletableDeferred<Throwable?>()
                val pairingDropped = CompletableDeferred<Throwable>()
                client.onDisconnected = { lostRemote.complete(it) }
                client.onPairingDropped = { pairingDropped.complete(it) }

                val setup = client.startPairing("127.0.0.1", tv.port)
                client.finishPairing(setup, "1234")
                Thread.sleep(300)

                assertFalse(pairingDropped.isCompleted)
                assertFalse(lostRemote.isCompleted)
            }
        }
    }
}
