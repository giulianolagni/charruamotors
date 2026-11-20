package com.charruamotors.ui.diagnostico.obd

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.charruamotors.R
import com.charruamotors.databinding.FragmentObdBinding
import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.domain.model.LeituraObd
import com.charruamotors.obd.DadosObd
import com.charruamotors.obd.DispositivoObd
import com.charruamotors.obd.ModoObd
import com.charruamotors.ui.diagnostico.DiagnosticoActivity
import com.charruamotors.ui.util.PermissoesBluetooth
import com.charruamotors.ui.util.comoDecimal
import com.charruamotors.ui.util.comoMilhar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/** Aba 1 — Diagnóstico OBD2: conexão com o scanner, dados em tempo real e códigos de falha. */
class ObdFragment : Fragment() {

    // Binding só é válido entre onCreateView e onDestroyView; é liberado em onDestroyView.
    private var _binding: FragmentObdBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ObdViewModel by viewModels { ObdViewModel.Factory }
    private val adapterCodigos = CodigoFalhaAdapter()
    private val adapterHistorico = LeituraObdAdapter()

    /** Ação que aguarda a concessão das permissões / ligar o Bluetooth. */
    private var acaoPendente: (() -> Unit)? = null

    private val pedirPermissoes =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (PermissoesBluetooth.concedidas(requireContext())) {
                garantirBluetooth(acaoPendente)
            } else {
                acaoPendente = null
                mostrarMensagem(getString(R.string.obd_permissao_negada))
            }
        }

    private val pedirLigarBluetooth =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { resultado ->
            val acao = acaoPendente
            acaoPendente = null
            if (resultado.resultCode == Activity.RESULT_OK) {
                acao?.invoke()
            } else {
                mostrarMensagem(getString(R.string.obd_bluetooth_desligado))
            }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentObdBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.listaCodigos.adapter = adapterCodigos
        binding.listaHistoricoLeituras.adapter = adapterHistorico
        configurarAcoes()
        observarViewModel()
    }

    private fun configurarAcoes() = with(binding) {
        grupoModo.addOnButtonCheckedListener { _, id, marcado ->
            if (!marcado) return@addOnButtonCheckedListener
            val modo = if (id == R.id.botao_modo_bluetooth) ModoObd.BLUETOOTH else ModoObd.SIMULADO
            if (modo != viewModel.modo.value) {
                viewModel.selecionarModo(modo)
                if (modo == ModoObd.BLUETOOTH) garantirBluetooth { viewModel.carregarDispositivos() }
            }
        }
        switchSimularFalha.setOnCheckedChangeListener { _, marcado -> viewModel.definirSimularFalha(marcado) }
        campoDispositivo.setOnItemClickListener { _, _, posicao, _ ->
            viewModel.dispositivos.value.getOrNull(posicao)?.let(viewModel::selecionarDispositivo)
        }
        botaoAtualizarDispositivos.setOnClickListener {
            garantirBluetooth { viewModel.carregarDispositivos() }
        }
        botaoConectar.setOnClickListener { garantirBluetooth { viewModel.conectar() } }
        botaoDesconectar.setOnClickListener { viewModel.desconectar() }
        botaoSalvarLeitura.setOnClickListener { viewModel.salvarLeitura() }
        botaoLerCodigos.setOnClickListener { viewModel.lerCodigos() }
        botaoLimparCodigos.setOnClickListener { confirmarLimpezaCodigos() }
    }

    /**
     * Antes de usar o Bluetooth real: exige permissões (em tempo de execução no Android 12+)
     * e Bluetooth ligado. No modo simulado executa a [acao] direto.
     */
    private fun garantirBluetooth(acao: (() -> Unit)?) {
        if (acao == null) return
        if (viewModel.modo.value == ModoObd.SIMULADO) {
            acao()
            return
        }
        val contexto = requireContext()
        when {
            !PermissoesBluetooth.disponivel(contexto) ->
                mostrarMensagem(getString(R.string.obd_sem_bluetooth))
            !PermissoesBluetooth.concedidas(contexto) -> {
                acaoPendente = acao
                pedirPermissoes.launch(PermissoesBluetooth.necessarias())
            }
            !PermissoesBluetooth.ligado(contexto) -> {
                acaoPendente = acao
                pedirLigarBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
            else -> acao()
        }
    }

    private fun confirmarLimpezaCodigos() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.obd_limpar_titulo)
            .setMessage(R.string.obd_limpar_mensagem)
            .setNegativeButton(R.string.botao_cancelar, null)
            .setPositiveButton(R.string.obd_limpar_confirmar) { _, _ -> viewModel.limparCodigos() }
            .show()
    }

    private fun observarViewModel() {
        // viewLifecycleOwner: a coleta acompanha a view do Fragment (para ao destruir a view).
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.modo.collect { mostrarModo(it) } }
                launch { viewModel.estado.collect { mostrarEstado(it) } }
                launch { viewModel.dispositivos.collect { mostrarDispositivos(it) } }
                launch { viewModel.dispositivoSelecionado.collect { mostrarDispositivoSelecionado(it) } }
                launch { viewModel.dados.collect { mostrarDados(it) } }
                launch { viewModel.codigos.collect { mostrarCodigos() } }
                launch { viewModel.ocupado.collect { atualizarBotoes() } }
                launch { viewModel.historicoLeituras.collect { mostrarHistorico(it) } }
                launch {
                    viewModel.simularFalha.collect { ativa ->
                        if (binding.switchSimularFalha.isChecked != ativa) binding.switchSimularFalha.isChecked = ativa
                    }
                }
                launch {
                    viewModel.eventos.collect { evento ->
                        mostrarMensagem(
                            when (evento) {
                                is EventoObd.Mensagem -> getString(evento.resId)
                                is EventoObd.Texto -> evento.texto
                            },
                        )
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ renderização

    private fun mostrarModo(modo: ModoObd) = with(binding) {
        grupoModo.check(if (modo == ModoObd.BLUETOOTH) R.id.botao_modo_bluetooth else R.id.botao_modo_simulado)
        // A opção de simular falha só faz sentido (e só aparece) no modo simulado.
        switchSimularFalha.isVisible = modo == ModoObd.SIMULADO
        layoutDispositivo.hint = getString(
            if (modo == ModoObd.BLUETOOTH) R.string.obd_dispositivo_pareado else R.string.obd_dispositivo,
        )
    }

    private fun mostrarEstado(estado: EstadoConexaoObd) {
        binding.textoEstado.text = when (estado) {
            EstadoConexaoObd.Desconectado -> getString(R.string.obd_estado_desconectado)
            EstadoConexaoObd.Conectando -> getString(R.string.obd_estado_conectando)
            EstadoConexaoObd.Conectado -> getString(R.string.obd_estado_conectado)
            is EstadoConexaoObd.Erro -> getString(R.string.obd_estado_erro, estado.mensagem)
        }
        atualizarBotoes()
    }

    private fun atualizarBotoes() = with(binding) {
        val estado = viewModel.estado.value
        val conectado = estado is EstadoConexaoObd.Conectado
        val livre = estado is EstadoConexaoObd.Desconectado || estado is EstadoConexaoObd.Erro
        val ocupado = viewModel.ocupado.value

        botaoConectar.isEnabled = livre
        botaoDesconectar.isEnabled = conectado
        // Modo, dispositivo e atualização da lista só mudam com o scanner desconectado.
        botaoModoSimulado.isEnabled = livre
        botaoModoBluetooth.isEnabled = livre
        layoutDispositivo.isEnabled = livre
        switchSimularFalha.isEnabled = livre
        botaoAtualizarDispositivos.isEnabled = livre
        botaoSalvarLeitura.isEnabled = conectado && viewModel.dados.value != null
        botaoLerCodigos.isEnabled = conectado && !ocupado
        botaoLimparCodigos.isEnabled = conectado && !ocupado
    }

    private fun mostrarDispositivos(lista: List<DispositivoObd>) {
        binding.campoDispositivo.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, lista.map { it.nome }),
        )
        mostrarDispositivoSelecionado(viewModel.dispositivoSelecionado.value)
    }

    private fun mostrarDispositivoSelecionado(dispositivo: DispositivoObd?) {
        // filter = false: mantém a lista completa no dropdown mesmo com um item já escolhido.
        binding.campoDispositivo.setText(dispositivo?.nome.orEmpty(), false)
        binding.layoutDispositivo.helperText =
            if (dispositivo == null && viewModel.modo.value == ModoObd.BLUETOOTH) {
                getString(R.string.obd_sem_pareados)
            } else {
                null
            }
    }

    private fun mostrarDados(dados: DadosObd?) = with(binding) {
        val vazio = getString(R.string.valor_indisponivel)
        valorRpm.text = dados?.rpm?.let { getString(R.string.obd_valor_rpm, it.comoMilhar()) } ?: vazio
        valorVelocidade.text = dados?.velocidade?.let { getString(R.string.obd_valor_velocidade, it) } ?: vazio
        valorTemperatura.text = dados?.temperaturaMotor?.let { getString(R.string.obd_valor_temperatura, it) } ?: vazio
        valorCarga.text = dados?.cargaMotor?.let { getString(R.string.obd_valor_carga, it.comoDecimal(1)) } ?: vazio
        valorBateria.text = dados?.tensaoBateria?.let { getString(R.string.obd_valor_bateria, it.comoDecimal(1)) } ?: vazio
        atualizarBotoes()
    }

    private fun mostrarHistorico(leituras: List<LeituraObd>) {
        adapterHistorico.submitList(leituras)
        binding.textoHistoricoVazio.isVisible = leituras.isEmpty()
    }

    private fun mostrarCodigos() = with(binding) {
        val codigos = viewModel.codigos.value
        adapterCodigos.submitList(codigos.orEmpty())
        textoCodigosVazio.isVisible = codigos.isNullOrEmpty()
        textoCodigosVazio.setText(if (codigos == null) R.string.obd_codigos_nao_lidos else R.string.obd_sem_codigos)
    }

    private fun mostrarMensagem(texto: String) {
        if (texto.isNotBlank()) Snackbar.make(requireView(), texto, Snackbar.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------------ ciclo de vida

    // O polling de PIDs só roda com a aba visível. Em um ViewPager2 a aba vizinha pode ficar
    // em STARTED sem estar em RESUMED, por isso pausamos também em onPause.
    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart: retoma o polling de PIDs")
        viewModel.retomarPolling()
    }

    override fun onResume() {
        super.onResume()
        viewModel.retomarPolling()
    }

    override fun onPause() {
        viewModel.pausarPolling()
        super.onPause()
    }

    override fun onStop() {
        Log.d(TAG, "onStop: pausa o polling de PIDs")
        viewModel.pausarPolling()
        super.onStop()
    }

    override fun onDestroyView() {
        Log.d(TAG, "onDestroyView: libera o binding")
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val TAG = "ObdFragment"

        fun novo(veiculoId: Long) = ObdFragment().apply {
            arguments = Bundle().apply { putLong(DiagnosticoActivity.EXTRA_VEICULO_ID, veiculoId) }
        }
    }
}
