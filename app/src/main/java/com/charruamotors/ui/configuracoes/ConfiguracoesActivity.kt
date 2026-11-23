package com.charruamotors.ui.configuracoes

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.R
import com.charruamotors.databinding.ActivityConfiguracoesBinding
import com.charruamotors.direcao.desempenho.FonteVelocidade
import com.charruamotors.obd.sessao.ConfigAutoConexao
import com.charruamotors.sistema.StatusCaixaPreta
import com.charruamotors.sistema.permissoes.ItemPermissao
import com.charruamotors.sistema.permissoes.LeitorPermissoes
import com.charruamotors.sistema.preferencias.ConfigApp
import com.charruamotors.sistema.preferencias.TemaApp
import com.charruamotors.sistema.unidades.SistemaUnidades
import com.charruamotors.sistema.unidades.UnidadePressao
import com.charruamotors.BuildConfig
import com.charruamotors.ui.assistente.AssistenteActivity
import com.charruamotors.ui.atualizacao.AtualizadorApp
import com.charruamotors.ui.permissoes.ChecklistPermissoesActivity
import com.charruamotors.ui.permissoes.SolicitadorPermissoes
import com.charruamotors.ui.util.aplicarInsetsDoSistema
import com.charruamotors.ui.util.definirTexto
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Configurações (menu lateral, ou a faixa da caixa-preta na aba OBD2):
 * - Caixa-preta automática: auto-conexão, logs automáticos, Bluetooth gatilho, scanner e veículo
 *   padrão e o atalho de bateria "Sem restrições";
 * - Aparência e Painel: tema (aplicado na hora), unidades globais (métrico ou imperial, que
 *   convertem na hora todo o app e o HUD) e a unidade de pressão do métrico (kPa/bar);
 * - Assistente: refazer o Assistente Inicial e abrir o Checklist de Permissões;
 * - Atualizações: "Verificar atualizações" no GitHub Releases (baixa e instala o APK novo).
 */
class ConfiguracoesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConfiguracoesBinding
    private val app get() = application as CharruaMotorsApp
    private val prefsCaixa get() = app.preferenciasAutoConexao
    private val prefsApp get() = app.preferenciasApp
    private val solicitador = SolicitadorPermissoes(this) { atualizarBateria() }
    private val dialogos = DialogosCaixaPreta(this) { solicitador.solicitarBluetooth() }
    private val atualizador = AtualizadorApp(this)

    /** Localização precisa (o provedor GPS exige a FINE; a COARSE vai junto, como o Android 12+ pede). */
    private val pedirLocalizacao = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { resultado ->
        if (resultado[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            app.reavaliarGps()
        } else {
            Snackbar.make(binding.root, R.string.config_gps_permissao_negada, Snackbar.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        binding = ActivityConfiguracoesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.appbar.aplicarInsetsDoSistema(base = false)
        binding.conteudo.aplicarInsetsDoSistema(topo = false)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        configurarCaixaPreta()
        configurarAparencia()
        configurarGps()
        binding.botaoRefazerAssistente.setOnClickListener {
            prefsApp.reiniciarAssistente()
            startActivity(AssistenteActivity.intent(this))
        }
        binding.botaoChecklist.setOnClickListener { startActivity(ChecklistPermissoesActivity.intent(this)) }
        binding.textoVersaoInstalada.text = getString(R.string.config_atualizacoes_descricao, BuildConfig.VERSION_NAME)
        binding.botaoVerificarAtualizacoes.setOnClickListener { atualizador.verificarManualmente() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    prefsCaixa.config.combine(app.sessaoObd.estadoSessao) { c, e -> c to e }
                        .collect { (config, estado) ->
                            mostrarCaixaPreta(config)
                            binding.textoStatusSessao.definirTexto(StatusCaixaPreta.faixa(config, estado))
                        }
                }
                launch { prefsApp.config.collect(::mostrarAparencia) }
                launch { prefsApp.config.collect(::mostrarGps) }
                launch {
                    app.velocidadeGps.situacao.collect { s ->
                        binding.textoSituacaoGps.text = getString(R.string.config_gps_situacao, s.texto)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // O usuário pode ter voltado agora da tela de bateria do sistema.
        atualizarBateria()
    }

    // ------------------------------------------------------------------ caixa-preta

    private fun configurarCaixaPreta() = with(binding) {
        linhaGatilho.textoTitulo.setText(R.string.config_gatilho)
        linhaScanner.textoTitulo.setText(R.string.config_scanner)
        linhaVeiculo.textoTitulo.setText(R.string.config_veiculo)

        switchAutoConexao.setOnCheckedChangeListener { _, marcado ->
            if (marcado == prefsCaixa.config.value.autoConexaoAtiva) return@setOnCheckedChangeListener
            prefsCaixa.definirAutoConexao(marcado)
            // O broadcast do carro e o serviço connectedDevice exigem a permissão de Bluetooth.
            if (marcado) {
                LeitorPermissoes.avaliar(this@ConfiguracoesActivity, listOf(ItemPermissao.BLUETOOTH))
                    .firstOrNull { !it.ok }
                    ?.let { solicitador.solicitar(it.item) }
            }
        }
        switchLogsAutomaticos.setOnCheckedChangeListener { _, marcado ->
            if (marcado != prefsCaixa.config.value.gravarLogsAutomaticos) prefsCaixa.definirGravarLogs(marcado)
        }
        switchLogCompleto.setOnCheckedChangeListener { _, marcado ->
            if (marcado != prefsCaixa.config.value.logCompletoTrafego) prefsCaixa.definirLogCompleto(marcado)
        }
        linhaGatilho.root.setOnClickListener { dialogos.escolherGatilho() }
        linhaScanner.root.setOnClickListener { dialogos.escolherScanner() }
        linhaVeiculo.root.setOnClickListener {
            dialogos.escolherVeiculo(aoNenhum = {
                Snackbar.make(root, R.string.veiculos_nenhum, Snackbar.LENGTH_LONG).show()
            })
        }
        botaoBateria.setOnClickListener { solicitador.liberarBateria() }
    }

    private fun mostrarCaixaPreta(config: ConfigAutoConexao) = with(binding) {
        if (switchAutoConexao.isChecked != config.autoConexaoAtiva) switchAutoConexao.isChecked = config.autoConexaoAtiva
        if (switchLogsAutomaticos.isChecked != config.gravarLogsAutomaticos) switchLogsAutomaticos.isChecked = config.gravarLogsAutomaticos
        if (switchLogCompleto.isChecked != config.logCompletoTrafego) switchLogCompleto.isChecked = config.logCompletoTrafego
        linhaGatilho.textoValor.definirTexto(
            if (config.gatilhoEndereco != null) "${config.gatilhoNome} (${config.gatilhoEndereco})" else config.gatilhoNome,
        )
        linhaScanner.textoValor.definirTexto(
            config.scanner?.let { "${it.nome} (${it.endereco})" } ?: getString(R.string.config_scanner_nenhum),
        )
        linhaVeiculo.textoValor.definirTexto(config.veiculoDescricao ?: getString(R.string.caixa_veiculo_nenhum))
    }

    private fun atualizarBateria() {
        if (!::binding.isInitialized) return
        val liberada = LeitorPermissoes.ler(this).bateriaSemRestricoes
        val cor = ContextCompat.getColor(this, if (liberada) R.color.status_ok else R.color.status_pendente)
        binding.pontoBateria.backgroundTintList = ColorStateList.valueOf(cor)
        binding.textoBateria.setText(if (liberada) R.string.config_bateria_ok else R.string.config_bateria_pendente)
        binding.botaoBateria.isVisible = !liberada
    }

    // ------------------------------------------------------------------ aparência

    private fun configurarAparencia() = with(binding) {
        grupoTema.addOnButtonCheckedListener { _, id, marcado ->
            if (!marcado) return@addOnButtonCheckedListener
            val tema = when (id) {
                R.id.botao_tema_claro -> TemaApp.CLARO
                R.id.botao_tema_escuro -> TemaApp.ESCURO
                else -> TemaApp.SISTEMA
            }
            if (tema == prefsApp.tema) return@addOnButtonCheckedListener
            prefsApp.definirTema(tema)
            // Aplica na hora: as Activities abertas são recriadas no novo modo.
            AppCompatDelegate.setDefaultNightMode(tema.modoNoturno)
        }
        grupoSistemaUnidades.addOnButtonCheckedListener { _, id, marcado ->
            if (!marcado) return@addOnButtonCheckedListener
            val sistema = if (id == R.id.botao_sistema_imperial) SistemaUnidades.IMPERIAL else SistemaUnidades.METRICO
            if (sistema != prefsApp.sistemaUnidades) prefsApp.definirSistemaUnidades(sistema)
        }
        grupoUnidade.addOnButtonCheckedListener { _, id, marcado ->
            if (!marcado) return@addOnButtonCheckedListener
            prefsApp.definirUnidadePressao(
                when (id) {
                    R.id.botao_unidade_bar -> UnidadePressao.BAR
                    R.id.botao_unidade_psi -> UnidadePressao.PSI
                    else -> UnidadePressao.KPA
                },
            )
        }
    }

    private fun mostrarAparencia(config: ConfigApp) = with(binding) {
        val tema = when (config.tema) {
            TemaApp.SISTEMA -> R.id.botao_tema_sistema
            TemaApp.CLARO -> R.id.botao_tema_claro
            TemaApp.ESCURO -> R.id.botao_tema_escuro
        }
        if (grupoTema.checkedButtonId != tema) grupoTema.check(tema)
        val unidade = when (config.unidadePressao) {
            UnidadePressao.KPA -> R.id.botao_unidade_kpa
            UnidadePressao.BAR -> R.id.botao_unidade_bar
            UnidadePressao.PSI -> R.id.botao_unidade_psi
        }
        if (grupoUnidade.checkedButtonId != unidade) grupoUnidade.check(unidade)
        val imperial = config.sistemaUnidades == SistemaUnidades.IMPERIAL
        val sistema = if (imperial) R.id.botao_sistema_imperial else R.id.botao_sistema_metrico
        if (grupoSistemaUnidades.checkedButtonId != sistema) grupoSistemaUnidades.check(sistema)
        textoSistemaUnidades.setText(if (imperial) R.string.config_sistema_imperial_descricao else R.string.config_sistema_metrico_descricao)
        // No imperial a pressão é sempre psi: a escolha de kPa/bar só vale no métrico.
        grupoUnidade.isEnabled = !imperial
        textoUnidadePressao.setText(if (imperial) R.string.config_unidade_imperial else R.string.config_unidade_descricao)
    }

    // ------------------------------------------------------------------ velocidade GPS

    private fun configurarGps() = with(binding) {
        switchVelocidadeGps.setOnCheckedChangeListener { _, marcado ->
            if (marcado == prefsApp.config.value.velocidadeGps) return@setOnCheckedChangeListener
            prefsApp.definirVelocidadeGps(marcado)
            if (marcado && !app.velocidadeGps.temPermissao()) {
                pedirLocalizacao.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        }
        grupoFonteCronometro.addOnButtonCheckedListener { _, id, marcado ->
            if (!marcado) return@addOnButtonCheckedListener
            val fonte = if (id == R.id.botao_fonte_gps) FonteVelocidade.GPS else FonteVelocidade.OBD
            if (fonte != prefsApp.config.value.fonteCronometro) prefsApp.definirFonteCronometro(fonte)
        }
    }

    private fun mostrarGps(config: ConfigApp) = with(binding) {
        if (switchVelocidadeGps.isChecked != config.velocidadeGps) switchVelocidadeGps.isChecked = config.velocidadeGps
        val fonte = if (config.fonteCronometro == FonteVelocidade.GPS) R.id.botao_fonte_gps else R.id.botao_fonte_ecu
        if (grupoFonteCronometro.checkedButtonId != fonte) grupoFonteCronometro.check(fonte)
        // A fonte GPS só vale com a leitura ligada.
        grupoFonteCronometro.isEnabled = config.velocidadeGps
        textoSituacaoGps.isVisible = config.velocidadeGps
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, ConfiguracoesActivity::class.java)
    }
}
