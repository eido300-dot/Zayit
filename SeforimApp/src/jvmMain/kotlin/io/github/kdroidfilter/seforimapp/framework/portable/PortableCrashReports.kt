package io.github.kdroidfilter.seforimapp.framework.portable

import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import java.nio.file.InvalidPathException
import java.nio.file.Path

private const val DATA_PLACEHOLDER = "<zayit-data>"
private const val DRIVE_PLACEHOLDER = "<drive>"
private const val HOME_PLACEHOLDER = "~"

/**
 * Keeps crash reports from a portable copy free of the computer's and the drive's names. A drive
 * moves between computers, often shared ones, so the report must not carry the host name, the
 * user's home folder (it holds the account name) or the drive's location: the data folder, the
 * folder next to it that holds the program, and the drive's own name. Every text the report can
 * carry is cleaned: the message, exceptions and stack frames, extras (where the logger puts its
 * message), tags, and breadcrumbs with their data.
 */
internal fun keepPortableReportsAnonymous(
    options: SentryOptions,
    dataDir: String,
    home: String?,
) {
    val container = containerOfDataDir(dataDir)
    val scrub: (String) -> String = { text -> scrubPaths(text, dataDir, home, container).orEmpty() }
    options.isAttachServerName = false
    options.beforeSend =
        SentryOptions.BeforeSendCallback { event, _ ->
            event.also { anonymize(it, scrub) }
        }
    options.beforeBreadcrumb =
        SentryOptions.BeforeBreadcrumbCallback { breadcrumb, _ ->
            breadcrumb.also { anonymize(it, scrub) }
        }
}

private fun containerOfDataDir(dataDir: String): String? =
    try {
        Path.of(dataDir).parent?.toString()
    } catch (_: InvalidPathException) {
        null
    }

private fun anonymize(
    event: SentryEvent,
    scrub: (String) -> String,
) {
    event.serverName = null
    event.message?.let { message ->
        message.message = message.message?.let(scrub)
        message.formatted = message.formatted?.let(scrub)
        message.params = message.params?.map(scrub)
    }
    event.exceptions?.forEach { exception ->
        exception.value = exception.value?.let(scrub)
        exception.stacktrace?.frames?.forEach { frame -> frame.absPath = frame.absPath?.let(scrub) }
    }
    // The logger puts its rendered message in an extra, and it goes through the same scrubbing.
    event.extras
        ?.entries
        ?.toList()
        ?.forEach { (key, value) -> event.setExtra(key, scrubValue(value, scrub)) }
    event.tags
        ?.entries
        ?.toList()
        ?.forEach { (key, value) -> event.setTag(key, scrub(value)) }
    event.breadcrumbs?.forEach { anonymize(it, scrub) }
}

private fun anonymize(
    breadcrumb: Breadcrumb,
    scrub: (String) -> String,
) {
    breadcrumb.message = breadcrumb.message?.let(scrub)
    breadcrumb.data.entries
        .toList()
        .forEach { (key, value) -> breadcrumb.setData(key, scrubValue(value, scrub)) }
}

/** Cleans the text inside [value], including inside maps and collections; other values are kept. */
private fun scrubValue(
    value: Any,
    scrub: (String) -> String,
): Any =
    when (value) {
        is String -> scrub(value)
        is Map<*, *> -> value.entries.associate { (key, inner) -> key to (inner?.let { scrubValue(it, scrub) }) }
        is Collection<*> -> value.map { inner -> inner?.let { scrubValue(it, scrub) } }
        else -> value
    }

/**
 * Replaces the data folder, then the folder that holds it and the program ([container]), then the
 * home folder, in [text]. The data folder can lie under home, so the longest path goes first.
 * Matching ignores case and treats `/` and `\` alike, since the same path is written both ways on
 * Windows; a path only matches whole names, so `/home/moshe` does not match `/home/moshe2`.
 */
internal fun scrubPaths(
    text: String?,
    dataDir: String,
    home: String?,
    container: String? = null,
): String? {
    if (text == null) return null
    return listOf(dataDir to DATA_PLACEHOLDER, container to DRIVE_PLACEHOLDER, home to HOME_PLACEHOLDER)
        .fold(text) { current, (path, placeholder) -> replacePath(current, path, placeholder) }
}

private fun replacePath(
    text: String,
    path: String?,
    placeholder: String,
): String {
    val names = path?.split('/', '\\')?.filter { it.isNotEmpty() }.orEmpty()
    // The root itself ("/" or "C:\") is no name worth hiding, and would rewrite every separator.
    if (names.isEmpty()) return text
    val leadingSeparator = if (path?.firstOrNull() == '/' || path?.firstOrNull() == '\\') "[\\\\/]*" else ""
    val body = names.joinToString("[\\\\/]+") { Regex.escape(it) }
    val regex = Regex(leadingSeparator + body + "(?![\\p{L}\\p{N}_-])", RegexOption.IGNORE_CASE)
    return regex.replace(text, Regex.escapeReplacement(placeholder))
}
