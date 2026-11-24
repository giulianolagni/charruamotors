package com.charruamotors.obd.sessao

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.R
import com.charruamotors.direcao.hud.HudFlutuanteService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Serviço em primeiro plano (`connectedDevice`) da sessão OBD2 — o "modo caixa-preta". Não abre
 * conexão própria: segura o processo vivo enquanto a [SessaoObd] do Application estiver ativa
 * (conexão manual pela aba OBD2 ou automática pelo Bluetooth do carro), com uma notificação
 * discreta (canal de baixa prioridade, sem som nem vibração) mostrando o status
 * (`Conectado · Toyota SW4 · Gravando log`) e a ação **Desconectar**. Para sozinho quando a sessão
 * termina — os logs já foram fechados e salvos pela sessão.
 */
class ObdSessaoService : Service() {

    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessao: SessaoObd get() = (application as CharruaMotorsApp).sessaoObd

    private var emPrimeiroPlano = false
    private var observando = false
    private var ultimoTexto: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACAO_DESCONECTAR) {
            sessao.desconectar()
            if (!emPrimeiroPlano) stopSelf()
            return START_NOT_STICKY
        }
        val automatica = intent?.action == ACAO_AUTOMATICA
        // startForeground antes de tudo: um serviço iniciado com startForegroundService que não entra
        // em primeiro plano a tempo derruba o app.
        if (!emPrimeiroPlano) {
            emPrimeiroPlano = entrarEmPrimeiroPlano()
            if (!emPrimeiroPlano) {
                if (automatica) notificarBloqueio(this)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        observar()
        if (automatica) {
            cancelarAlertaBloqueio(this)
            val gatilho = intent?.getStringExtra(EXTRA_GATILHO)
            escopo.launch {
                val iniciou = sessao.iniciarAutomatico(gatilho)
                if (!iniciou && !sessao.estadoSessao.value.ativa) encerrarServico()
            }
        } else if (!sessao.estadoSessao.value.ativa) {
            encerrarServico()
        }
        // Sem recriação automática: se o sistema matar o processo, a conexão também se foi.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        escopo.cancel()
        super.onDestroy()
    }

    /** Acompanha a sessão: atualiza a notificação e para o serviço quando ela termina. */
    private fun observar() {
        if (observando) return
        observando = true
        escopo.launch {
            var viuAtiva = false
            sessao.estadoSessao.collect { estado ->
                if (estado.ativa) {
                    viuAtiva = true
                    atualizarNotificacao(estado)
                } else if (viuAtiva) {
                    encerrarServico()
                }
            }
        }
    }

    private fun encerrarServico() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        emPrimeiroPlano = false
        stopSelf()
    }

    // ------------------------------------------------------------------ notificação

    private fun entrarEmPrimeiroPlano(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            ID_NOTIFICACAO,
            criarNotificacao(sessao.estadoSessao.value),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )
        true
    } catch (e: RuntimeException) {
        // Android 12+: ForegroundServiceStartNotAllowedException (início em segundo plano bloqueado);
        // Android 14+: SecurityException sem a permissão de Bluetooth exigida pelo tipo connectedDevice.
        Log.w(TAG, "Não foi possível iniciar a sessão OBD em primeiro plano", e)
        false
    }

    private fun atualizarNotificacao(estado: EstadoSessao) {
        val texto = textoStatus(estado)
        if (texto == ultimoTexto) return
        ultimoTexto = texto
        runCatching { getSystemService(NotificationManager::class.java)?.notify(ID_NOTIFICACAO, criarNotificacao(estado)) }
    }

    private fun textoStatus(estado: EstadoSessao): String =
        if (estado.ativa) SessaoObd.descreverStatus(estado) else getString(R.string.sessao_notificacao_preparando)

    private fun criarNotificacao(estado: EstadoSessao): Notification {
        criarCanais(this)
        val desconectar = PendingIntent.getService(
            this,
            REQ_DESCONECTAR,
            Intent(this, ObdSessaoService::class.java).setAction(ACAO_DESCONECTAR),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val abrirApp = HudFlutuanteService.intentVoltarAoApp(this)?.let {
            PendingIntent.getActivity(this, REQ_ABRIR, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_caixa_preta)
            .setContentTitle(getString(R.string.sessao_notificacao_titulo))
            .setContentText(textoStatus(estado))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(abrirApp)
            .addAction(R.drawable.ic_fechar, getString(R.string.sessao_desconectar), desconectar)
            .build()
    }

    companion object {
        private const val TAG = "ObdSessaoService"
        private const val CANAL = "sessao_obd"
        private const val CANAL_ALERTA = "sessao_obd_alerta"
        private const val ID_NOTIFICACAO = 1201
        private const val ID_ALERTA = 1202
        private const val REQ_DESCONECTAR = 1
        private const val REQ_ABRIR = 2
        private const val REQ_INICIAR = 3
        const val ACAO_AUTOMATICA = "com.charruamotors.sessao.AUTOMATICA"
        const val ACAO_DESCONECTAR = "com.charruamotors.sessao.DESCONECTAR"
        const val EXTRA_GATILHO = "gatilho"

        /** Conexão manual (app em primeiro plano): sobe a notificação da sessão. */
        fun iniciar(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ObdSessaoService::class.java))
            } catch (e: IllegalStateException) {
                // A sessão segue no processo; só fica sem a proteção do primeiro plano.
                Log.w(TAG, "Serviço da sessão não iniciado", e)
            }
        }

        /**
         * Bluetooth do carro conectou (broadcast ACL, que libera o início em segundo plano). Se o
         * Android ainda assim bloquear (`ForegroundServiceStartNotAllowedException`, bateria
         * otimizada no One UI...), avisa com uma notificação: um toque inicia a sessão.
         */
        fun iniciarAutomatico(context: Context, gatilho: String?) {
            val intent = Intent(context, ObdSessaoService::class.java).setAction(ACAO_AUTOMATICA).putExtra(EXTRA_GATILHO, gatilho)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: IllegalStateException) { // ForegroundServiceStartNotAllowedException é uma IllegalStateException
                Log.w(TAG, "Início automático bloqueado pelo sistema", e)
                notificarBloqueio(context)
            }
        }

        private fun criarCanais(context: Context) {
            val gerenciador = context.getSystemService(NotificationManager::class.java) ?: return
            if (gerenciador.getNotificationChannel(CANAL) == null) {
                gerenciador.createNotificationChannel(
                    NotificationChannel(CANAL, context.getString(R.string.sessao_canal_nome), NotificationManager.IMPORTANCE_LOW).apply {
                        description = context.getString(R.string.sessao_canal_descricao)
                        setSound(null, null)
                        enableVibration(false)
                        setShowBadge(false)
                    },
                )
            }
            if (gerenciador.getNotificationChannel(CANAL_ALERTA) == null) {
                gerenciador.createNotificationChannel(
                    NotificationChannel(CANAL_ALERTA, context.getString(R.string.sessao_canal_alerta_nome), NotificationManager.IMPORTANCE_HIGH).apply {
                        description = context.getString(R.string.sessao_canal_alerta_descricao)
                    },
                )
            }
        }

        /** Fallback seguro: o sistema bloqueou o serviço vindo do segundo plano; o toque do usuário libera. */
        fun notificarBloqueio(context: Context) {
            criarCanais(context)
            val iniciar = PendingIntent.getForegroundService(
                context,
                REQ_INICIAR,
                Intent(context, ObdSessaoService::class.java).setAction(ACAO_AUTOMATICA),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val texto = context.getString(R.string.sessao_bloqueada_texto)
            val notificacao = NotificationCompat.Builder(context, CANAL_ALERTA)
                .setSmallIcon(R.drawable.ic_caixa_preta)
                .setContentTitle(context.getString(R.string.sessao_bloqueada_titulo))
                .setContentText(texto)
                .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setContentIntent(iniciar)
                .addAction(R.drawable.ic_caixa_preta, context.getString(R.string.sessao_bloqueada_acao), iniciar)
                .build()
            runCatching { context.getSystemService(NotificationManager::class.java)?.notify(ID_ALERTA, notificacao) }
        }

        private fun cancelarAlertaBloqueio(context: Context) {
            runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(ID_ALERTA) }
        }
    }
}
