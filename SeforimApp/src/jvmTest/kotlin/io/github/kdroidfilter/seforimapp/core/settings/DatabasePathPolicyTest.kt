package io.github.kdroidfilter.seforimapp.core.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DatabasePathPolicyTest {
    @Test
    fun `host mode returns the stored path and treats blank as none`() {
        assertEquals("/data/seforim.db", effectiveStoredDatabasePath("/data/seforim.db", isPortable = false))
        assertNull(effectiveStoredDatabasePath("", isPortable = false))
        assertNull(effectiveStoredDatabasePath("   ", isPortable = false))
        assertNull(effectiveStoredDatabasePath(null, isPortable = false))
    }

    @Test
    fun `portable mode ignores any stored path`() {
        assertNull(effectiveStoredDatabasePath("E:\\zayit-data\\databases\\seforim.db", isPortable = true))
    }

    @Test
    fun `host mode stores the path and portable mode stores nothing`() {
        assertEquals("/data/seforim.db", databasePathToStore("/data/seforim.db", isPortable = false))
        assertEquals("", databasePathToStore(null, isPortable = false))
        assertEquals("", databasePathToStore("/media/usb/zayit-data/databases/seforim.db", isPortable = true))
    }
}
