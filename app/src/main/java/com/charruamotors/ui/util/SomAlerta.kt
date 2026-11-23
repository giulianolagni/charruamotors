package com.charruamotors.ui.util

import android.media.AudioManager
import android.media.ToneGenerator

/** Bipe curto e discreto do alerta de proteção (dois toques suaves, no volume de notificação). */
object SomAlerta {

    fun tocar() {
        runCatching {
            val gerador = ToneGenerator(AudioManager.STREAM_NOTIFICATION, VOLUME)
            gerador.startTone(ToneGenerator.TONE_PROP_BEEP2, DURACAO_MS)
            // Libera o gerador depois do tom (sem ele a instância fica presa ao áudio do sistema).
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ gerador.release() }, DURACAO_MS * 2L)
        }
    }

    private const val VOLUME = 60
    private const val DURACAO_MS = 300
}
