package io.github.kdroidfilter.seforimapp.framework.portable

import com.russhwolf.settings.PropertiesSettings
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LenientSettingsTest {
    private val properties = Properties()
    private val settings = LenientSettings(PropertiesSettings(properties))

    @Test
    fun `corrupt numbers fall back to the default instead of throwing`() {
        listOf("i", "l", "f", "d").forEach { properties.setProperty(it, "abc") }

        assertEquals(7, settings.getInt("i", 7))
        assertEquals(7L, settings.getLong("l", 7L))
        assertEquals(16f, settings.getFloat("f", 16f))
        assertEquals(1.5, settings.getDouble("d", 1.5))
        assertNull(settings.getIntOrNull("i"))
        assertNull(settings.getLongOrNull("l"))
        assertNull(settings.getFloatOrNull("f"))
        assertNull(settings.getDoubleOrNull("d"))
    }

    @Test
    fun `corrupt boolean falls back to the default`() {
        properties.setProperty("b", "maybe")

        assertEquals(true, settings.getBoolean("b", true))
        assertNull(settings.getBooleanOrNull("b"))
    }

    @Test
    fun `valid values written through the wrapper read back unchanged`() {
        settings.putInt("i", 3)
        settings.putLong("l", 4L)
        settings.putFloat("f", 2.5f)
        settings.putDouble("d", 0.25)
        settings.putBoolean("b", false)
        settings.putString("s", "x")

        assertEquals(3, settings.getInt("i", 0))
        assertEquals(4L, settings.getLong("l", 0L))
        assertEquals(2.5f, settings.getFloat("f", 0f))
        assertEquals(0.25, settings.getDouble("d", 0.0))
        assertEquals(false, settings.getBoolean("b", true))
        assertEquals("x", settings.getString("s", ""))
    }

    @Test
    fun `missing keys return defaults`() {
        assertEquals(5, settings.getInt("absent", 5))
        assertEquals(false, settings.getBoolean("absent", false))
    }
}
