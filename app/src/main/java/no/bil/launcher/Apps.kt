package no.bil.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.widget.Toast

/** En installert app som kan startes fra launcheren. */
data class AppEntry(
    val label: String,
    val packageName: String,
    val icon: Drawable,
)

/**
 * En hurtigflis på forsiden. Hver flis har en liste med pakkenavn den prøver i rekkefølge,
 * slik at "Musikk" åpner Spotify hvis det finnes, ellers YouTube Music, ellers innebygd spiller.
 * Rediger denne lista fritt – det er her du tilpasser forsiden.
 */
data class QuickTile(
    val title: String,
    val candidates: List<String>,
    val fallbackIntent: Intent? = null,
)

/** Apper som ikke skal vises i app-skuffen. Legg til pakkenavn her. */
object HiddenApps {
    val packages = setOf(
        "com.android.calculator2",
        "com.google.android.calculator",
        "com.ts.calculator",
    )
    val labelKeywords = listOf("kalkulator", "calculator")

    fun isHidden(app: AppEntry) =
        app.packageName in packages ||
            labelKeywords.any { app.label.lowercase().contains(it) }
}

object Tiles {
    fun byPrefix(p: String) = defaults.first { it.title.startsWith(p) }

    val defaults = listOf(
        QuickTile(
            "Navigasjon",
            listOf(
                "com.google.android.apps.maps",
                "com.waze",
                "com.here.app.maps",
                "com.sygic.aura",
            ),
            fallbackIntent = Intent(Intent.ACTION_VIEW).setData(android.net.Uri.parse("geo:0,0")),
        ),
        QuickTile(
            "Musikk",
            listOf(
                "com.spotify.music",
                "com.google.android.apps.youtube.music",
                "com.apple.android.music",
                "com.android.music",
            ),
        ),
        QuickTile(
            "Radio",
            listOf(
                "com.ts.radio",          // vanlig på Unisoc-enheter
                "com.syu.radio",         // FYT/Joying
                "com.android.radio",
                "com.hzbhd.radio",
            ),
        ),
        QuickTile(
            "Telefon",
            listOf(
                "com.ts.bt",             // Bluetooth-telefon på Unisoc
                "com.syu.bt",
                "com.android.dialer",
                "com.google.android.dialer",
            ),
            fallbackIntent = Intent(Intent.ACTION_DIAL),
        ),
        QuickTile(
            "Bluetooth-musikk",
            listOf(                      // gjetninger – sjekk med «adb shell pm list packages» / Lær-knappen
                "com.ts.bt",
                "com.syu.bt",
                "com.microntek.btmusic",
                "com.android.bluetooth",
            ),
        ),
        QuickTile(
            "CarPlay / Android Auto",
            listOf(
                "com.zjinnova.zlink",    // Zlink – det vanligste CarPlay-programmet på disse
                "com.zjinnova.zlink5",
                "com.autokit.carplay",
                "cn.manstep.phonemirrorBox",
                "com.google.android.projection.gearhead",
            ),
        ),
        QuickTile(
            "Innstillinger",
            listOf("com.android.settings"),
            fallbackIntent = Intent(android.provider.Settings.ACTION_SETTINGS),
        ),
    )
}

object AppRepository {

    fun installedApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map {
                AppEntry(
                    label = it.loadLabel(pm).toString(),
                    packageName = it.activityInfo.packageName,
                    icon = it.loadIcon(pm),
                )
            }
            .filterNot { HiddenApps.isHidden(it) }
            .sortedBy { it.label.lowercase() }
    }

    fun isInstalled(context: Context, packageName: String): Boolean =
        context.packageManager.getLaunchIntentForPackage(packageName) != null

    fun launch(context: Context, packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } else {
            Toast.makeText(context, "Fant ikke appen", Toast.LENGTH_SHORT).show()
        }
    }

    /** Åpner første installerte kandidat for flisa, ellers fallback-intent. */
    fun launchTile(context: Context, tile: QuickTile) {
        val pkg = tile.candidates.firstOrNull { isInstalled(context, it) }
        when {
            pkg != null -> launch(context, pkg)
            tile.fallbackIntent != null -> runCatching {
                context.startActivity(tile.fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure {
                Toast.makeText(context, "Ingen app for ${tile.title}", Toast.LENGTH_SHORT).show()
            }
            else -> Toast.makeText(context, "Ingen app for ${tile.title}", Toast.LENGTH_SHORT).show()
        }
    }

    /** Ikon fra første installerte kandidat, til visning på flisa. */
    fun tileIcon(context: Context, tile: QuickTile): Drawable? {
        val pkg = tile.candidates.firstOrNull { isInstalled(context, it) } ?: return null
        return runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrNull()
    }
}
