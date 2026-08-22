package fr.groggy.racecontrol.tv.f1tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class F1TvChannelIdTest {
    @Test
    fun `data class equality uses value`() {
        assertEquals(F1TvChannelId("abc"), F1TvChannelId("abc"))
        assertNotEquals(F1TvChannelId("abc"), F1TvChannelId("xyz"))
    }
}

class F1TvBasicChannelTypeTest {
    @Test
    fun `prefers PRES identifier as F1 Live`() {
        assertEquals(
            F1TvBasicChannelType.F1Live,
            F1TvBasicChannelType.from("additional", "International", "PRES")
        )
    }

    @Test
    fun `matches F1 LIVE title with spaces`() {
        assertEquals(
            F1TvBasicChannelType.F1Live,
            F1TvBasicChannelType.from("additional", "  F1   LIVE  ", null)
        )
    }

    @Test
    fun `matches F1LIVE compact title`() {
        assertEquals(
            F1TvBasicChannelType.F1Live,
            F1TvBasicChannelType.from("additional", "F1LIVE", null)
        )
    }

    @Test
    fun `wif identifier stays international`() {
        assertEquals(
            F1TvBasicChannelType.Wif,
            F1TvBasicChannelType.from("wif", "International", "WIF")
        )
    }

    @Test
    fun `normalize collapses whitespace`() {
        assertEquals("F1 LIVE", F1TvBasicChannelType.normalizeFeedKey("  f1   live "))
        assertTrue(F1TvBasicChannelType.normalizeFeedKey(null).isEmpty())
    }
}
