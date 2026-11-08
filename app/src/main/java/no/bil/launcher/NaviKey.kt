package no.bil.launcher

import android.content.Context

/**
 * NAVI-knappen på stereoen åpner som regel kartappen direkte (eller stereoens egen «Navi»-app),
 * uten å sende en tastekode T'OS kan fange. KeyService ser bare at kartappen kommer fram.
 *  - Ekte kartapp (Google Maps/Waze/iGO/valgt kartapp – alle som åpner «geo:») kom fram uten at T'OS åpnet den →
 *    tilby brukerens kartapp, eller den andre av Waze/Google Maps (popupen).
 *  - Stereoens egen navigasjons-«app» (systemapp med navi/nav i navnet) → åpne den valgte kartappen i stedet.
 */
object NaviKey {
    const val MAPS = "com.google.android.apps.maps"
    const val WAZE = "com.waze"
    /** iGO – navigasjonsappen FYT-stereoen åpner med NAVI-knappen (kartleggingen 8. nov, sys.navi.packagename). */
    const val IGO = "com.nng.igo.primong.igoworld"
    const val YANDEX = "ru.yandex.yandexnavi"

    /** Kjente kartapper med navnet som vises. Andre apper som åpner «geo:»-adresser får appnavnet sitt. */
    private val known = linkedMapOf(MAPS to "Google Maps", WAZE to "Waze", IGO to "iGO", YANDEX to "Yandex Navigator")
    fun knownLabel(pkg: String): String? = known[pkg]

    /**
     * Ekte kartapper på stereoen: alle som åpner en «geo:»-adresse (Google Maps, Waze, iGO, Yandex …). Huskes i 10 min.
     * T'OS selv og stereoens snarvei-apper regnes ikke med.
     */
    @Volatile private var geoCache: Pair<Long, Set<String>>? = null
    fun geoApps(c: Context): Set<String> {
        val now = android.os.SystemClock.elapsedRealtime()
        geoCache?.let { (at, set) -> if (now - at < 600_000) return set }
        val set = runCatching {
            c.packageManager.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0?q=0,0")), 0)
                .map { it.activityInfo.packageName }
        }.getOrDefault(emptyList()).filter { it != c.packageName && it != "com.syu.onekeynavi" }.toSet()
        geoCache = now to set
        return set
    }
    fun invalidate() { geoCache = null }

    /** Kartappene T'OS kan velge mellom (Innstillinger → Navigasjon): kjente først, så andre som åpner «geo:». */
    fun choices(c: Context): List<Pair<String, String>> {
        val geo = geoApps(c)
        val pm = c.packageManager
        val list = known.filter { (p, _) -> p in geo || AppRepository.isInstalled(c, p) }.map { it.key to it.value } +
            geo.filter { it !in known }.sorted().map { p ->
                p to (runCatching { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: p)
            }
        // Google Maps er standard og vises alltid (også når den ikke finnes ennå)
        return if (list.none { it.first == MAPS }) listOf(MAPS to "Google Maps") + list else list
    }

    /**
     * Hvilken kartapp popupen skal tilby når [shown] kom fram uten at T'OS åpnet den: brukerens kartapp ([preferred])
     * når en annen kartapp kom (f.eks. iGO fra NAVI-knappen), ellers den andre av Waze/Google Maps. null = ingen.
     */
    internal fun offerFor(shown: String, preferred: String?, installed: (String) -> Boolean): String? {
        val pref = preferred?.takeIf { it.isNotBlank() && installed(it) }
        if (pref != null && pref != shown) return pref
        val other = when (shown) { WAZE -> MAPS; MAPS -> WAZE; else -> return null }
        return other.takeIf { installed(it) }
    }

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
        val other = offerFor(shown, Prefs.str("nav_app", MAPS), { AppRepository.isInstalled(c, it) }) ?: return
        lastOffer = now
        val label = knownLabel(other) ?: runCatching { c.packageManager.let { pm -> pm.getApplicationLabel(pm.getApplicationInfo(other, 0)).toString() } }.getOrDefault(other)
        NaviPopup.show(c, label, other)
    }
}
