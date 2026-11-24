package com.charruamotors.ui.painel

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.databinding.ActivityClusterPainelBinding
import com.charruamotors.direcao.desempenho.FonteVelocidade
import com.charruamotors.direcao.painel.ClusterPainel
import com.charruamotors.direcao.painel.EstiloMostrador
import com.charruamotors.direcao.painel.SensorPainel
import com.charruamotors.obd.DadosObd
import com.charruamotors.sistema.unidades.ConversorUnidades
import com.charruamotors.ui.componentes.GaugeView
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Modo Painel Automotivo: cluster em tela cheia, aberto sozinho pela aba Painel quando o celular
 * vai para a horizontal (conectado ou no Modo Simulado) e fechado ao voltar para a vertical.
 *
 * Centro: marcha/alavanca, velocidade digital e avisos; ao lado, os relógios grandes de RPM e
 * velocidade; nas pontas, 4 bolinhas (água, óleo/ATF, turbo/MAP, bateria) que trocam de sensor
 * ao toque. Lê a [com.charruamotors.direcao.hud.TelemetriaCompartilhada], a mesma do HUD: a
 * sessão OBD continua no serviço, esta tela só desenha.
 */
class ClusterPainelActivity : AppCompatActivity() {

    private lateinit var binding: ActivityClusterPainelBinding
    private val app: CharruaMotorsApp get() = application as CharruaMotorsApp

    private var conversor: ConversorUnidades = ConversorUnidades.PADRAO
    private var dados: DadosObd? = null

    /** Escolha do usuário por bolinha; null = padrão/automático (ver [ClusterPainel.sensorDaBolinha]). */
    private val escolhidos = arrayOfNulls<SensorPainel>(ClusterPainel.BOLINHAS)

    /** Sensor desenhado em cada bolinha agora, e o conversor com que os relógios foram configurados. */
    private val exibidos = arrayOfNulls<SensorPainel>(ClusterPainel.BOLINHAS)
    private var conversorConfigurado: ConversorUnidades? = null

    private lateinit var bolinhas: List<GaugeView>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) {
            finish()
            return
        }
        binding = ActivityClusterPainelBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        entrarEmTelaCheia()

        bolinhas = listOf(binding.bolinha0, binding.bolinha1, binding.bolinha2, binding.bolinha3)
        carregarEscolhas()
        bolinhas.forEachIndexed { i, gauge -> gauge.setOnClickListener { trocarSensor(i) } }

        binding.textoDesempenho.setOnClickListener { app.telemetriaCompartilhada.cronometro.reiniciar() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                dispensado = true // continua na horizontal: a aba Painel não deve reabrir o cluster
                finish()
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(
                        app.telemetriaCompartilhada.cronometro.estado,
                        app.telemetriaCompartilhada.fonteCronometroAtiva,
                        app.recordesDesempenho.recordes,
                    ) { e, fonte, _ -> e to fonte }
                        .collect { (e, fonte) ->
                            val via = if (fonte == FonteVelocidade.GPS) " (GPS)" else ""
                            binding.textoDesempenho.text = "0–100$via · ${e.texto}${"\n"}${app.recordesDesempenho.recordeDe(app.chaveRecorde()).texto}"
                        }
                }
                combine(app.telemetriaCompartilhada.dados, app.preferenciasApp.config.map { it.conversor }) { d, c -> d to c }
                    .collect { (d, c) ->
                        dados = d
                        desenhar(c)
                    }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        aberto = true
        app.sessaoObd.registrarEvento("Cluster horizontal ABERTO")
    }

    override fun onStop() {
        aberto = false
        app.sessaoObd.registrarEvento("Cluster horizontal FECHADO")
        super.onStop()
    }

    /** Vertical de novo: volta ao layout padrão da aba Painel (o finish() aqui é o comportamento desejado). */
    @android.annotation.SuppressLint("ChromeOsOnConfigurationChanged")
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation != Configuration.ORIENTATION_LANDSCAPE) finish()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::binding.isInitialized) entrarEmTelaCheia()
    }

    /** Esconde barra de status e de navegação (um deslize da borda as mostra por instantes) e ocupa o recorte da câmera. */
    private fun entrarEmTelaCheia() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        val raiz = binding.raiz
        if (raiz.getTag(TAG_PADDING_BASE) == null) {
            raiz.setTag(TAG_PADDING_BASE, intArrayOf(raiz.paddingLeft, raiz.paddingTop, raiz.paddingRight, raiz.paddingBottom))
        }
        ViewCompat.setOnApplyWindowInsetsListener(raiz) { v, insets ->
            val base = v.getTag(TAG_PADDING_BASE) as IntArray
            val recorte = insets.getInsets(WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.systemBars())
            v.setPadding(base[0] + recorte.left, base[1] + recorte.top, base[2] + recorte.right, base[3] + recorte.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(raiz)
    }

    private fun desenhar(novoConversor: ConversorUnidades) {
        val mudouConversor = novoConversor != conversorConfigurado
        conversor = novoConversor
        if (mudouConversor) {
            conversorConfigurado = novoConversor
            MontadorMostradores.configurarGauge(binding.gaugeRpm, SensorPainel.RPM, EstiloMostrador.ESPORTIVO, conversor)
            MontadorMostradores.configurarGauge(binding.gaugeVelocidade, SensorPainel.VELOCIDADE, EstiloMostrador.ESPORTIVO, conversor)
            binding.textoUnidadeVelocidade.text = SensorPainel.VELOCIDADE.unidade(conversor)
        }
        binding.gaugeRpm.definirValor(valorExibido(SensorPainel.RPM))
        binding.gaugeVelocidade.definirValor(valorExibido(SensorPainel.VELOCIDADE))

        // Limites do veículo: o som toca uma vez ao entrar em alerta; as bolinhas e o aviso ficam vermelhos.
        val alertas = app.centralAlertas.processar(dados)
        bolinhas.forEachIndexed { i, gauge ->
            val sensor = ClusterPainel.sensorDaBolinha(i, escolhidos[i], dados)
            gauge.emAlerta = sensor.emAlerta(alertas) || app.centralAlertas.foraDaFaixa(sensor.comandos, dados)
            if (sensor != exibidos[i] || mudouConversor) {
                exibidos[i] = sensor
                MontadorMostradores.configurarGauge(gauge, sensor, EstiloMostrador.DIGITAL, conversor)
            }
            gauge.definirValor(valorExibido(sensor))
        }

        val marcha = ClusterPainel.indicadorMarcha(dados)
        binding.textoMarcha.text = marcha.principal
        binding.textoLegendaMarcha.text = marcha.legenda.orEmpty()
        binding.textoVelocidade.text = SensorPainel.VELOCIDADE.texto(SensorPainel.VELOCIDADE.ler(dados), conversor)
        binding.textoAlertas.text = ClusterPainel.alertas(dados, app.centralAlertas.limites).joinToString("  ·  ") { it.texto }
    }

    private fun valorExibido(sensor: SensorPainel): Double? =
        sensor.ler(dados)?.let { sensor.exibir(it, conversor).valor }

    /** Toque numa bolinha: passa para o próximo sensor com leitura; a escolha fica salva. */
    private fun trocarSensor(indice: Int) {
        val atual = ClusterPainel.sensorDaBolinha(indice, escolhidos[indice], dados)
        escolhidos[indice] = ClusterPainel.proximo(atual, dados)
        app.sessaoObd.registrarEvento("Cluster: bolinha ${indice + 1} trocou de ${atual.titulo} para ${escolhidos[indice]?.titulo}")
        salvarEscolhas()
        desenhar(conversor)
    }

    private fun carregarEscolhas() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        for (i in escolhidos.indices) escolhidos[i] = SensorPainel.deCodigo(prefs.getString("bolinha$i", null))
    }

    private fun salvarEscolhas() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            escolhidos.forEachIndexed { i, s -> if (s == null) remove("bolinha$i") else putString("bolinha$i", s.name) }
        }.apply()
    }

    companion object {
        private const val PREFS = "cluster_painel"
        private const val TAG_PADDING_BASE = 0x7F0C0001

        /** O cluster está na tela (evita abri-lo duas vezes). */
        @Volatile var aberto = false
            private set

        /**
         * O usuário saiu do cluster com o Voltar sem girar o celular: a aba Painel não o reabre até
         * o celular voltar à vertical.
         */
        @Volatile var dispensado = false

        fun intent(contexto: Context) = Intent(contexto, ClusterPainelActivity::class.java)
    }
}
