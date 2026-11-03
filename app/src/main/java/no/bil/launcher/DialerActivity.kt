package no.bil.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * «Ring/vis nummer» fra andre apper (tel:-lenker, «Ring» i Kart, osv.) og Androids krav til en telefon-app:
 * åpner T'OS sin Telefon-side med nummeret klart på tastaturet. Viser ingenting selv.
 */
class DialerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        val number = if (data?.scheme == "tel") dialable(data.schemeSpecificPart.orEmpty()) else ""
        Phone.pendingDial = number
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).putExtra(Nav.EXTRA, Screen.PHONE.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
        finish()
    }
}
