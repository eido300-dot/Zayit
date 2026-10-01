package io.github.kdroidfilter.seforimapp.core.settings

/**
 * The books database path to use from the stored setting.
 *
 * In portable mode no custom path is honored: the database always lives in the managed
 * `zayit-data/databases` folder on the drive. A stored absolute path would carry the drive letter
 * of the first computer (`E:\…`) and point at nothing, or at another drive, on the next one.
 */
internal fun effectiveStoredDatabasePath(
    stored: String?,
    isPortable: Boolean,
): String? = if (isPortable) null else stored?.takeIf { it.isNotBlank() }

/** The value to store for [path]; portable mode never stores one (see [effectiveStoredDatabasePath]). */
internal fun databasePathToStore(
    path: String?,
    isPortable: Boolean,
): String = if (isPortable || path.isNullOrBlank()) "" else path
