package com.yegorkapomidorka.headswap.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HuAppsTest {
    private val fermata = HuApps.CATALOG.first { it.name == "Fermata Auto" }
    private val browser = HuApps.CATALOG.first { it.name == "AABrowser" }

    @Test
    fun catalogHasTwoAppsWithGithubReleases() {
        assertEquals(listOf("me.aap.fermata.auto.dear.google.why", "com.kododake.aabrowser"), HuApps.CATALOG.map { it.pkg })
        assertTrue(HuApps.CATALOG.all { it.url.startsWith("https://github.com/") && it.url.endsWith("/releases") })
    }

    @Test
    fun installedComesFromPackageCheck() {
        val installed = setOf(fermata.pkg)
        assertTrue(HuApps.status(fermata, { it in installed }, 36).installed)
        assertFalse(HuApps.status(browser, { it in installed }, 36).installed)
    }

    @Test
    fun aabrowserNeedsAndroid15() {
        assertTrue(HuApps.status(browser, { false }, 34).unsupported)
        assertFalse(HuApps.status(browser, { false }, 35).unsupported)
        assertFalse(HuApps.status(browser, { false }, 36).unsupported)
        assertTrue(browser.reqRes != null)
    }

    @Test
    fun fermataHasNoSdkRestriction() {
        assertFalse(HuApps.status(fermata, { false }, 26).unsupported)
        assertNull(fermata.reqRes)
    }

    @Test
    fun installedCountIgnoresSdk() {
        assertEquals(0, HuApps.installedCount({ false }, 36))
        assertEquals(1, HuApps.installedCount({ it == browser.pkg }, 36))
        assertEquals(2, HuApps.installedCount({ true }, 30))
    }
}
