package io.github.kdroidfilter.seforimapp.features.onboarding.diskspace

import dev.nucleusframework.systeminfo.SystemInfo
import io.github.kdroidfilter.seforimapp.framework.portable.PortableEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AvailableDiskSpaceUseCase {
    /**
     * Reads available and total disk space via Nucleus SystemInfo.
     * Must be called from a coroutine — dispatched to IO internally.
     */
    suspend fun getDiskSpaceInfo(): DiskSpaceInfo =
        withContext(Dispatchers.IO) {
            val disks = SystemInfo.disks()

            // Portable: the database goes to the drive holding the data dir, so measure that one
            // (the longest mount point containing it, e.g. a USB stick mounted under /media).
            val portableDir =
                PortableEnvironment.layout
                    ?.dataDir
                    ?.toAbsolutePath()
                    ?.toString()
            val portableDisk =
                portableDir?.let { dir ->
                    disks
                        .filter { it.mountPoint.isNotEmpty() && dir.startsWith(it.mountPoint, ignoreCase = true) }
                        .maxByOrNull { it.mountPoint.length }
                }

            val systemDir =
                portableDisk ?: disks.firstOrNull {
                    it.mountPoint.contains(System.getProperty("user.home")) ||
                        it.mountPoint == "/" ||
                        it.mountPoint.startsWith("C:")
                } ?: disks.first()

            DiskSpaceInfo(
                availableBytes = systemDir.availableSpace,
                totalBytes = systemDir.totalSpace,
            )
        }

    data class DiskSpaceInfo(
        val availableBytes: Long,
        val totalBytes: Long,
    ) {
        val hasEnoughSpace: Boolean get() = availableBytes >= REQUIRED_SPACE_BYTES
        val remainingAfterInstall: Long get() = availableBytes - REQUIRED_SPACE_BYTES
    }

    companion object {
        /** Total space required during installation (includes temporary files). */
        const val REQUIRED_SPACE_GB = 10L

        /** Temporary space needed only during installation (will be freed after). */
        const val TEMPORARY_SPACE_GB = 2.5

        /** Final space after installation completes. */
        const val FINAL_SPACE_GB = 7.5

        val REQUIRED_SPACE_BYTES = REQUIRED_SPACE_GB * 1024 * 1024 * 1024
    }
}
