package com.charruamotors.direcao.waze

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Dispara os [AcaoWaze] (funciona a partir de Activity ou do serviço do HUD). */
object AbridorWaze {

    /**
     * Abre a ação no Waze, caindo para a Play Store se ele não estiver instalado. Em [AcaoWaze.ABRIR]
     * usa primeiro o ícone do app, que só traz o Waze para a frente sem mexer na rota em andamento.
     * Devolve false se nada pôde ser aberto.
     */
    fun abrir(context: Context, acao: AcaoWaze): Boolean {
        if (acao == AcaoWaze.ABRIR) {
            val icone = context.packageManager.getLaunchIntentForPackage(AtalhosWaze.PACOTE)
            if (icone != null && iniciar(context, icone)) return true
        }
        return AtalhosWaze.tentativas(acao).any { uri -> iniciar(context, Intent(Intent.ACTION_VIEW, Uri.parse(uri))) }
    }

    private fun iniciar(context: Context, intent: Intent): Boolean = try {
        // NEW_TASK: obrigatório quando quem abre é o serviço do HUD (sem Activity).
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
