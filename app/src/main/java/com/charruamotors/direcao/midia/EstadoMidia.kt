package com.charruamotors.direcao.midia

import android.graphics.Bitmap
import android.media.session.PlaybackState

/**
 * Estado do mini-player da aba OBD2. Lógica pura (sem `MediaController`), para ser testada na
 * JVM; quem traduz as sessões reais do Android para cá é o [ControladorMidia].
 */
sealed interface EstadoMidia {

    /** O Acesso a Notificações ainda não foi liberado: o card mostra "Ativar controle de música". */
    data object SemPermissao : EstadoMidia

    /** Permissão ok, mas nenhum app de música tem sessão de mídia ativa agora. */
    data object SemPlayer : EstadoMidia

    /** Player escolhido pelo [SeletorSessaoMidia], com a faixa atual. */
    data class Ativo(
        val pacote: String,
        val titulo: String?,
        val artista: String?,
        val tocando: Boolean,
        /** Capa do álbum (`METADATA_KEY_ALBUM_ART` / `ART`); null = mostra o ícone padrão. */
        val capa: Bitmap? = null,
    ) : EstadoMidia {
        /** Nome amigável do app (Spotify, YouTube Music...). */
        val nomePlayer: String get() = PlayersConhecidos.nome(pacote)

        /** Título da faixa; sem metadados (ex.: player recém-aberto), o nome do app. */
        val tituloExibicao: String get() = titulo?.takeIf { it.isNotBlank() } ?: nomePlayer

        /** Artista; sem artista, o nome do app (só quando o título já não é o próprio app). */
        val subtituloExibicao: String
            get() = artista?.takeIf { it.isNotBlank() } ?: if (titulo.isNullOrBlank()) "" else nomePlayer
    }
}

/** Resumo de uma sessão de mídia ativa, na ordem em que o Android as devolve. */
data class SessaoMidia(val pacote: String, val tocando: Boolean)

/** Escolhe qual sessão o mini-player controla quando há mais de um app de música aberto. */
object SeletorSessaoMidia {

    /**
     * Prioridade: Spotify tocando > qualquer player tocando (o primeiro da lista do Android) >
     * Spotify pausado > a primeira sessão. Devolve o índice em [sessoes]; null se vazia.
     */
    fun escolher(sessoes: List<SessaoMidia>): Int? {
        if (sessoes.isEmpty()) return null
        val ordem = listOf<(SessaoMidia) -> Boolean>(
            { it.tocando && it.pacote == PlayersConhecidos.SPOTIFY },
            { it.tocando },
            { it.pacote == PlayersConhecidos.SPOTIFY },
        )
        for (criterio in ordem) {
            val indice = sessoes.indexOfFirst(criterio)
            if (indice >= 0) return indice
        }
        return 0
    }

    /**
     * Estados de [PlaybackState] em que o player está (ou vai estar em instantes) tocando: o
     * botão vira "Pausar". Bufferizando e pulando de faixa contam como tocando.
     */
    fun estaTocando(estadoReproducao: Int?): Boolean = when (estadoReproducao) {
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING,
        PlaybackState.STATE_SKIPPING_TO_NEXT,
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
        -> true
        else -> false
    }
}

/** Pacotes dos players mais comuns e seus nomes para o card. */
object PlayersConhecidos {
    const val SPOTIFY = "com.spotify.music"
    const val YOUTUBE_MUSIC = "com.google.android.apps.youtube.music"
    const val YOUTUBE = "com.google.android.youtube"
    const val DEEZER = "deezer.android.app"
    const val AMAZON_MUSIC = "com.amazon.mp3"
    const val SAMSUNG_MUSIC = "com.sec.android.app.music"

    private val nomes = mapOf(
        SPOTIFY to "Spotify",
        YOUTUBE_MUSIC to "YouTube Music",
        YOUTUBE to "YouTube",
        DEEZER to "Deezer",
        AMAZON_MUSIC to "Amazon Music",
        SAMSUNG_MUSIC to "Samsung Music",
    )

    /** Nome conhecido; senão o último trecho do pacote com inicial maiúscula (`com.foo.radio` → `Radio`). */
    fun nome(pacote: String): String = nomes[pacote]
        ?: pacote.substringAfterLast('.').replaceFirstChar { it.uppercase() }.ifBlank { pacote }
}
