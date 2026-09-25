package io.github.kdroidfilter.seforimapp.framework.portable

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs

class DriveLockTest {
    private val root: Path = createTempDirectory("zayit-drive-lock")
    private val held = mutableListOf<DriveLock>()

    @AfterTest
    fun cleanUp() {
        held.forEach { it.close() }
        root.toFile().deleteRecursively()
    }

    private fun acquire(dir: Path = root): DriveLock.Result =
        DriveLock.tryAcquire(dir).also {
            (it as? DriveLock.Result.Acquired)?.let { a ->
                held +=
                    a.lock
            }
        }

    @Test
    fun `first copy gets the lock and a second one finds the drive in use`() {
        assertIs<DriveLock.Result.Acquired>(acquire())

        assertIs<DriveLock.Result.InUse>(acquire())
    }

    @Test
    fun `lock is free again once released`() {
        val first = assertIs<DriveLock.Result.Acquired>(DriveLock.tryAcquire(root))
        first.lock.close()

        assertIs<DriveLock.Result.Acquired>(acquire())
    }

    @Test
    fun `folder that cannot hold the lock file does not block the app`() {
        val notAFolder = Files.createFile(root.resolve("file"))

        assertIs<DriveLock.Result.Unavailable>(acquire(notAFolder))
    }
}
