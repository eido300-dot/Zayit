package io.github.kdroidfilter.seforimapp.framework.portable

import com.russhwolf.settings.Settings

/**
 * Makes typed reads tolerant of bad stored values.
 *
 * `PropertiesSettings` parses with `toInt()` and friends, which throw on a malformed value, and turns
 * any non-`true` boolean into `false`. The Preferences-backed store it replaces returns the default
 * instead. `AppSettings` reads several values while it initializes, so a single bad value would
 * otherwise stop the app from starting. `PropertiesSettings` is final, hence delegation.
 */
class LenientSettings(
    private val delegate: Settings,
) : Settings by delegate {
    override fun getInt(
        key: String,
        defaultValue: Int,
    ): Int = getIntOrNull(key) ?: defaultValue

    override fun getIntOrNull(key: String): Int? = delegate.getStringOrNull(key)?.toIntOrNull()

    override fun getLong(
        key: String,
        defaultValue: Long,
    ): Long = getLongOrNull(key) ?: defaultValue

    override fun getLongOrNull(key: String): Long? = delegate.getStringOrNull(key)?.toLongOrNull()

    override fun getFloat(
        key: String,
        defaultValue: Float,
    ): Float = getFloatOrNull(key) ?: defaultValue

    override fun getFloatOrNull(key: String): Float? = delegate.getStringOrNull(key)?.toFloatOrNull()

    override fun getDouble(
        key: String,
        defaultValue: Double,
    ): Double = getDoubleOrNull(key) ?: defaultValue

    override fun getDoubleOrNull(key: String): Double? = delegate.getStringOrNull(key)?.toDoubleOrNull()

    override fun getBoolean(
        key: String,
        defaultValue: Boolean,
    ): Boolean = getBooleanOrNull(key) ?: defaultValue

    override fun getBooleanOrNull(key: String): Boolean? = delegate.getStringOrNull(key)?.toBooleanStrictOrNull()
}
