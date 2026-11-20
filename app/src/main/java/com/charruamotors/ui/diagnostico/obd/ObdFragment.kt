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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.charruamotors.R
import com.charruamotors.databinding.FragmentObdBinding
import com.charruamotors.domain.calculo.EstadoCronometro
import com.charruamotors.domain.calculo.MetodoConsumo
import com.charruamotors.domain.model.TipoCombustivel
import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.domain.model.LeituraObd
import com.charruamotors.obd.DadosObd
import com.charruamotors.obd.DispositivoObd
import com.charruamotors.obd.ModoObd
import com.charruamotors.obd.ResultadoPerfil
import com.charruamotors.obd.pid.Pids
import com.charruamotors.ui.diagnostico.DiagnosticoActivity
import com.charruamotors.ui.util.ArquivoExportado
import com.charruamotors.ui.util.ExportadorCsv
import com.charruamotors.ui.util.PermissoesBluetooth
import com.charruamotors.ui.util.comoDecimal
import com.charruamotors.ui.util.comoMilhar
import com.charruamotors.ui.util.comoNumero
import com.charruamotors.ui.util.rotulo
import com.charruamotors.ui.componentes.PontoTelemetria
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/**
 * Aba 1 — Scanner OBD2: conexão, painel de telemetria (mostradores, gráfico ao vivo, computador
 * de bordo, cronômetro 0–100 km/h e grade de todos os PIDs suportados, inclusive os da
 * montadora), Data Logger CSV e diagnóstico completo (VIN, falhas por categoria, Freeze Frame).
 */
class ObdFragment : Fragment() {

    // Binding só é válido entre onCreateView e onDestroyView; é liberado em onDestroyView.
    private var _binding: FragmentObdBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ObdViewModel by viewModels { ObdViewModel.Factory }
    private val adapterCodigos = DiagnosticoDtcAdapter()
    private val adapterSensores = SensorAdapter()
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
        binding.listaSensores.layoutManager = GridLayoutManager(requireContext(), colunasDaGrade())
        binding.listaSensores.adapter = adapterSensores
        // A grade muda a cada ciclo de polling: sem animação de "change" para não piscar.
        binding.listaSensores.itemAnimator = null
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
        botaoGravarLog.setOnClickListener { viewModel.alternarLog() }
        botaoArmarCronometro.setOnClickListener { viewModel.alternarCronometro() }
        botaoZerarViagem.setOnClickListener { viewModel.zerarViagem() }
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
                launch { viewModel.sensores.collect { mostrarSensores(it) } }
                launch { viewModel.infoEcu.collect { mostrarInfoEcu(it) } }
                launch { viewModel.diagnostico.collect { mostrarDiagnostico(it) } }
                launch { viewModel.estadoLog.collect { mostrarEstadoLog(it) } }
                launch { viewModel.computador.collect { mostrarComputador(it) } }
                launch { viewModel.cronometro.collect { mostrarCronometro(it) } }
                launch { viewModel.grafico.collect { mostrarGrafico(it) } }
                launch { viewModel.ocupado.collect { atualizarBotoes() } }
                launch { viewModel.historicoLeituras.collect { mostrarHistorico(it) } }
                launch {
                    viewModel.simularFalha.collect { ativa ->
                        if (binding.switchSimularFalha.isChecked != ativa) binding.switchSimularFalha.isChecked = ativa
                    }
                }
                launch {
                    viewModel.eventos.collect { evento ->
                        when (evento) {
                            is EventoObd.Mensagem -> mostrarMensagem(getString(evento.resId))
                            is EventoObd.Texto -> mostrarMensagem(evento.texto)
                            is EventoObd.LogSalvo -> oferecerCompartilhamento(evento.arquivo)
                        }
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
        mostrarInfoEcu(viewModel.infoEcu.value)
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
        // Gravando: o botão continua ativo para permitir parar mesmo após a queda da conexão.
        botaoGravarLog.isEnabled = conectado || viewModel.estadoLog.value.gravando
        botaoArmarCronometro.isEnabled = conectado || viewModel.cronometro.value.estado.armado
        botaoZerarViagem.isEnabled = viewModel.computador.value.viagem.segundos > 0
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

    /** Mostradores principais: RPM, velocidade, MAP/turbo e temperatura do motor. */
    private fun mostrarDados(dados: DadosObd?) = with(binding) {
        gaugeRpm.definirValor(dados?.get(Pids.RPM))
        gaugeVelocidade.definirValor(dados?.get(Pids.VELOCIDADE))
        gaugeMap.definirValor(dados?.get(Pids.PRESSAO_COLETOR))
        gaugeTemperatura.definirValor(dados?.get(Pids.TEMPERATURA_MOTOR))
        atualizarBotoes()
    }

    private fun mostrarSensores(sensores: List<LeituraSensor>) = with(binding) {
        adapterSensores.submitList(sensores)
        textoSensoresVazio.isVisible = sensores.isEmpty()
        tituloSensores.text = if (sensores.isEmpty()) {
            getString(R.string.obd_sensores_titulo)
        } else {
            getString(R.string.obd_sensores_titulo_qtd, sensores.size)
        }
    }

    private fun mostrarInfoEcu(info: InfoEcu) = with(binding) {
        val conectado = viewModel.estado.value is EstadoConexaoObd.Conectado
        textoVin.text = when {
            !conectado -> getString(R.string.obd_ecu_desconectado)
            !info.vinLido -> getString(R.string.obd_ecu_vin_lendo)
            else -> info.vin ?: getString(R.string.obd_ecu_vin_indisponivel)
        }
        textoPidsSuportados.text = if (conectado) {
            getString(R.string.obd_ecu_pids_qtd, info.quantidadePids, viewModel.totalPidsCatalogo)
        } else {
            getString(R.string.valor_indisponivel)
        }
        val perfil = info.perfil
        textoPerfilFabricante.text = when {
            !conectado -> getString(R.string.valor_indisponivel)
            perfil == null -> getString(R.string.obd_perfil_verificando)
            perfil is ResultadoPerfil.SemPerfil -> getString(R.string.obd_perfil_sem)
            perfil is ResultadoPerfil.Indisponivel -> getString(R.string.obd_perfil_indisponivel, perfil.fabricante, perfil.motivo)
            perfil is ResultadoPerfil.Ativo ->
                resources.getQuantityString(R.plurals.obd_perfil_ativo, perfil.pids.size, perfil.fabricante, perfil.pids.size)
            else -> ""
        }
        val observacao = (perfil as? ResultadoPerfil.Ativo)?.observacao?.takeIf { conectado && it.isNotBlank() }
        textoPerfilObservacao.isVisible = observacao != null
        textoPerfilObservacao.text = observacao
    }

    /** Computador de bordo: km/L e L/h instantâneos, método usado e média da viagem. */
    private fun mostrarComputador(ui: ComputadorBordoUi) = with(binding) {
        val vazio = getString(R.string.valor_indisponivel)
        val consumo = ui.consumo
        textoConsumoKmL.text = when {
            consumo == null -> vazio
            consumo.kmPorLitro == null -> getString(R.string.obd_bordo_parado)
            else -> getString(R.string.obd_bordo_km_l, consumo.kmPorLitro.coerceAtMost(MAX_KM_L_EXIBIDO).comoNumero(1))
        }
        textoConsumoLH.text = consumo?.let { getString(R.string.obd_bordo_l_h, it.litrosPorHora.comoNumero(1)) } ?: vazio
        textoMetodoConsumo.isVisible = consumo != null
        textoMetodoConsumo.text = when (consumo?.metodo) {
            MetodoConsumo.MAF -> getString(R.string.obd_bordo_metodo_maf)
            MetodoConsumo.SPEED_DENSITY -> getString(R.string.obd_bordo_metodo_sd)
            MetodoConsumo.SENSOR_ECU -> getString(R.string.obd_bordo_metodo_ecu)
            null -> ""
        }
        textoMediaViagem.text = ui.viagem.mediaKmPorLitro?.let { getString(R.string.obd_bordo_km_l, it.comoNumero(1)) } ?: vazio
        textoTotaisViagem.text = getString(
            R.string.obd_bordo_totais, ui.viagem.distanciaKm.comoNumero(1), ui.viagem.litros.comoNumero(2),
        )

        val conectado = viewModel.estado.value is EstadoConexaoObd.Conectado
        val etanol = viewModel.dados.value?.get(Pids.PERCENTUAL_ETANOL)
        val combustivel = ui.motor.tipoCombustivel.rotulo(requireContext())
        val cilindrada = ui.motor.cilindradaLitros.comoNumero(1)
        textoParametrosMotor.text = when {
            conectado && consumo == null && ui.motor.tipoCombustivel == TipoCombustivel.DIESEL ->
                getString(R.string.obd_bordo_diesel_sem_ecu)
            conectado && consumo == null -> getString(R.string.obd_bordo_sem_dados)
            ui.motor.tipoCombustivel == TipoCombustivel.FLEX && etanol != null ->
                getString(R.string.obd_bordo_motor_etanol, cilindrada, combustivel, etanol.comoNumero(0))
            else -> getString(R.string.obd_bordo_motor, cilindrada, combustivel)
        }
        botaoZerarViagem.isEnabled = ui.viagem.segundos > 0
    }

    /** Cronômetro 0–100: tempo (ao vivo ou final), instrução do estado e melhor tempo. */
    private fun mostrarCronometro(ui: CronometroUi) = with(binding) {
        val estado = ui.estado
        textoCronometroTempo.text = getString(
            R.string.obd_cronometro_tempo,
            when (estado) {
                is EstadoCronometro.Medindo -> segundos(estado.decorridoMs, 1)
                is EstadoCronometro.Concluido -> segundos(estado.tempoMs, 2)
                else -> segundos(0, 2)
            },
        )
        textoCronometroStatus.text = when (estado) {
            EstadoCronometro.Desarmado -> getString(R.string.obd_cronometro_desarmado)
            EstadoCronometro.AguardandoParada -> getString(R.string.obd_cronometro_aguardando)
            EstadoCronometro.Pronto -> getString(R.string.obd_cronometro_pronto)
            is EstadoCronometro.Medindo -> getString(R.string.obd_cronometro_medindo, estado.velocidade.toInt())
            is EstadoCronometro.Concluido -> getString(R.string.obd_cronometro_concluido)
        }
        textoCronometroMelhor.isVisible = ui.melhorTempoMs != null
        textoCronometroMelhor.text = ui.melhorTempoMs?.let { getString(R.string.obd_cronometro_melhor, segundos(it, 2)) }
        botaoArmarCronometro.setText(
            when {
                estado.armado -> R.string.obd_cronometro_cancelar
                estado is EstadoCronometro.Concluido -> R.string.obd_cronometro_novamente
                else -> R.string.obd_cronometro_armar
            },
        )
        atualizarBotoes()
    }

    private fun segundos(ms: Long, casas: Int): String = (ms / 1000.0).comoNumero(casas)

    private fun mostrarGrafico(pontos: List<PontoTelemetria>) {
        binding.graficoTelemetria.definirPontos(pontos)
    }

    private fun mostrarEstadoLog(log: EstadoLog) = with(binding) {
        botaoGravarLog.isChecked = log.gravando
        botaoGravarLog.text = if (log.gravando) {
            getString(R.string.obd_log_gravando, log.amostras)
        } else {
            getString(R.string.obd_log_gravar)
        }
        atualizarBotoes()
    }

    private fun mostrarHistorico(leituras: List<LeituraObd>) {
        adapterHistorico.submitList(leituras)
        binding.textoHistoricoVazio.isVisible = leituras.isEmpty()
    }

    private fun mostrarDiagnostico(resultado: ResultadoDiagnostico?) = with(binding) {
        adapterCodigos.submitList(resultado?.itens().orEmpty())
        textoCodigosVazio.isVisible = resultado == null
        mostrarFreezeFrame(resultado)
    }

    private fun mostrarFreezeFrame(resultado: ResultadoDiagnostico?) = with(binding) {
        cardFreezeFrame.isVisible = resultado != null
        if (resultado == null) return@with
        val ff = resultado.freezeFrame
        if (ff == null || ff.vazio) {
            grupoFfDados.isVisible = false
            textoFfVazio.isVisible = true
            textoFfVazio.setText(if (ff == null) R.string.obd_ff_nao_suportado else R.string.obd_ff_vazio)
            return@with
        }
        grupoFfDados.isVisible = true
        textoFfVazio.isVisible = false
        val vazio = getString(R.string.valor_indisponivel)
        textoFfDtc.text = ff.dtc ?: getString(R.string.obd_ff_sem_dtc)
        textoFfDescricao.isVisible = resultado.infoFreezeFrame != null
        textoFfDescricao.text = resultado.infoFreezeFrame?.titulo
        valorFfRpm.text = ff.rpm?.let { getString(R.string.obd_valor_rpm, it.comoMilhar()) } ?: vazio
        valorFfVelocidade.text = ff.velocidade?.let { getString(R.string.obd_valor_velocidade, it) } ?: vazio
        valorFfTemperatura.text = ff.temperaturaMotor?.let { getString(R.string.obd_valor_temperatura, it) } ?: vazio
        valorFfCarga.text = ff.cargaMotor?.let { getString(R.string.obd_valor_carga, it.comoDecimal(1)) } ?: vazio
    }

    /** Log salvo: Snackbar com o local do arquivo e a ação de compartilhar. */
    private fun oferecerCompartilhamento(arquivo: ArquivoExportado) {
        val local = arquivo.local + "/" + arquivo.nome
        Snackbar.make(requireView(), getString(R.string.obd_log_salvo, local), Snackbar.LENGTH_INDEFINITE)
            .setAction(R.string.obd_log_compartilhar) {
                val titulo = getString(R.string.obd_log_compartilhar_titulo)
                runCatching { startActivity(ExportadorCsv.intentCompartilhar(arquivo, titulo)) }
                    .onFailure { mostrarMensagem(it.message ?: "") }
            }
            .show()
    }

    /** 2 colunas no celular em pé; mais colunas em telas largas (paisagem/tablet). */
    private fun colunasDaGrade(): Int =
        (resources.configuration.screenWidthDp / LARGURA_MINIMA_CELULA_DP).coerceIn(2, 5)

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
        private const val LARGURA_MINIMA_CELULA_DP = 170
        /** Em descidas com corte de combustível o km/L instantâneo dispara; acima disso mostra o teto. */
        private const val MAX_KM_L_EXIBIDO = 99.9

        fun novo(veiculoId: Long) = ObdFragment().apply {
            arguments = Bundle().apply { putLong(DiagnosticoActivity.EXTRA_VEICULO_ID, veiculoId) }
        }
    }
}
