package io.github.kdroidfilter.seforimapp.framework.portable

import io.sentry.Breadcrumb
import io.sentry.Hint
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.protocol.Message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PortableCrashReportsTest {
    @Test
    fun `drive and home folders are removed from report text`() {
        val text = "cannot read /media/moshe/STICK/zayit-data/settings.properties (see /home/moshe/log)"

        assertEquals(
            "cannot read <zayit-data>/settings.properties (see ~/log)",
            scrubPaths(text, dataDir = "/media/moshe/STICK/zayit-data", home = "/home/moshe"),
        )
    }

    @Test
    fun `data folder under home is replaced as a whole`() {
        assertEquals(
            "<zayit-data>/x",
            scrubPaths("/home/moshe/zayit-data/x", dataDir = "/home/moshe/zayit-data", home = "/home/moshe"),
        )
    }

    @Test
    fun `missing text or home is handled`() {
        assertNull(scrubPaths(null, dataDir = "/d", home = "/h"))
        assertEquals("/h/x", scrubPaths("/h/x", dataDir = "", home = null))
    }

    @Test
    fun `either slash and any case match, and only whole names`() {
        assertEquals(
            "<zayit-data>\\x and ~/y and /home/moshe2/z",
            scrubPaths(
                "e:\\usb\\Zayit-Data\\x and /HOME/moshe/y and /home/moshe2/z",
                dataDir = "E:/usb/zayit-data",
                home = "/home/moshe",
            ),
        )
    }

    @Test
    fun `the folder holding the program and the drive name are replaced`() {
        assertEquals(
            "<drive>/Zayit/zayit failed; <zayit-data>/db",
            scrubPaths(
                "/media/moshe/MY STICK/Zayit/zayit failed; /media/moshe/MY STICK/zayit-data/db",
                dataDir = "/media/moshe/MY STICK/zayit-data",
                home = "/home/moshe",
                container = "/media/moshe/MY STICK",
            ),
        )
    }

    @Test
    fun `a root home folder does not rewrite every separator`() {
        assertEquals("/a/b", scrubPaths("/a/b", dataDir = "", home = "/"))
    }

    @Test
    fun `extras, tags, message parameters and breadcrumbs are cleaned in the event`() {
        val options = SentryOptions()
        keepPortableReportsAnonymous(options, dataDir = "/media/moshe/STICK/zayit-data", home = "/home/moshe")
        val event =
            SentryEvent().apply {
                serverName = "moshes-pc"
                setExtra("logger.message", "cannot read /media/moshe/STICK/zayit-data/x and /home/moshe/y")
                setExtra("nested", mapOf("path" to "/home/moshe/z", "count" to 3))
                setExtra("count", 5)
                setTag("where", "/media/moshe/STICK/Zayit")
                message = Message().apply { params = listOf("/home/moshe/p") }
                addBreadcrumb(
                    Breadcrumb().apply {
                        message = "opened /home/moshe/b"
                        setData("file", "/media/moshe/STICK/zayit-data/c")
                    },
                )
            }

        val cleaned = assertNotNull(options.beforeSend?.execute(event, Hint()))

        assertNull(cleaned.serverName)
        assertEquals("cannot read <zayit-data>/x and ~/y", cleaned.extras?.get("logger.message"))
        assertEquals(mapOf("path" to "~/z", "count" to 3), cleaned.extras?.get("nested"))
        assertEquals(5, cleaned.extras?.get("count"))
        assertEquals("<drive>/Zayit", cleaned.tags?.get("where"))
        assertEquals(listOf("~/p"), cleaned.message?.params)
        val breadcrumb = assertNotNull(cleaned.breadcrumbs?.single())
        assertEquals("opened ~/b", breadcrumb.message)
        assertEquals("<zayit-data>/c", breadcrumb.data["file"])
    }

    @Test
    fun `a breadcrumb is cleaned before it is recorded`() {
        val options = SentryOptions()
        keepPortableReportsAnonymous(options, dataDir = "/d/zayit-data", home = null)
        val breadcrumb = Breadcrumb().apply { message = "/d/zayit-data/x" }

        assertEquals("<zayit-data>/x", options.beforeBreadcrumb?.execute(breadcrumb, Hint())?.message)
    }
}
