package com.charruamotors.direcao.midia

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mini-player nativo: acha o app de música ativo no celular via [MediaSessionManager] (Spotify,
 * YouTube Music, Deezer...) e expõe a faixa atual e os controles de transporte.
 *
 * O Android só entrega as sessões de mídia de outros apps a quem tem o Acesso a Notificações
 * liberado — por isso o [MidiaNotificacaoService], que não faz nada além de existir.
 *
 * Use [iniciar]/[parar] acompanhando o ciclo de vida da tela (onStart/onStop).
 */
class ControladorMidia(context: Context) {

    private val contexto = context.applicationContext
    private val gerenciador: MediaSessionManager? = contexto.getSystemService(MediaSessionManager::class.java)
    private val componente = ComponentName(contexto, MidiaNotificacaoService::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private val _estado = MutableStateFlow<EstadoMidia>(EstadoMidia.SemPermissao)
    val estado: StateFlow<EstadoMidia> = _estado.asStateFlow()

    /** Sessões ativas observadas (todas, para perceber quando outro player começa a tocar). */
    private var controladores: List<MediaController> = emptyList()

    /** Sessão que o card controla agora. */
    private var atual: MediaController? = null

    private var iniciado = false

    private val ouvinteSessoes = MediaSessionManager.OnActiveSessionsChangedListener { lista ->
        observar(lista.orEmpty())
    }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = reavaliar()
        override fun onMetadataChanged(metadata: MediaMetadata?) = reavaliar()
        override fun onSessionDestroyed() = recarregar()
    }

    /** Começa a observar as sessões; sem permissão, publica [EstadoMidia.SemPermissao]. */
    fun iniciar() {
        if (iniciado) parar()
        val gerenciador = gerenciador
        if (gerenciador == null || !permissaoConcedida(contexto)) {
            _estado.value = EstadoMidia.SemPermissao
            return
        }
        try {
            gerenciador.addOnActiveSessionsChangedListener(ouvinteSessoes, componente, handler)
            iniciado = true
            observar(gerenciador.getActiveSessions(componente))
        } catch (_: SecurityException) {
            // Acesso revogado entre a checagem e a chamada.
            _estado.value = EstadoMidia.SemPermissao
        }
    }

    fun parar() {
        if (iniciado) runCatching { gerenciador?.removeOnActiveSessionsChangedListener(ouvinteSessoes) }
        iniciado = false
        controladores.forEach { runCatching { it.unregisterCallback(callback) } }
        controladores = emptyList()
        atual = null
    }

    fun anterior() {
        atual?.transportControls?.skipToPrevious()
    }

    fun proxima() {
        atual?.transportControls?.skipToNext()
    }

    fun tocarPausar() {
        val controlador = atual ?: return
        if (SeletorSessaoMidia.estaTocando(controlador.playbackState?.state)) {
            controlador.transportControls.pause()
        } else {
            controlador.transportControls.play()
        }
    }

    /**
     * Abre o app do player ativo (toque na capa/título). Tenta o ícone do app; se não houver,
     * a tela que o próprio player associou à sessão. Devolve false se nada abriu.
     */
    fun abrirPlayer(): Boolean {
        val controlador = atual ?: return false
        val abertura = contexto.packageManager.getLaunchIntentForPackage(controlador.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (abertura != null && runCatching { contexto.startActivity(abertura) }.isSuccess) return true
        return controlador.sessionActivity?.let { runCatching { it.send() }.isSuccess } ?: false
    }

    // ------------------------------------------------------------------ sessões

    private fun recarregar() {
        val gerenciador = gerenciador ?: return
        try {
            observar(gerenciador.getActiveSessions(componente))
        } catch (_: SecurityException) {
            parar()
            _estado.value = EstadoMidia.SemPermissao
        }
    }

    /** Troca o conjunto de sessões observadas (registra o callback em todas). */
    private fun observar(lista: List<MediaController>) {
        controladores.forEach { runCatching { it.unregisterCallback(callback) } }
        controladores = lista
        lista.forEach { it.registerCallback(callback, handler) }
        reavaliar()
    }

    /** Escolhe a sessão (Spotify tocando primeiro) e publica o estado do card. */
    private fun reavaliar() {
        val sessoes = controladores.map { SessaoMidia(it.packageName, SeletorSessaoMidia.estaTocando(it.playbackState?.state)) }
        val escolhido = SeletorSessaoMidia.escolher(sessoes)?.let(controladores::getOrNull)
        atual = escolhido
        _estado.value = escolhido?.let(::paraEstado) ?: EstadoMidia.SemPlayer
    }

    private fun paraEstado(controlador: MediaController): EstadoMidia.Ativo {
        val metadados = controlador.metadata
        return EstadoMidia.Ativo(
            pacote = controlador.packageName,
            titulo = metadados?.getString(MediaMetadata.METADATA_KEY_TITLE),
            artista = metadados?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadados?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            tocando = SeletorSessaoMidia.estaTocando(controlador.playbackState?.state),
            capa = metadados?.capa(),
        )
    }

    private fun MediaMetadata.capa(): Bitmap? =
        getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

    companion object {
        /** O usuário liberou o Charrua OBD Pro em "Acesso a notificações"? */
        fun permissaoConcedida(context: Context): Boolean =
            context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

        /** Tela do Android para liberar o Acesso a Notificações. */
        fun intentPermissao(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }
}
