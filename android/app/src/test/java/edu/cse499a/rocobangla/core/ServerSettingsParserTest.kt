package edu.cse499a.rocobangla.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerSettingsParserTest {

    @Test
    fun acceptsHttpsAndTrimsSlashes() {
        assertEquals(
            UrlCheck.Valid("https://abc-def.trycloudflare.com"),
            ServerSettingsParser.checkUrl("  https://abc-def.trycloudflare.com/// "),
        )
    }

    @Test
    fun refusesCleartextAndJunk() {
        assertEquals(UrlCheck.Invalid(UrlProblem.NotHttps), ServerSettingsParser.checkUrl("http://abc.trycloudflare.com"))
        assertEquals(UrlCheck.Invalid(UrlProblem.NotHttps), ServerSettingsParser.checkUrl("abc.trycloudflare.com"))
        assertEquals(UrlCheck.Invalid(UrlProblem.Empty), ServerSettingsParser.checkUrl("   "))
        assertEquals(UrlCheck.Invalid(UrlProblem.Malformed), ServerSettingsParser.checkUrl("https://"))
        assertEquals(UrlCheck.Invalid(UrlProblem.Malformed), ServerSettingsParser.checkUrl("https://a b.com"))
        assertEquals(UrlCheck.Invalid(UrlProblem.Malformed), ServerSettingsParser.checkUrl("https://a.com/?x=1"))
    }

    @Test
    fun parsesColabQrPayload() {
        val s = ServerSettingsParser.parseQr("""{"url":"https://abc.trycloudflare.com/","key":" k3y "}""")
        assertEquals(ServerSettings("https://abc.trycloudflare.com", "k3y"), s)
    }

    @Test
    fun rejectsBadQrPayloads() {
        assertNull(ServerSettingsParser.parseQr(null))
        assertNull(ServerSettingsParser.parseQr("https://abc.trycloudflare.com"))
        assertNull(ServerSettingsParser.parseQr("""{"url":"http://abc.trycloudflare.com","key":"k"}"""))
        assertNull(ServerSettingsParser.parseQr("""{"url":"https://abc.trycloudflare.com","key":""}"""))
        assertNull(ServerSettingsParser.parseQr("""{"url":"https://abc.trycloudflare.com"}"""))
    }
}
