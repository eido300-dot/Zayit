package io.github.kdroidfilter.seforimapp.framework.portable

/** Backoff budget for [moveWithRetry]. */
internal data class RetryPolicy(
    val maxTotalMillis: Long,
    val initialDelayMillis: Long,
    val maxDelayMillis: Long,
) {
    companion object {
        /** Settings saves: kept short so a stuck save never blocks the writer thread for long. */
        val SETTINGS = RetryPolicy(maxTotalMillis = 2_000, initialDelayMillis = 10, maxDelayMillis = 100)

        /** Renames while copying the program to a drive: a scanner may hold the fresh copy for a while. */
        val INSTALL = RetryPolicy(maxTotalMillis = 60_000, initialDelayMillis = 50, maxDelayMillis = 2_000)
    }
}
