package io.github.kdroidfilter.seforimapp.features.onboarding.installlocation

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InstallTargetsTest {
    @Test
    fun `fat file systems are refused whatever their spelling`() {
        listOf("fat", "FAT", "fat12", "fat16", "FAT32", "vfat", "msdos").forEach { type ->
            assertFalse(isFileSystemSupported(type, "/media/usb"), type)
        }
    }

    @Test
    fun `network file systems are refused`() {
        listOf("nfs", "nfs4", "cifs", "smb2", "smbfs", "fuse.sshfs", "9p", "afpfs", "webdav").forEach { type ->
            assertFalse(isFileSystemSupported(type, "/mnt/share"), type)
        }
    }

    @Test
    fun `drive file systems are accepted, including fuseblk used by exFAT and NTFS on Linux`() {
        listOf("exfat", "exFAT", "ntfs3", "NTFS", "fuseblk", "ext4", "apfs", "hfs").forEach { type ->
            assertTrue(isFileSystemSupported(type, "/media/usb"), type)
        }
    }

    @Test
    fun `a UNC path is refused even when the share reports NTFS`() {
        assertFalse(isFileSystemSupported("NTFS", """\\server\share\folder"""))
        assertTrue(isUncPath("//server/share"))
        assertFalse(isUncPath("""E:\"""))
    }

    @Test
    fun `only long Windows paths are too long`() {
        val long = "E:\\" + "a".repeat(MAX_WINDOWS_TARGET_CHARS)
        assertTrue(isPathTooLong(long, "\\"))
        assertFalse(isPathTooLong("E:\\Zayit", "\\"))
        assertFalse(isPathTooLong("/" + "a".repeat(MAX_WINDOWS_TARGET_CHARS * 2), "/"))
    }

    @Test
    fun `the Windows uninstaller is recognized by name`() {
        assertTrue(isUninstaller("Uninstall \u05D6\u05D9\u05EA.exe"))
        assertTrue(isUninstaller("uninstall Zayit.EXE"))
        assertFalse(isUninstaller("zayit.exe"))
        assertFalse(isUninstaller("Uninstall.dll"))
    }

    @Test
    fun `a Windows or Linux program folder is copied as the Zayit folder`() {
        val plan = assertNotNull(planInstall(Path.of("/opt/zayit/zayit"), Path.of("/media/usb")))

        assertEquals(Path.of("/opt/zayit"), plan.source)
        assertEquals(Path.of("/media/usb/Zayit"), plan.destination)
        assertEquals(Path.of("/media/usb/Zayit.partial"), plan.staging)
        assertEquals(Path.of("/media/usb/Zayit.old"), plan.previous)
        assertEquals(plan.staging, plan.copyRoot)
        assertEquals(Path.of("zayit"), plan.relativeExecutable)
    }

    @Test
    fun `a macOS bundle is copied into the Zayit folder, next to where the data folder goes`() {
        val exe = Path.of("/Applications/\u05D6\u05D9\u05EA.app/Contents/MacOS/zayit")

        val plan = assertNotNull(planInstall(exe, Path.of("/Volumes/USB")))

        assertEquals(Path.of("/Applications/\u05D6\u05D9\u05EA.app"), plan.source)
        assertEquals(Path.of("/Volumes/USB/Zayit.partial/\u05D6\u05D9\u05EA.app"), plan.copyRoot)
        assertEquals(Path.of("\u05D6\u05D9\u05EA.app/Contents/MacOS/zayit"), plan.relativeExecutable)
    }
}
