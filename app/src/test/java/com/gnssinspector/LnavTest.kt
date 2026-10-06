package com.gnssinspector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LnavTest {
    private val parityBits = arrayOf(
        intArrayOf(1, 2, 3, 5, 6, 10, 11, 12, 13, 14, 17, 18, 20, 23),
        intArrayOf(2, 3, 4, 6, 7, 11, 12, 13, 14, 15, 18, 19, 21, 24),
        intArrayOf(1, 3, 4, 5, 7, 8, 12, 13, 14, 15, 16, 19, 20, 22),
        intArrayOf(2, 4, 5, 6, 8, 9, 13, 14, 15, 16, 17, 20, 21, 23),
        intArrayOf(1, 3, 5, 6, 7, 9, 10, 14, 15, 16, 17, 18, 21, 22, 24),
        intArrayOf(3, 5, 6, 8, 9, 10, 11, 13, 15, 19, 22, 23, 24),
    )
    private val usesD29 = booleanArrayOf(true, false, true, false, false, true)

    /** Encodes 10 source data words the way a satellite transmits them (parity + D30* inversion). */
    private fun encode(data: IntArray, alreadyInverted: Boolean = true): ByteArray {
        val out = ByteArray(40)
        var prev = 0
        for (i in 0 until 10) {
            val d29 = (prev ushr 1) and 1
            val d30 = prev and 1
            var p = 0
            for (k in 0..5) {
                var b = if (usesD29[k]) d29 else d30
                for (bit in parityBits[k]) b = b xor ((data[i] ushr (24 - bit)) and 1)
                p = (p shl 1) or b
            }
            val tx = if (alreadyInverted && d30 == 1) data[i] xor 0xFFFFFF else data[i]
            val word = (tx shl 6) or p
            for (j in 0..3) out[4 * i + j] = (word ushr (24 - 8 * j)).toByte()
            prev = word
        }
        return out
    }

    private fun put(words: IntArray, word: Int, start: Int, len: Int, value: Long) {
        val shift = 24 - start - len + 1
        val mask = ((1L shl len) - 1)
        words[word - 1] = (words[word - 1] and (mask shl shift).toInt().inv()) or ((value and mask) shl shift).toInt()
    }

    private fun subframe2(): IntArray {
        val w = IntArray(10)
        put(w, 1, 1, 8, 0x8B)
        put(w, 2, 1, 17, 12345) // TOW
        put(w, 2, 20, 3, 2) // subframe 2
        put(w, 3, 1, 8, 77) // IODE
        put(w, 3, 9, 16, -1234) // Crs
        val m0 = -987654321L
        put(w, 4, 17, 8, m0 shr 24); put(w, 5, 1, 24, m0)
        val e = 0x00ABCDEFL // eccentricity raw
        put(w, 6, 17, 8, e shr 24); put(w, 7, 1, 24, e)
        val sqrtA = 2702438528L // ≈ 5154.6 m^½
        put(w, 8, 17, 8, sqrtA shr 24); put(w, 9, 1, 24, sqrtA)
        put(w, 10, 1, 16, 7200) // toe / 16
        return w
    }

    @Test
    fun decodesSubframe2FromTransmittedBits() {
        for (inverted in listOf(true, false)) {
            val f = LnavSubframe.parse(encode(subframe2(), inverted))!!
            assertEquals(10, f.parityOkWords)
            assertEquals(0x8B, f.preamble)
            assertEquals(12345, f.towCount)
            assertEquals(2, f.subframeId)
            val o = Lnav.orbit1(f)
            assertEquals(77, o.iode)
            assertEquals(-1234 / 32.0, o.crs, 1e-12)
            assertEquals(-987654321 * Math.scalb(1.0, -31) * GPS_PI, o.m0, 1e-12)
            assertEquals(0x00ABCDEF * Math.scalb(1.0, -33), o.e, 1e-15)
            assertEquals(2702438528.0 * Math.scalb(1.0, -19), o.sqrtA, 1e-9)
            assertEquals(7200 * 16.0, o.toe, 0.0)
        }
    }

    @Test
    fun corruptedWordFailsParity() {
        val raw = encode(subframe2())
        raw[17] = (raw[17].toInt() xor 0x10).toByte()
        assertTrue(LnavSubframe.parse(raw)!!.parityOkWords < 10)
    }

    @Test
    fun satellitePositionIsAtGpsAltitude() {
        val nav = GpsSatNav(
            prn = 1,
            clock = ClockData(0, 0, 0, 0, 77, 0, 0.0, 0.0, 0.0, 0.0, 0.0),
            orbit1 = OrbitPart1(77, 0.0, 0.0, 0.5, 0.0, 0.01, 0.0, 5153.6, 0.0, 0, 0),
            orbit2 = OrbitPart2(0.0, 1.0, 0.0, Math.toRadians(55.0), 0.0, 0.3, 0.0, 77, 0.0),
        )
        val pos = satellitePosition(nav, 1000.0)!!.ecef
        val r = Math.sqrt(pos[0] * pos[0] + pos[1] * pos[1] + pos[2] * pos[2])
        assertTrue("radius $r", r in 2.6e7..2.7e7)
    }
}
