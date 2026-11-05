package no.bil.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class NaviKeyTest {
    private val nav = setOf(NaviKey.MAPS, NaviKey.WAZE)

    @Test fun kartapp_gir_popup() {
        assertEquals(NaviKey.Kind.REAL_NAV, NaviKey.classify(NaviKey.MAPS, nav, system = false, label = "Maps", learned = false))
        assertEquals(NaviKey.Kind.REAL_NAV, NaviKey.classify(NaviKey.WAZE, nav, system = false, label = "Waze", learned = true))
    }

    @Test fun stereoens_navi_app_byttes_ut() {
        assertEquals(NaviKey.Kind.STOCK_NAV, NaviKey.classify("com.syu.navi", nav, system = true, label = "Navi", learned = false))
        assertEquals(NaviKey.Kind.STOCK_NAV, NaviKey.classify("com.fyt.car", nav, system = true, label = "Navigation", learned = false))
    }

    @Test fun andre_apper_roeres_ikke() {
        assertEquals(NaviKey.Kind.OTHER, NaviKey.classify("com.spotify.music", nav, system = false, label = "Spotify", learned = false))
        assertEquals(NaviKey.Kind.OTHER, NaviKey.classify("com.android.settings", nav, system = true, label = "Innstillinger", learned = false))
        assertEquals(NaviKey.Kind.OTHER, NaviKey.classify("com.example.canvas", nav, system = true, label = "Canvas", learned = false))
    }
}
