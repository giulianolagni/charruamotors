package com.charruamotors.ui.assistente

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.R
import com.charruamotors.databinding.ActivityAssistenteBinding
import com.charruamotors.domain.model.Veiculo
import com.charruamotors.obd.DispositivoObd
import com.charruamotors.obd.ModoObd
import com.charruamotors.sistema.PreSelecaoAssistente
import com.charruamotors.sistema.permissoes.ItemPermissao
import com.charruamotors.ui.permissoes.PainelPermissoes
import com.charruamotors.ui.permissoes.SolicitadorPermissoes
import com.charruamotors.ui.util.PermissoesBluetooth
import com.charruamotors.ui.util.aplicarInsetsDoSistema
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Assistente de Primeira Abertura: abre sozinho no primeiro Dashboard após o login (flag em
 * `PreferenciasApp`) e pode ser refeito nas Configurações. Quatro passos, todos com "Pular":
 * 1. permissões essenciais (Bluetooth, notificações, bateria "Sem restrições");
 * 2. scanner OBD2 (pré-seleciona `OBDII`) e Bluetooth gatilho do carro (`Toyota SW4` ou digitar);
 * 3. veículo padrão + auto-conexão e logs automáticos (já ligados);
 * 4. recursos de direção opcionais (Acesso a Notificações e Sobrepor apps).
 * "Próximo" grava o passo; "Pular" avança sem gravar. Sair (voltar no 1º passo) conta como concluído.
 */
class AssistenteActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAssistenteBinding
    private val app get() = application as CharruaMotorsApp
    private val solicitador = SolicitadorPermissoes(this) { aoMudarPermissoes() }

    private var passo = 0
    private var painelEssenciais: PainelPermissoes? = null
    private var painelDirecao: PainelPermissoes? = null

    /** Pareados exibidos no passo 2 (o ID da RadioButton é o índice + 1). */
    private var pareados: List<DispositivoObd> = emptyList()
    private var veiculos: List<Veiculo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityAssistenteBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.aplicarInsetsDoSistema()

        passo = savedInstanceState?.getInt(ESTADO_PASSO) ?: 0
        painelEssenciais = PainelPermissoes(binding.passoPermissoes, ItemPermissao.ESSENCIAIS, solicitador)
        painelDirecao = PainelPermissoes(binding.passoDirecao, ItemPermissao.DIRECAO, solicitador)
        configurarPassoBluetooth()
        carregarVeiculos()

        binding.botaoPular.setOnClickListener { avancar(gravar = false) }
        binding.botaoProximo.setOnClickListener { avancar(gravar = true) }
        binding.botaoVoltar.setOnClickListener { voltar() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = voltar()
        })
        mostrarPasso()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(ESTADO_PASSO, passo)
    }

    override fun onResume() {
        super.onResume()
        // Volta das telas do sistema (bateria, Acesso a Notificações, Sobrepor apps, Bluetooth).
        aoMudarPermissoes()
    }

    // ------------------------------------------------------------------ navegação

    private fun avancar(gravar: Boolean) {
        if (gravar) gravarPasso()
        if (passo >= TOTAL_PASSOS - 1) {
            concluir()
            return
        }
        passo++
        mostrarPasso()
    }

    private fun voltar() {
        if (passo == 0) {
            concluir()
            return
        }
        passo--
        mostrarPasso()
    }

    private fun concluir() {
        app.preferenciasApp.marcarAssistenteConcluido()
        finish()
    }

    private fun mostrarPasso() = with(binding) {
        passoPermissoes.isVisible = passo == 0
        passoBluetooth.isVisible = passo == 1
        passoVeiculo.isVisible = passo == 2
        passoDirecao.isVisible = passo == 3
        textoPasso.text = getString(R.string.assistente_passo, passo + 1, TOTAL_PASSOS)
        progresso.setProgressCompat(passo + 1, true)
        textoTitulo.setText(TITULOS[passo])
        textoDescricao.setText(DESCRICOES[passo])
        botaoVoltar.visibility = if (passo == 0) View.INVISIBLE else View.VISIBLE
        botaoProximo.setText(if (passo == TOTAL_PASSOS - 1) R.string.assistente_concluir else R.string.assistente_proximo)
        rolagem.scrollTo(0, 0)
        if (passo == 1 && pareados.isEmpty()) carregarPareados()
    }

    private fun aoMudarPermissoes() {
        painelEssenciais?.atualizar()
        painelDirecao?.atualizar()
        val semPermissao = PermissoesBluetooth.disponivel(this) && !PermissoesBluetooth.concedidas(this)
        val mudou = binding.grupoSemPermissao.isVisible != semPermissao
        binding.grupoSemPermissao.isVisible = semPermissao
        // A permissão de Bluetooth acabou de ser concedida: agora dá para listar os pareados.
        if (mudou && !semPermissao) carregarPareados()
    }

    // ------------------------------------------------------------------ passo 2: Bluetooth

    private fun configurarPassoBluetooth() = with(binding) {
        botaoPermitirBluetooth.setOnClickListener { solicitador.solicitarBluetooth() }
        botaoAtualizarPareados.setOnClickListener { carregarPareados() }
        botaoAbrirBluetooth.setOnClickListener { runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } }
        campoNomeGatilho.setText(app.preferenciasAutoConexao.config.value.gatilhoNome)
        grupoGatilho.setOnCheckedChangeListener { _, id -> layoutNomeGatilho.isVisible = id == ID_DIGITAR }
    }

    private fun carregarPareados() {
        lifecycleScope.launch {
            val lista = if (PermissoesBluetooth.disponivel(this@AssistenteActivity) && PermissoesBluetooth.concedidas(this@AssistenteActivity)) {
                runCatching { app.obdClientDe(ModoObd.BLUETOOTH).listarDispositivos() }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            mostrarPareados(lista)
        }
    }

    private fun mostrarPareados(lista: List<DispositivoObd>) = with(binding) {
        pareados = lista
        val config = app.preferenciasAutoConexao.config.value
        val scanner = PreSelecaoAssistente.scanner(lista, config.scanner)
        val gatilho = PreSelecaoAssistente.gatilho(lista, config, scanner)

        grupoScanner.removeAllViews()
        lista.forEachIndexed { i, d -> grupoScanner.addView(radio(i + 1, d)) }
        scanner?.let { grupoScanner.check(lista.indexOf(it) + 1) }
        textoSemPareados.isVisible = lista.isEmpty()

        grupoGatilho.removeAllViews()
        lista.forEachIndexed { i, d -> grupoGatilho.addView(radio(i + 1, d)) }
        grupoGatilho.addView(RadioButton(this@AssistenteActivity).apply {
            id = ID_DIGITAR
            isSaveEnabled = false
            setText(R.string.assistente_2_digitar)
        })
        // Toyota SW4 pareado: 1 toque. Senão, "Digitar" já com o nome configurado (padrão Toyota SW4).
        grupoGatilho.check(gatilho?.let { lista.indexOf(it) + 1 } ?: ID_DIGITAR)
        layoutNomeGatilho.isVisible = grupoGatilho.checkedRadioButtonId == ID_DIGITAR
    }

    private fun radio(id: Int, dispositivo: DispositivoObd) = RadioButton(this).apply {
        this.id = id
        // IDs 1..n se repetem entre os grupos (cada RadioGroup só procura nos próprios filhos);
        // sem salvar estado, para não colidirem na restauração — as listas são remontadas.
        isSaveEnabled = false
        text = getString(R.string.assistente_dispositivo, dispositivo.nome, dispositivo.endereco)
        layoutParams = RadioGroup.LayoutParams(RadioGroup.LayoutParams.MATCH_PARENT, RadioGroup.LayoutParams.WRAP_CONTENT)
    }

    // ------------------------------------------------------------------ passo 3: veículo

    private fun carregarVeiculos() {
        lifecycleScope.launch {
            veiculos = app.veiculoRepository.observarTodos().first()
            val atual = app.preferenciasAutoConexao.config.value.veiculoId
            with(binding) {
                grupoVeiculo.removeAllViews()
                veiculos.forEachIndexed { i, v ->
                    grupoVeiculo.addView(RadioButton(this@AssistenteActivity).apply {
                        id = i + 1
                        isSaveEnabled = false
                        text = getString(R.string.assistente_dispositivo, v.descricao, v.placaFormatada)
                    })
                }
                val indice = veiculos.indexOfFirst { it.id == atual }.takeIf { it >= 0 } ?: 0
                if (veiculos.isNotEmpty()) grupoVeiculo.check(indice + 1)
                textoSemVeiculos.isVisible = veiculos.isEmpty()
            }
        }
    }

    // ------------------------------------------------------------------ gravação

    private fun gravarPasso() {
        val prefs = app.preferenciasAutoConexao
        when (passo) {
            1 -> with(binding) {
                pareados.getOrNull(grupoScanner.checkedRadioButtonId - 1)?.let(prefs::vincularScanner)
                when (val id = grupoGatilho.checkedRadioButtonId) {
                    ID_DIGITAR -> campoNomeGatilho.text?.toString()?.takeIf { it.isNotBlank() }?.let { prefs.definirGatilho(it, null) }
                    else -> pareados.getOrNull(id - 1)?.let { prefs.definirGatilho(it.nome, it.endereco) }
                }
            }
            2 -> with(binding) {
                veiculos.getOrNull(grupoVeiculo.checkedRadioButtonId - 1)?.let { prefs.vincularVeiculo(it.id, it.descricao) }
                prefs.definirAutoConexao(switchAutoConexao.isChecked)
                prefs.definirGravarLogs(switchLogsAutomaticos.isChecked)
            }
        }
    }

    companion object {
        private const val TOTAL_PASSOS = 4
        private const val ESTADO_PASSO = "passo"
        /** ID da opção "Digitar o nome" no grupo do gatilho (os pareados usam 1..n). */
        private const val ID_DIGITAR = 10_000

        private val TITULOS = intArrayOf(
            R.string.assistente_1_titulo,
            R.string.assistente_2_titulo,
            R.string.assistente_3_titulo,
            R.string.assistente_4_titulo,
        )
        private val DESCRICOES = intArrayOf(
            R.string.assistente_1_descricao,
            R.string.assistente_2_descricao,
            R.string.assistente_3_descricao,
            R.string.assistente_4_descricao,
        )

        fun intent(context: Context): Intent = Intent(context, AssistenteActivity::class.java)
    }
}
