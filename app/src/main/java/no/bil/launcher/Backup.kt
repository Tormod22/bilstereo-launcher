package no.bil.launcher

import android.content.Context
import java.io.File

/**
 * Sikkerhetskopi i T'OS sin egen mappe på stereoen (Android/data/no.bil.launcher/files), uten Androids filvelger.
 * NB: mappa slettes hvis T'OS avinstalleres – for en kopi som tåler det, bruk «Lagre til fil» (f.eks. Google Drive).
 */
object LocalBackup {
    private fun dir(c: Context): File = c.getExternalFilesDir(null) ?: c.filesDir
    /** Kopien brukeren lagrer selv. */
    fun file(c: Context) = File(dir(c), "backup.json")
    /** Automatisk kopi (én gang i døgnet ved oppstart). */
    fun autoFile(c: Context) = File(dir(c), "backup-auto.json")

    /** Skriver først til en midlertidig fil, så en halvskrevet kopi aldri erstatter en hel. */
    fun save(c: Context, f: File = file(c)): Boolean = runCatching {
        val tmp = File(f.path + ".tmp")
        tmp.writeText(Prefs.exportJson(c))
        if (!tmp.renameTo(f)) { f.delete(); check(tmp.renameTo(f)) }
    }.isSuccess

    /** Henter innstillingene fra [f]. Kalles på hovedtråden (innstillingene tegnes på nytt). */
    fun restore(c: Context, f: File): Boolean =
        runCatching { f.exists() && Prefs.importJson(c, f.readText()) }.getOrDefault(false)

    fun autoSave(c: Context) {
        val f = autoFile(c)
        // Nesten tomme innstillinger (nyinstallert) skal ikke skrive over en god, gammel kopi
        if (runCatching { c.getSharedPreferences("tos", Context.MODE_PRIVATE).all.size }.getOrDefault(0) < 5) return
        if (autoSaveDue(f.exists(), f.lastModified(), System.currentTimeMillis())) save(c, f)
    }

    /** Ren logikk (testes): ny automatisk kopi når den mangler eller er over et døgn gammel. */
    internal fun autoSaveDue(exists: Boolean, lastModified: Long, now: Long): Boolean =
        !exists || now - lastModified > 86_400_000L || lastModified > now
}
