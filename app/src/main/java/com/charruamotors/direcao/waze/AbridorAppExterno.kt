package com.charruamotors.direcao.waze

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Atalhos do Painel que abrem apps externos de navegação e música (Google Maps e Spotify). */
object AbridorAppExterno {

    const val PACOTE_MAPS = "com.google.android.apps.maps"
    const val PACOTE_SPOTIFY = "com.spotify.music"

    /** Abre o app pelo ícone; sem ele instalado, cai na página dele na Play Store (ou no navegador). */
    fun abrir(context: Context, pacote: String): Boolean {
        val icone = context.packageManager.getLaunchIntentForPackage(pacote)
        if (icone != null && iniciar(context, icone)) return true
        return listOf("market://details?id=$pacote", "https://play.google.com/store/apps/details?id=$pacote")
            .any { uri -> iniciar(context, Intent(Intent.ACTION_VIEW, Uri.parse(uri))) }
    }

    private fun iniciar(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
