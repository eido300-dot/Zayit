package io.github.kdroidfilter.seforimapp.framework.portable

/** Backoff budget for [moveWithRetry]. */
data class RetryPolicy(
    val maxTotalMillis: Long,
    val initialDelayMillis: Long,
    val maxDelayMillis: Long,
) {
    companion object {
        /** Settings saves: kept short so a stuck save never blocks the writer thread for long. */
        val SETTINGS = RetryPolicy(maxTotalMillis = 2_000, initialDelayMillis = 10, maxDelayMillis = 100)

        /** Final rename of a freshly copied program folder, which antivirus scanners often hold open. */
        val INSTALL = RetryPolicy(maxTotalMillis = 60_000, initialDelayMillis = 50, maxDelayMillis = 1_000)
    }
}
