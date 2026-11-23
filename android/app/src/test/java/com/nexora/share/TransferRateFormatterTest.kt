package com.nexora.share

import org.junit.Assert.assertEquals
import org.junit.Test

class TransferRateFormatterTest {
    @Test
    fun formatsBytesPerSecond() {
        assertEquals("0 B/s", TransferRateFormatter.format(0L))
        assertEquals("900 B/s", TransferRateFormatter.format(900L))
    }

    @Test
    fun formatsKilobytesPerSecond() {
        assertEquals("1 KB/s", TransferRateFormatter.format(1024L))
        assertEquals("2 KB/s", TransferRateFormatter.format(2048L))
    }

    @Test
    fun formatsMegabytesPerSecond() {
        assertEquals("1.0 MB/s", TransferRateFormatter.format(1024L * 1024L))
        assertEquals("1.5 MB/s", TransferRateFormatter.format(1536L * 1024L))
    }

    @Test
    fun clampsNegativeRatesToZero() {
        assertEquals("0 B/s", TransferRateFormatter.format(-500L))
    }
}
