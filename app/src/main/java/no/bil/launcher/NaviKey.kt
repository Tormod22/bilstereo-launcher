package no.bil.launcher

import android.content.Context

/**
 * NAVI-knappen på stereoen åpner som regel kartappen direkte (eller stereoens egen «Navi»-app),
 * uten å sende en tastekode T'OS kan fange. KeyService ser bare at kartappen kommer fram.
 *  - Ekte kartapp (Google Maps/Waze/valgt kartapp) kom fram uten at T'OS åpnet den → tilby den andre (Waze-popupen).
 *  - Stereoens egen navigasjons-«app» (systemapp med navi/nav i navnet) → åpne den valgte kartappen i stedet.
 */
object NaviKey {
    const val MAPS = "com.google.android.apps.maps"
    const val WAZE = "com.waze"

    enum class Kind { REAL_NAV, STOCK_NAV, OTHER }

    /** Ren logikk (testbar). [learned] = NAVI er lært til akkurat denne appen. */
    internal fun classify(pkg: String, navApps: Set<String>, system: Boolean, label: String, learned: Boolean): Kind {
        if (pkg in navApps) return Kind.REAL_NAV
        if (!system) return if (learned) Kind.STOCK_NAV else Kind.OTHER
        val text = (pkg + " " + label).lowercase()
        val looksNav = Regex("""(^|[._ ])(navi|navigation|navigasjon|gps|nav)([._ ]|$)""").containsMatchIn(text) ||
            "navi" in text || "导航" in text
        // Stereoens egne apper med «navi» i navnet er snarveier til kartvalget – ikke selve kartet
        return if (looksNav || learned) Kind.STOCK_NAV else Kind.OTHER
    }

    /** Vis «Åpne heller …» for den andre kartappen, hvis den finnes. */
    private var lastOffer = 0L

    fun offerOther(c: Context, shown: String) {
        // Ikke mas: høyst én popup i minuttet (f.eks. når man bytter fram og tilbake til kartet)
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastOffer < 60_000) return
        lastOffer = now
        val other = if (shown == WAZE) MAPS else WAZE
        if (AppRepository.isInstalled(c, other)) NaviPopup.show(c, if (other == WAZE) "Waze" else "Google Maps", other)
    }
}
