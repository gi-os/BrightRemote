package com.gios.lightremote.companion

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Asking the television for a pairing code, with one second chance.
 *
 * Pair-setup opens with M1 — the method and sequence TLV, `_pwTy` 1, byte for byte what pyatv
 * sends — and the television answers with M2, its salt and public key, while it draws the code. In
 * every trace that worked, M2 came back inside a third of a second. The four Pair-screen reports
 * that did not are two different failures:
 *
 * - **No M2 at all.** light-reports#676: the socket took 833 ms to open, M1 went out, and the
 *   television closed the connection without a word. #590: three M1s over thirteen seconds, none
 *   answered, the last one waited out the full twenty-second timeout. Nothing was on the screen to
 *   lose, and a television that was asleep, or still tearing down the previous attempt, answers
 *   the next try. So the first M1 that is hung up on or ignored gets exactly one more, on a fresh
 *   socket, after a short pause — and the wait for M2 is eight seconds, not twenty: a television
 *   that has not answered in eight is not going to answer in twenty (pyatv waits five).
 *
 * - **M2, then the television hung up while the code was up.** #631 and #657 (the second one after
 *   two and a half minutes with no code typed). Before, the keypad stayed up wired to a dead
 *   socket, and a code typed into it waited twenty seconds for an answer that could not come.
 *   Now the view model hears about it ([CompanionClient.onPairingDropped]) and, while the Pair
 *   screen is still in front of somebody, asks for a new code once ([restartAfterCodeLost]).
 *
 * Everything else — a refusal the television spelled out, an address that does not answer at all —
 * fails at once, as before: a second try cannot change either.
 *
 * Plain Kotlin, no Android, so the rules and the retry loop are tested on the JVM.
 */
object PairStart {

    /** How long to wait for M2. See the class comment for why eight seconds and not twenty. */
    const val START_TIMEOUT_MS = 8_000L

    /** Extra M1 attempts after the first, for a failure a second try can fix. */
    const val RETRIES = 1

    /** Lets a television that just hung up finish with that socket before the next one arrives. */
    const val PAUSE_MS = 1_500L

    /** New codes asked for, per visit to the Pair screen, after the television took one away. */
    const val CODE_RESTARTS = 1

    enum class Failure {
        /** The socket ended before M2: an EOF, a reset, or a send into a socket already gone. */
        ClosedBeforeCode,

        /** M2 never came inside [START_TIMEOUT_MS]. */
        NoAnswer,

        /** Nothing answered the TCP connect at all. */
        Unreachable,

        /** The television answered, with a HAP error or with something that cannot be used. */
        Refused,

        Other,
    }

    fun classify(error: Throwable): Failure = when {
        error is java.net.ConnectException ||
            error is java.net.SocketTimeoutException ||
            error is java.net.NoRouteToHostException ||
            error is java.net.UnknownHostException -> Failure.Unreachable
        error is AuthenticationException -> Failure.Refused
        error is ProtocolException && error.message?.startsWith(CompanionSession.NO_ANSWER) == true ->
            Failure.NoAnswer
        error is ProtocolException && error.message == CompanionSession.CLOSED_BY_TV ->
            Failure.ClosedBeforeCode
        error is IllegalStateException && error.message == CompanionConnection.NOT_CONNECTED ->
            Failure.ClosedBeforeCode
        // A reset or an abort while waiting for M2 is the same thing said less politely.
        error is java.io.IOException -> Failure.ClosedBeforeCode
        else -> Failure.Other
    }

    fun worthRetrying(failure: Failure): Boolean =
        failure == Failure.ClosedBeforeCode || failure == Failure.NoAnswer

    /**
     * Run [attempt], and run it once more if it fails in a way a second try can fix.
     *
     * Cancellation is never retried and never swallowed: it is how Cancel on the keypad stops this.
     */
    suspend fun <T> withRetry(
        attempt: suspend () -> T,
        pause: suspend (Long) -> Unit = { delay(it) },
        onRetry: (Failure, Throwable) -> Unit = { _, _ -> },
    ): T {
        var retriesLeft = RETRIES
        while (true) {
            try {
                return attempt()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val failure = classify(e)
                if (retriesLeft <= 0 || !worthRetrying(failure)) throw e
                retriesLeft--
                onRetry(failure, e)
                pause(PAUSE_MS)
            }
        }
    }

    /**
     * Whether to ask for a new code after the television hung up while one was on its screen.
     *
     * Once per visit, and only while the Pair screen is actually in front of somebody: a code put up
     * for a phone left in a pocket is a code nobody asked for.
     */
    fun restartAfterCodeLost(restartsSoFar: Int, onScreen: Boolean): Boolean =
        onScreen && restartsSoFar < CODE_RESTARTS

    /** The sentence for a pairing that could not get a code, or null to use the generic one. */
    fun explain(error: Throwable): String? = when (classify(error)) {
        Failure.ClosedBeforeCode ->
            "The Apple TV hung up before showing a code. Check that it is awake, then tap it again."
        Failure.NoAnswer ->
            "The Apple TV did not answer the pairing request. Check that it is awake, then tap it again."
        else -> null
    }
}
