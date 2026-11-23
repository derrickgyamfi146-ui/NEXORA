package com.nexora.share

internal object TransferRateFormatter {
    fun format(bytesPerSecond: Long): String {
        val safeRate = bytesPerSecond.coerceAtLeast(0L)
        val rate = safeRate.toDouble()
        return when {
            rate >= 1024.0 * 1024.0 ->
                String.format(java.util.Locale.US, "%.1f MB/s", rate / (1024.0 * 1024.0))
            rate >= 1024.0 ->
                String.format(java.util.Locale.US, "%.0f KB/s", rate / 1024.0)
            else -> "$safeRate B/s"
        }
    }
}
