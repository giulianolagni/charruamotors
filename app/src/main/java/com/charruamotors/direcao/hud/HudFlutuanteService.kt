package com.charruamotors.direcao.hud

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.R
import com.charruamotors.databinding.HudFlutuanteBinding
import com.charruamotors.databinding.ItemHudCelulaBinding
import com.charruamotors.direcao.painel.EstiloHud
import com.charruamotors.direcao.painel.SensorPainel
import com.charruamotors.direcao.painel.TamanhoHud
import com.charruamotors.direcao.waze.AbridorWaze
import com.charruamotors.direcao.waze.AcaoWaze
import com.charruamotors.ui.util.definirTexto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Modo HUD: painel compacto e semitransparente numa janela `TYPE_APPLICATION_OVERLAY` sobre o
 * Waze, arrastável com o dedo. Tamanho (Minimizado: RPM e Turbo; Expandido: até 4 sensores),
 * sensores fixados e estilo (números, números grandes ou com barra) vêm da tela de ativação, e os
 * valores seguem as unidades globais (métrico/imperial).
 *
 * Roda em primeiro plano (`connectedDevice`, ligado ao scanner OBD2 Bluetooth): com a
 * notificação fixa, o Android mantém o processo — e com ele o socket do ELM327 e o polling do
 * `ObdViewModel` — vivo enquanto o app fica minimizado. Os dados chegam pela
 * [TelemetriaCompartilhada] do Application.
 */
class HudFlutuanteService : Service() {

    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val telemetria: TelemetriaCompartilhada get() = (application as CharruaMotorsApp).telemetriaCompartilhada

    private var gerenciadorJanelas: WindowManager? = null
    private var binding: HudFlutuanteBinding? = null
    private var parametros: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // startForeground antes de tudo: parar um serviço iniciado com startForegroundService sem
        // ter entrado em primeiro plano derruba o app.
        if (!entrarEmPrimeiroPlano() || !Settings.canDrawOverlays(this) || !mostrarJanela()) {
            stopSelf()
            return
        }
        telemetria.definirHudAtivo(true)
        escopo.launch { telemetria.painel.collect(::desenhar) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACAO_FECHAR) stopSelf()
        // Sem recriação automática: se o sistema matar o processo, a conexão OBD também se foi.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        telemetria.definirHudAtivo(false)
        escopo.cancel()
        binding?.let { b -> runCatching { gerenciadorJanelas?.removeView(b.root) } }
        binding = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ primeiro plano

    private fun entrarEmPrimeiroPlano(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            ID_NOTIFICACAO,
            criarNotificacao(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )
        true
    } catch (e: RuntimeException) {
        // Android 14+: SecurityException sem a permissão de Bluetooth (pré-requisito do tipo
        // connectedDevice) ou ForegroundServiceStartNotAllowedException.
        Log.w(TAG, "Não foi possível iniciar o HUD em primeiro plano", e)
        false
    }

    private fun criarNotificacao(): Notification {
        val gerenciador = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && gerenciador?.getNotificationChannel(CANAL) == null) {
            gerenciador?.createNotificationChannel(
                NotificationChannel(CANAL, getString(R.string.hud_canal_nome), NotificationManager.IMPORTANCE_LOW).apply {
                    description = getString(R.string.hud_canal_descricao)
                    setShowBadge(false)
                },
            )
        }
        val fechar = PendingIntent.getService(
            this,
            0,
            Intent(this, HudFlutuanteService::class.java).setAction(ACAO_FECHAR),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val abrirApp = intentVoltarAoApp(this)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_hud)
            .setContentTitle(getString(R.string.hud_notificacao_titulo))
            .setContentText(getString(R.string.hud_notificacao_texto))
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(abrirApp)
            .addAction(R.drawable.ic_fechar, getString(R.string.hud_fechar), fechar)
            .build()
    }

    // ------------------------------------------------------------------ janela flutuante

    private fun mostrarJanela(): Boolean {
        val wm = getSystemService(WindowManager::class.java) ?: return false
        val b = HudFlutuanteBinding.inflate(LayoutInflater.from(ContextThemeWrapper(this, R.style.Theme_CharruaMotors)))
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Não rouba o foco nem o teclado do Waze; toques fora do painel passam direto.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = posicaoX
            y = posicaoY
        }
        b.botaoHudWaze.setOnClickListener { AbridorWaze.abrir(this, AcaoWaze.ABRIR) }
        b.botaoHudVoltar.setOnClickListener {
            intentVoltarAoApp(this)?.let { runCatching { startActivity(it) } }
            stopSelf()
        }
        habilitarArraste(b.root)
        try {
            wm.addView(b.root, p)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Sem permissão para desenhar sobre outros apps", e)
            return false
        }
        gerenciadorJanelas = wm
        binding = b
        parametros = p
        return true
    }

    /** Arrasta a janela pelo painel (os botões continuam recebendo os próprios toques). */
    @SuppressLint("ClickableViewAccessibility")
    private fun habilitarArraste(alvo: View) {
        val limiar = ViewConfiguration.get(this).scaledTouchSlop
        var inicioX = 0
        var inicioY = 0
        var toqueX = 0f
        var toqueY = 0f
        var arrastando = false
        alvo.setOnTouchListener { _, evento ->
            val p = parametros ?: return@setOnTouchListener false
            when (evento.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    inicioX = p.x
                    inicioY = p.y
                    toqueX = evento.rawX
                    toqueY = evento.rawY
                    arrastando = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = evento.rawX - toqueX
                    val dy = evento.rawY - toqueY
                    if (!arrastando && (abs(dx) > limiar || abs(dy) > limiar)) arrastando = true
                    if (arrastando) {
                        p.x = (inicioX + dx).toInt().coerceAtLeast(0)
                        p.y = (inicioY + dy).toInt().coerceAtLeast(0)
                        posicaoX = p.x
                        posicaoY = p.y
                        runCatching { gerenciadorJanelas?.updateViewLayout(alvo, p) }
                    }
                    true
                }
                else -> arrastando
            }
        }
    }

    /** Células montadas para a combinação atual de sensores e estilo; refeitas só quando ela muda. */
    private var celulas: List<ItemHudCelulaBinding> = emptyList()
    private var assinatura: Pair<List<SensorPainel>, EstiloHud>? = null

    private fun desenhar(painel: PainelHud) {
        val b = binding ?: return
        val nova = painel.celulas.map { it.sensor } to painel.estilo
        if (nova != assinatura) montarCelulas(b, painel)
        painel.celulas.forEachIndexed { i, celula ->
            val c = celulas.getOrNull(i) ?: return@forEachIndexed
            c.valorCelula.definirTexto(celula.valor)
            c.rotuloCelula.definirTexto(celula.rotulo)
            if (painel.estilo == EstiloHud.BARRAS) {
                c.barraCelula.setProgressCompat(((celula.fracao ?: 0f) * 1000).toInt(), false)
            }
            val quente = painel.alertaTemperatura && celula.sensor == SensorPainel.TEMP_MOTOR
            c.valorCelula.setTextColor(ContextCompat.getColor(this, if (quente) R.color.branco else R.color.hud_valor))
        }
        b.textoHudStatus.setText(
            when {
                !painel.conectado -> R.string.hud_status_desconectado
                painel.alertaTemperatura -> R.string.hud_status_alerta
                else -> R.string.hud_status_ao_vivo
            },
        )
        b.root.setBackgroundResource(if (painel.alertaTemperatura) R.drawable.fundo_hud_alerta else R.drawable.fundo_hud)
    }

    /**
     * Grade de 2 colunas: Minimizado (RPM + Turbo) numa linha e largura menor; Expandido em até
     * 2 linhas. "Números grandes" aumenta o texto; "com barra" mostra a fração da escala.
     */
    private fun montarCelulas(b: HudFlutuanteBinding, painel: PainelHud) {
        val inflador = LayoutInflater.from(ContextThemeWrapper(this, R.style.Theme_CharruaMotors))
        b.gradeHud.removeAllViews()
        val novas = mutableListOf<ItemHudCelulaBinding>()
        painel.celulas.chunked(COLUNAS).forEachIndexed { indiceLinha, linha ->
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                if (indiceLinha > 0) setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, 0)
            }
            for (celula in linha) {
                val c = ItemHudCelulaBinding.inflate(inflador, container, false)
                c.barraCelula.isVisible = painel.estilo == EstiloHud.BARRAS
                c.valorCelula.textSize = if (painel.estilo == EstiloHud.NUMEROS_GRANDES) TEXTO_GRANDE_SP else TEXTO_NORMAL_SP
                c.rotuloCelula.text = celula.rotulo
                container.addView(c.root)
                novas += c
            }
            b.gradeHud.addView(container)
        }
        celulas = novas
        assinatura = painel.celulas.map { it.sensor } to painel.estilo
        val larguraDp = when {
            painel.tamanho == TamanhoHud.MINIMIZADO -> LARGURA_MINIMIZADO_DP
            painel.estilo == EstiloHud.NUMEROS_GRANDES -> LARGURA_GRANDE_DP
            else -> LARGURA_EXPANDIDO_DP
        }
        parametros?.let { p ->
            p.width = (larguraDp * resources.displayMetrics.density).toInt()
            runCatching { gerenciadorJanelas?.updateViewLayout(b.root, p) }
        }
    }

    companion object {
        private const val TAG = "HudFlutuante"
        private const val CANAL = "hud_flutuante"
        private const val ID_NOTIFICACAO = 1101
        private const val ACAO_FECHAR = "com.charruamotors.hud.FECHAR"

        /** Última posição do painel no processo (reabre onde o motorista deixou). */
        private var posicaoX = 24
        private var posicaoY = 240

        private const val COLUNAS = 2
        private const val TEXTO_NORMAL_SP = 30f
        private const val TEXTO_GRANDE_SP = 40f
        private const val LARGURA_MINIMIZADO_DP = 200
        private const val LARGURA_EXPANDIDO_DP = 232
        private const val LARGURA_GRANDE_DP = 280

        fun iniciar(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, HudFlutuanteService::class.java))
        }

        fun parar(context: Context) {
            context.stopService(Intent(context, HudFlutuanteService::class.java))
        }

        /**
         * Volta ao Charrua OBD Pro: o mesmo intent do ícone do launcher, que traz a tarefa existente
         * (com a aba OBD2 e a conexão) para a frente em vez de abrir o login de novo.
         */
        fun intentVoltarAoApp(context: Context): Intent? =
            context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }
}
