package com.gios.lightremote

import com.gios.lightremote.hw.NotchGuard
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The wheel guard, which is the only part of `hw/Wheel.kt` a JVM test can reach.
 *
 * Everything else in that file is a Composable driving a `SharedFlow` against a real sensor, and
 * the two rules in here — two notches to start, two against to turn back — read as obviously
 * right and then behave differently in the hand, which is how the stray-notch bug survived a
 * release. Driving the guard over a synthetic clock is the cheap half of that, and it runs in the
 * sandbox where the Android toolchain does not.
 */
class NotchGuardTest {

    /** A spin of [count] notches one way, 40 ms apart — the sensor's real rate. */
    private fun NotchGuard.spin(count: Int, direction: Int, from: Long): List<Int> {
        val out = mutableListOf<Int>()
        for (i in 0 until count) {
            val emit = accept(direction, from + i * 40L)
            if (emit != 0) out.add(emit)
        }
        return out
    }

    @Test
    fun `one notch on its own does nothing`() {
        assertEquals(emptyList<Int>(), NotchGuard().spin(1, 1, from = 1_000))
    }

    @Test
    fun `two notches arm the wheel and neither is lost`() {
        assertEquals(listOf(2), NotchGuard().spin(2, 1, from = 1_000))
    }

    @Test
    fun `a spin releases the held pair and then every notch`() {
        assertEquals(listOf(2, 1, 1, 1, 1), NotchGuard().spin(6, 1, from = 1_000))
    }

    @Test
    fun `notches either side of the idle window do not add up`() {
        val guard = NotchGuard()
        assertEquals(0, guard.accept(1, 1_000))
        // Three seconds later the guard has re-armed, so this is a first notch again.
        assertEquals(0, guard.accept(1, 4_000))
    }

    @Test
    fun `a lone stray notch mid-spin is swallowed`() {
        val guard = NotchGuard()
        val out = mutableListOf<Int>()
        // Eight notches up with the sixth coming back inverted, which is what the optical
        // sensor actually does a few percent of the time.
        val notches = listOf(1, 1, 1, 1, 1, -1, 1, 1)
        notches.forEachIndexed { i, n ->
            val emit = guard.accept(n, 1_000 + i * 40L)
            if (emit != 0) out.add(emit)
        }
        assertTrue(out.none { it < 0 }, "the stray notch reached the handler: $out")
        assertEquals(listOf(2, 1, 1, 1, 1, 1), out)
    }

    @Test
    fun `two strays in one spin are both swallowed`() {
        val guard = NotchGuard()
        val out = mutableListOf<Int>()
        val notches = listOf(1, 1, 1, 1, -1, 1, 1, -1, 1, 1)
        notches.forEachIndexed { i, n ->
            val emit = guard.accept(n, 1_000 + i * 40L)
            if (emit != 0) out.add(emit)
        }
        assertTrue(out.none { it < 0 }, "a stray notch reached the handler: $out")
    }

    @Test
    fun `two strays back to back are also swallowed`() {
        // The reason REVERSE_NOTCHES is three and not two. Strays cluster where the surface is
        // barely moving, so a pair of them in a row is ordinary — and at a threshold of two, one
        // would be handed through *and* would flip the direction being protected, so the next
        // real notch would be swallowed in its place.
        val guard = NotchGuard()
        val out = mutableListOf<Int>()
        val notches = listOf(1, 1, 1, -1, -1, 1, 1, 1)
        notches.forEachIndexed { i, n ->
            val emit = guard.accept(n, 1_000 + i * 40L)
            if (emit != 0) out.add(emit)
        }
        assertTrue(out.none { it < 0 }, "a stray notch reached the handler: $out")
        // And the notches after them are not eaten either.
        assertEquals(listOf(2, 1, 1, 1, 1), out)
    }

    @Test
    fun `a deliberate reversal commits on the third notch against`() {
        val guard = NotchGuard()
        val out = mutableListOf<Int>()
        // Four up, then five genuinely back down, all inside one turn.
        val notches = listOf(1, 1, 1, 1, -1, -1, -1, -1, -1)
        notches.forEachIndexed { i, n ->
            val emit = guard.accept(n, 1_000 + i * 40L)
            if (emit != 0) out.add(emit)
        }
        // Arms on 2, two more up, two down spent proving it, then the rest go through.
        assertEquals(listOf(2, 1, 1, -1, -1, -1), out)
    }

    @Test
    fun `a new turn is not fought for going the other way`() {
        val guard = NotchGuard()
        assertEquals(listOf(2, 1), guard.spin(3, 1, from = 1_000))
        // A pause long enough to reset, then two notches down: the guard must not remember the
        // previous turn's direction, or the first notch of every fresh turn back would vanish.
        assertEquals(listOf(-2), guard.spin(2, -1, from = 5_000))
    }
}
