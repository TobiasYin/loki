package dev.thor.agent

import org.junit.Assert.*
import org.junit.Test

class BackgroundAppsTest {
    private val apps = listOf(BackgroundAppEntry("dev.reader", "阅读", true), BackgroundAppEntry("dev.game", "Game One", false), BackgroundAppEntry("dev.player", "播放器", true))
    @Test fun searchesNamesAndSeparatesConfiguredApps() {
        assertEquals(3, filterBackgroundApps(apps, "", BackgroundAppFilter.ALL).size)
        assertEquals(listOf(apps[0], apps[2]), filterBackgroundApps(apps, "", BackgroundAppFilter.CONFIGURED))
        assertEquals(listOf(apps[1]), filterBackgroundApps(apps, " gAmE ", BackgroundAppFilter.UNCONFIGURED))
        assertEquals(listOf(apps[2]), filterBackgroundApps(apps, "播放", BackgroundAppFilter.ALL))
        assertTrue(filterBackgroundApps(apps, "dev.reader", BackgroundAppFilter.ALL).isEmpty())
        assertTrue(filterBackgroundApps(apps, "Game", BackgroundAppFilter.CONFIGURED).isEmpty())
    }
}
