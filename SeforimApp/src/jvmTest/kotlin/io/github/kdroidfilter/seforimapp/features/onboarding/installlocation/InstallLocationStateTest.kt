package io.github.kdroidfilter.seforimapp.features.onboarding.installlocation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class InstallLocationStateTest {
    @Test
    fun `progress is a whole percent, clamped to 0 to 100`() {
        assertEquals(0, percentOf(0, 4))
        assertEquals(25, percentOf(1, 4))
        assertEquals(33, percentOf(1, 3))
        assertEquals(100, percentOf(4, 4))
        assertEquals(100, percentOf(5, 4))
        assertEquals(0, percentOf(-1, 4))
    }

    @Test
    fun `an empty program counts as copied`() {
        assertEquals(100, percentOf(0, 0))
    }

    @Test
    fun `large programs do not overflow the percent`() {
        val program = 400L * 1024 * 1024 * 1024
        assertEquals(50, percentOf(program / 2, program))
        assertEquals(100, percentOf(program, program))
    }

    @Test
    fun `each checked folder offers the one step it allows`() {
        assertEquals(InstallLocationEvents.StartCopy, primaryActionOf(TargetCheck.Ok(free = 2, required = 1))?.second)
        assertEquals(InstallLocationEvents.UpdateExisting, primaryActionOf(TargetCheck.ExistingPortable("/media/usb/Zayit"))?.second)
        assertEquals(
            InstallLocationEvents.DeleteStalePartial,
            primaryActionOf(TargetCheck.StalePartial("/media/usb/Zayit.partial"))?.second,
        )
    }

    @Test
    fun `a folder that cannot take a copy offers only picking another one`() {
        listOf(
            TargetCheck.UnsupportedFileSystem("vfat"),
            TargetCheck.NotEnoughSpace(free = 1, required = 2),
            TargetCheck.NotWritable,
            TargetCheck.InsideProgramDir,
            TargetCheck.PathTooLong,
            TargetCheck.AlreadyExists("/media/usb/Zayit"),
            TargetCheck.Unavailable,
        ).forEach { check -> assertNull(primaryActionOf(check), check.toString()) }
    }

    @Test
    fun `picked folders are compared by path`() {
        assertEquals(InstallLocationEvents.FolderPicked("/media/usb"), InstallLocationEvents.FolderPicked("/media/usb"))
        assertNotEquals(InstallLocationEvents.FolderPicked("/media/usb"), InstallLocationEvents.FolderPicked("/media/other"))
    }
}
