package com.charruamotors.direcao.midia

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Escolha do player e textos do mini-player (estado do [ControladorMidia]). */
class EstadoMidiaTest {

    private val spotifyPausado = SessaoMidia(PlayersConhecidos.SPOTIFY, tocando = false)
    private val spotifyTocando = SessaoMidia(PlayersConhecidos.SPOTIFY, tocando = true)
    private val ytMusicTocando = SessaoMidia(PlayersConhecidos.YOUTUBE_MUSIC, tocando = true)
    private val ytMusicPausado = SessaoMidia(PlayersConhecidos.YOUTUBE_MUSIC, tocando = false)
    private val deezerTocando = SessaoMidia(PlayersConhecidos.DEEZER, tocando = true)

    @Test
    fun `sem sessoes nao ha player`() {
        assertNull(SeletorSessaoMidia.escolher(emptyList()))
    }

    @Test
    fun `spotify tocando vence outro player tocando que vem antes na lista`() {
        assertEquals(1, SeletorSessaoMidia.escolher(listOf(ytMusicTocando, spotifyTocando)))
    }

    @Test
    fun `player tocando vence spotify pausado`() {
        assertEquals(1, SeletorSessaoMidia.escolher(listOf(spotifyPausado, ytMusicTocando)))
    }

    @Test
    fun `entre varios tocando sem spotify fica o primeiro do android`() {
        assertEquals(0, SeletorSessaoMidia.escolher(listOf(deezerTocando, ytMusicTocando)))
    }

    @Test
    fun `tudo pausado prioriza o spotify`() {
        assertEquals(1, SeletorSessaoMidia.escolher(listOf(ytMusicPausado, spotifyPausado)))
    }

    @Test
    fun `tudo pausado sem spotify fica a sessao mais recente`() {
        assertEquals(0, SeletorSessaoMidia.escolher(listOf(ytMusicPausado, SessaoMidia("com.foo.radio", false))))
    }

    @Test
    fun `estados de reproducao que contam como tocando`() {
        listOf(
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_SKIPPING_TO_NEXT,
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
            PlaybackState.STATE_FAST_FORWARDING,
        ).forEach { assertTrue("estado $it", SeletorSessaoMidia.estaTocando(it)) }
        listOf(
            PlaybackState.STATE_PAUSED,
            PlaybackState.STATE_STOPPED,
            PlaybackState.STATE_NONE,
            PlaybackState.STATE_ERROR,
            null,
        ).forEach { assertFalse("estado $it", SeletorSessaoMidia.estaTocando(it)) }
    }

    @Test
    fun `faixa completa mostra titulo e artista`() {
        val estado = EstadoMidia.Ativo(PlayersConhecidos.SPOTIFY, "Céu de Santo Amaro", "Flávio Venturini", tocando = true)
        assertEquals("Céu de Santo Amaro", estado.tituloExibicao)
        assertEquals("Flávio Venturini", estado.subtituloExibicao)
        assertEquals("Spotify", estado.nomePlayer)
    }

    @Test
    fun `sem metadados o titulo vira o nome do player`() {
        val estado = EstadoMidia.Ativo(PlayersConhecidos.YOUTUBE_MUSIC, titulo = null, artista = " ", tocando = false)
        assertEquals("YouTube Music", estado.tituloExibicao)
        assertEquals("", estado.subtituloExibicao)
    }

    @Test
    fun `sem artista o subtitulo mostra o player`() {
        val estado = EstadoMidia.Ativo(PlayersConhecidos.SPOTIFY, "Podcast do dia", artista = null, tocando = true)
        assertEquals("Spotify", estado.subtituloExibicao)
    }

    @Test
    fun `pacote desconhecido vira nome legivel`() {
        assertEquals("Radio", PlayersConhecidos.nome("com.foo.radio"))
        assertEquals("Deezer", PlayersConhecidos.nome(PlayersConhecidos.DEEZER))
    }
}
