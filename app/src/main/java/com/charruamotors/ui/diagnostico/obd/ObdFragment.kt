package com.charruamotors.ui.diagnostico.obd

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.content.res.ColorStateList
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.R
import com.charruamotors.databinding.DialogRegistroComunicacaoBinding
import com.charruamotors.databinding.FragmentObdBinding
import com.charruamotors.direcao.hud.HudFlutuanteService
import com.charruamotors.direcao.hud.TelemetriaCompartilhada
import com.charruamotors.direcao.midia.ControladorMidia
import com.charruamotors.direcao.midia.EstadoMidia
import com.charruamotors.direcao.waze.AbridorWaze
import com.charruamotors.direcao.waze.AcaoWaze
import com.charruamotors.domain.calculo.EstadoCronometro
import com.charruamotors.domain.calculo.MetodoConsumo
import com.charruamotors.domain.model.TipoCombustivel
import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.domain.model.LeituraObd
import com.charruamotors.obd.DadosObd
import com.charruamotors.obd.DispositivoObd
import com.charruamotors.obd.InfoConexao
import com.charruamotors.obd.ModoComunicacaoEcu
import com.charruamotors.obd.ModoObd
import com.charruamotors.obd.ResultadoPerfil
import com.charruamotors.obd.log.LogsSessaoSalvos
import com.charruamotors.obd.pid.Pids
import com.charruamotors.obd.sessao.ConfigAutoConexao
import com.charruamotors.obd.sessao.EstadoSessao
import com.charruamotors.obd.sessao.FaseSessao
import com.charruamotors.obd.sessao.SessaoObd
import com.charruamotors.sistema.StatusCaixaPreta
import com.charruamotors.sistema.unidades.UnidadePressao
import com.charruamotors.ui.componentes.EscalaGauge
import com.charruamotors.ui.configuracoes.ConfiguracoesActivity
import com.charruamotors.ui.diagnostico.DiagnosticoActivity
import com.charruamotors.ui.oficina.bateria.TesteBateriaActivity
import com.charruamotors.ui.oficina.replay.ReplayLogActivity
import com.charruamotors.ui.oficina.terminal.TerminalObdActivity
import com.charruamotors.ui.util.ArquivoExportado
import com.charruamotors.ui.util.ExportadorCsv
import com.charruamotors.ui.util.PermissoesBluetooth
import com.charruamotors.ui.util.comoDecimal
import com.charruamotors.ui.util.comoMilhar
import com.charruamotors.ui.util.comoNumero
import com.charruamotors.ui.util.definirTexto
import com.charruamotors.ui.util.rotulo
import com.charruamotors.ui.componentes.PontoTelemetria
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/**
 * Aba 1 — Scanner OBD2: card de direção (mini-player do Spotify/YouTube Music, atalhos do Waze e
 * Modo HUD flutuante), conexão, hub "Ferramentas de Oficina" (teste de bateria e alternador,
 * replay de logs CSV, terminal ELM327 e varredura de PIDs), painel de telemetria (mostradores, gráfico ao vivo, computador
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

    /** A aba está em primeiro plano (entre onResume e onPause)? */
    private var emPrimeiroPlano = false

    /** Mini-player (sessões de mídia do Android); criado com a view, ligado entre onStart e onStop. */
    private var controladorMidia: ControladorMidia? = null

    private val app: CharruaMotorsApp
        get() = requireActivity().application as CharruaMotorsApp

    private val telemetriaHud: TelemetriaCompartilhada
        get() = app.telemetriaCompartilhada

    /** Unidade do Turbo/MAP escolhida nas Configurações (kPa, bar ou psi). */
    private var unidadePressao: UnidadePressao = UnidadePressao.PADRAO

    /** A permissão de notificação (opcional, só para ver o "Fechar HUD" na gaveta) já foi pedida? */
    private var notificacaoPerguntada = false

    // Modo HUD: "Aparecer sobre outros apps" → Bluetooth (pré-requisito do serviço connectedDevice
    // no Android 14+) → notificação (opcional). Cada retorno continua a sequência em ativarHud().
    private val pedirSobreposicao =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(requireContext())) ativarHud() else mostrarMensagem(getString(R.string.hud_permissao_overlay))
        }

    private val pedirBluetoothHud =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (PermissoesBluetooth.concedidas(requireContext())) ativarHud() else mostrarMensagem(getString(R.string.hud_permissao_bluetooth))
        }

    private val pedirNotificacao =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ativarHud() }

    private val pedirPermissoes =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { resultado ->
            if (PermissoesBluetooth.concedidas(requireContext())) {
                garantirBluetooth(acaoPendente)
                return@registerForActivityResult
            }
            acaoPendente = null
            // Negada duas vezes (ou "não perguntar de novo"): o Android nem mostra mais o pedido.
            val bloqueada = resultado.filterValues { !it }.keys.any { !shouldShowRequestPermissionRationale(it) }
            if (bloqueada) oferecerConfiguracoes() else mostrarMensagem(getString(R.string.obd_permissao_negada))
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
        controladorMidia = ControladorMidia(requireContext())
        configurarAcoes()
        configurarDirecao()
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
        botaoRegistroComunicacao.setOnClickListener { mostrarRegistroComunicacao() }
        configurarOficina()
        configurarCaixaPreta()
    }

    // ------------------------------------------------------------------ caixa-preta (segundo plano)

    /**
     * Faixa discreta da caixa-preta (`Caixa-preta ativa · Gatilho: Toyota SW4`). A configuração
     * completa (auto-conexão, logs, gatilho, vínculos e bateria) fica nas Configurações.
     */
    private fun configurarCaixaPreta() {
        binding.faixaCaixaPreta.setOnClickListener { startActivity(ConfiguracoesActivity.intent(requireContext())) }
    }

    private fun mostrarFaixaCaixaPreta(config: ConfigAutoConexao, estado: EstadoSessao) = with(binding) {
        val texto = StatusCaixaPreta.faixa(config, estado)
        textoFaixaCaixaPreta.definirTexto(texto)
        faixaCaixaPreta.contentDescription = getString(R.string.obd_faixa_caixa_descricao, texto)
        val cor = when {
            estado.ativa && estado.fase == FaseSessao.Conectada -> R.color.status_ok
            config.autoConexaoAtiva || estado.ativa -> R.color.azul_primario
            else -> R.color.status_espera
        }
        pontoCaixaPreta.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), cor))
    }

    private fun mostrarEstadoSessao(estado: EstadoSessao) {
        mostrarFaixaCaixaPreta(viewModel.configAuto.value, estado)
        mostrarEstado(viewModel.estado.value)
    }

    /** Sessão encerrada com os logs automáticos salvos: atalho para o visualizador. */
    private fun oferecerLogsSessao(logs: LogsSessaoSalvos) {
        val arquivo = logs.txt ?: logs.csv ?: return
        Snackbar.make(requireView(), getString(R.string.caixa_logs_salvos, arquivo.local), Snackbar.LENGTH_LONG)
            .setAction(R.string.caixa_ver_logs) { startActivity(ReplayLogActivity.intent(requireContext())) }
            .show()
    }

    // ------------------------------------------------------------------ Ferramentas de Oficina

    /**
     * Hub "Ferramentas de Oficina". Cada ferramenta abre numa tela própria: na bateria e no
     * terminal, a sessão manual do cliente suspende o polling da sessão compartilhada (e do HUD);
     * ao voltar, o cliente restaura o `ATSH` e o polling retoma, sem fechar o socket Bluetooth.
     */
    private fun configurarOficina() = with(binding) {
        val veiculoId = requireArguments().getLong(DiagnosticoActivity.EXTRA_VEICULO_ID)
        botaoOficinaBateria.setOnClickListener {
            abrirFerramentaConectada { TesteBateriaActivity.intent(requireContext(), veiculoId) }
        }
        botaoOficinaReplay.setOnClickListener { startActivity(ReplayLogActivity.intent(requireContext())) }
        botaoOficinaTerminal.setOnClickListener {
            abrirFerramentaConectada { TerminalObdActivity.intent(requireContext(), veiculoId) }
        }
    }

    private fun abrirFerramentaConectada(intent: () -> Intent) {
        if (viewModel.estado.value !is EstadoConexaoObd.Conectado) {
            mostrarMensagem(getString(R.string.oficina_requer_conexao))
            return
        }
        startActivity(intent())
    }

    // ------------------------------------------------------------------ direção: música, Waze e HUD

    private fun configurarDirecao() = with(binding) {
        botaoMidiaAnterior.setOnClickListener { controladorMidia?.anterior() }
        botaoMidiaTocar.setOnClickListener { controladorMidia?.tocarPausar() }
        botaoMidiaProxima.setOnClickListener { controladorMidia?.proxima() }
        areaAbrirPlayer.setOnClickListener {
            if (controladorMidia?.abrirPlayer() != true) mostrarMensagem(getString(R.string.midia_erro_abrir))
        }
        botaoAtivarMidia.setOnClickListener { abrirAcessoNotificacoes() }
        botaoWazeAbrir.setOnClickListener { abrirWaze(AcaoWaze.ABRIR) }
        botaoWazePosto.setOnClickListener { abrirWaze(AcaoWaze.POSTO) }
        botaoWazeOficina.setOnClickListener { abrirWaze(AcaoWaze.OFICINA) }
        botaoHud.setOnClickListener {
            if (telemetriaHud.hudAtivo.value) HudFlutuanteService.parar(requireContext()) else ativarHud()
        }
    }

    private fun abrirWaze(acao: AcaoWaze) {
        if (!AbridorWaze.abrir(requireContext(), acao)) mostrarMensagem(getString(R.string.waze_erro))
    }

    /** Tela do "Acesso a notificações"; se o aparelho não a tiver, as informações do app. */
    private fun abrirAcessoNotificacoes() {
        val abriu = runCatching { startActivity(ControladorMidia.intentPermissao()) }.isSuccess
        if (!abriu) {
            val detalhes = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", requireContext().packageName, null))
            runCatching { startActivity(detalhes) }
        }
    }

    /**
     * "Modo HUD (sobre Waze)": garante as permissões, sobe o painel flutuante e vai para o Waze.
     * O app fica minimizado com a conexão OBD2 e o polling rodando na sessão compartilhada.
     */
    private fun ativarHud() {
        val contexto = requireContext()
        when {
            !Settings.canDrawOverlays(contexto) -> {
                val tela = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + contexto.packageName))
                runCatching { pedirSobreposicao.launch(tela) }
                    .onFailure { mostrarMensagem(getString(R.string.hud_permissao_overlay)) }
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !PermissoesBluetooth.concedidas(contexto) ->
                pedirBluetoothHud.launch(PermissoesBluetooth.necessarias())
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificacaoPerguntada &&
                ContextCompat.checkSelfPermission(contexto, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> {
                notificacaoPerguntada = true
                pedirNotificacao.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            else -> {
                HudFlutuanteService.iniciar(contexto)
                abrirWaze(AcaoWaze.ABRIR)
            }
        }
    }

    private fun mostrarMidia(estado: EstadoMidia) = with(binding) {
        grupoMidia.isVisible = estado is EstadoMidia.Ativo
        textoMidiaVazio.isVisible = estado is EstadoMidia.SemPlayer
        grupoMidiaPermissao.isVisible = estado is EstadoMidia.SemPermissao
        if (estado !is EstadoMidia.Ativo) return@with
        textoTituloMidia.definirTexto(estado.tituloExibicao)
        textoArtistaMidia.definirTexto(estado.subtituloExibicao)
        textoArtistaMidia.isVisible = estado.subtituloExibicao.isNotEmpty()
        val capa = estado.capa
        if (capa != null) {
            imagemCapaMidia.scaleType = ImageView.ScaleType.CENTER_CROP
            imagemCapaMidia.setImageBitmap(capa)
        } else {
            imagemCapaMidia.scaleType = ImageView.ScaleType.CENTER_INSIDE
            imagemCapaMidia.setImageResource(R.drawable.ic_musica)
        }
        botaoMidiaTocar.setImageResource(if (estado.tocando) R.drawable.ic_pausar else R.drawable.ic_tocar)
        botaoMidiaTocar.contentDescription = getString(if (estado.tocando) R.string.midia_pausar else R.string.midia_tocar)
    }

    private fun mostrarHud(ativo: Boolean) = with(binding) {
        botaoHud.definirTexto(getString(if (ativo) R.string.hud_desativar else R.string.hud_ativar))
        botaoHud.setIconResource(if (ativo) R.drawable.ic_fechar else R.drawable.ic_hud)
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
                try {
                    pedirLigarBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                } catch (e: ActivityNotFoundException) {
                    acaoPendente = null
                    mostrarMensagem(getString(R.string.obd_bluetooth_desligado))
                } catch (e: SecurityException) {
                    acaoPendente = null
                    mostrarMensagem(getString(R.string.obd_permissao_negada))
                }
            }
            else -> acao()
        }
    }

    /** Permissão bloqueada: só dá para liberar pela tela de configurações do app. */
    private fun oferecerConfiguracoes() {
        Snackbar.make(requireView(), getString(R.string.obd_permissao_bloqueada), Snackbar.LENGTH_LONG)
            .setAction(R.string.obd_permissao_configuracoes) {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", requireContext().packageName, null))
                runCatching { startActivity(intent) }
            }
            .show()
    }

    /**
     * Log de campo: as últimas trocas com o ELM327 (inicialização, `ATDP`, `ATRV`, `0100`, perfil
     * de montadora e erros do polling), com opção de copiar ou compartilhar.
     */
    private fun mostrarRegistroComunicacao() {
        val texto = viewModel.textoRegistroComunicacao()
        if (texto == null) {
            mostrarMensagem(getString(R.string.obd_registro_vazio))
            return
        }
        val conteudo = DialogRegistroComunicacaoBinding.inflate(layoutInflater)
        conteudo.textoRegistro.text = texto
        // Abre no fim: o que interessa costuma ser a última falha.
        conteudo.root.post { conteudo.root.fullScroll(View.FOCUS_DOWN) }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.obd_registro_titulo)
            .setView(conteudo.root)
            .setNegativeButton(R.string.obd_registro_compartilhar) { _, _ ->
                val envio = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.obd_registro_rotulo))
                    .putExtra(Intent.EXTRA_TEXT, texto)
                runCatching { startActivity(Intent.createChooser(envio, getString(R.string.obd_registro_rotulo))) }
            }
            .setPositiveButton(R.string.obd_registro_copiar) { _, _ ->
                val area = requireContext().getSystemService(ClipboardManager::class.java)
                area?.setPrimaryClip(ClipData.newPlainText(getString(R.string.obd_registro_rotulo), texto))
                // Android 13+ já mostra a confirmação do sistema ao copiar.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) mostrarMensagem(getString(R.string.obd_registro_copiado))
            }
            .setNeutralButton(R.string.botao_fechar, null)
            .show()
    }

    /**
     * Mantém a tela acesa enquanto o scanner está conectado (ou gravando o log), só com esta aba
     * em primeiro plano. A flag é da janela: sai em [onPause] (troca de aba, app em segundo plano)
     * e ao desconectar, então a tela volta a apagar normalmente.
     */
    private fun aplicarTelaAcesa() {
        val janela = activity?.window ?: return
        if (emPrimeiroPlano && viewModel.manterTelaAcesa.value) {
            janela.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            janela.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
                launch { viewModel.manterTelaAcesa.collect { aplicarTelaAcesa() } }
                launch { viewModel.historicoLeituras.collect { mostrarHistorico(it) } }
                controladorMidia?.let { midia -> launch { midia.estado.collect { mostrarMidia(it) } } }
                launch { telemetriaHud.hudAtivo.collect { mostrarHud(it) } }
                launch { viewModel.configAuto.collect { mostrarFaixaCaixaPreta(it, viewModel.estadoSessao.value) } }
                launch { app.preferenciasApp.config.collect { aplicarUnidadePressao(it.unidadePressao) } }
                launch { viewModel.estadoSessao.collect { mostrarEstadoSessao(it) } }
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
                            is EventoObd.ErroConexao -> mostrarErroConexao(evento.texto)
                            is EventoObd.LogsSessao -> oferecerLogsSessao(evento.logs)
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
        val sessao = viewModel.estadoSessao.value
        val aguardandoSessao = sessao.ativa && estado !is EstadoConexaoObd.Conectado && estado !is EstadoConexaoObd.Conectando
        binding.textoEstado.definirTexto(when {
            // Sessão esperando a ECU ou entre tentativas: o estado do cliente ainda não diz tudo.
            aguardandoSessao -> SessaoObd.descreverStatus(sessao)
            estado is EstadoConexaoObd.Erro -> getString(R.string.obd_estado_erro, estado.mensagem)
            estado is EstadoConexaoObd.Conectando -> getString(R.string.obd_estado_conectando)
            estado is EstadoConexaoObd.Conectado -> getString(R.string.obd_estado_conectado)
            else -> getString(R.string.obd_estado_desconectado)
        })
        atualizarBotoes()
    }

    private fun atualizarBotoes() = with(binding) {
        val estado = viewModel.estado.value
        val conectado = estado is EstadoConexaoObd.Conectado
        val sessaoAtiva = viewModel.estadoSessao.value.ativa
        val livre = (estado is EstadoConexaoObd.Desconectado || estado is EstadoConexaoObd.Erro) && !sessaoAtiva
        val ocupado = viewModel.ocupado.value

        botaoConectar.isEnabled = livre
        // Conectando (ou a sessão automática esperando a ECU / entre tentativas): o mesmo botão
        // cancela (a conexão RFCOMM com um scanner fora de alcance leva ~10 s).
        val conectando = estado is EstadoConexaoObd.Conectando || (sessaoAtiva && !conectado)
        botaoDesconectar.isEnabled = conectado || conectando
        botaoDesconectar.definirTexto(getString(if (conectando) R.string.obd_cancelar_conexao else R.string.obd_desconectar))
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
        // Bateria e terminal usam a conexão ativa; o replay de logs funciona sempre.
        botaoOficinaBateria.isEnabled = conectado && !ocupado
        botaoOficinaTerminal.isEnabled = conectado && !ocupado
        textoOficinaRequerConexao.isVisible = !conectado
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
        gaugeMap.definirValor(unidadePressao.deKpa(dados?.get(Pids.PRESSAO_COLETOR)))
        gaugeTemperatura.definirValor(dados?.get(Pids.TEMPERATURA_MOTOR))
        atualizarBotoes()
    }

    /** Configurações → Unidade de pressão: escala e unidade do mostrador de Turbo/MAP e da grade. */
    private fun aplicarUnidadePressao(unidade: UnidadePressao) = with(binding) {
        if (unidade == unidadePressao && gaugeMap.unidade == unidade.simbolo) return@with
        unidadePressao = unidade
        val faixa = unidade.mostradorMap
        gaugeMap.unidade = unidade.simbolo
        gaugeMap.casasDecimais = unidade.casasDecimais
        gaugeMap.escala = EscalaGauge(
            minimo = 0f,
            maximo = faixa.maximo.toFloat(),
            zonaVermelha = faixa.zonaVermelha?.toFloat(),
            divisoes = faixa.divisoes,
            subdivisoes = faixa.subdivisoes,
        )
        gaugeMap.definirValor(unidade.deKpa(viewModel.dados.value?.get(Pids.PRESSAO_COLETOR)), animar = false)
        adapterSensores.definirUnidadePressao(unidade)
    }

    private fun mostrarSensores(sensores: List<LeituraSensor>) = with(binding) {
        // A cada ciclo só os valores mudam: com tamanho fixo, a grade rebinda as células sem pedir
        // um novo layout ao ScrollView (que remediria a aba inteira 4 vezes por segundo). Quando
        // entram ou saem PIDs (conexão, perfil de montadora), a grade precisa crescer de verdade.
        listaSensores.setHasFixedSize(sensores.size == adapterSensores.itemCount)
        adapterSensores.submitList(sensores)
        textoSensoresVazio.isVisible = sensores.isEmpty()
        tituloSensores.definirTexto(if (sensores.isEmpty()) {
            getString(R.string.obd_sensores_titulo)
        } else {
            getString(R.string.obd_sensores_titulo_qtd, sensores.size)
        })
    }

    private fun mostrarInfoEcu(info: InfoEcu) = with(binding) {
        val conectado = viewModel.estado.value is EstadoConexaoObd.Conectado
        textoVin.definirTexto(when {
            !conectado -> getString(R.string.obd_ecu_desconectado)
            !info.vinLido -> getString(R.string.obd_ecu_vin_lendo)
            else -> info.vin ?: getString(R.string.obd_ecu_vin_indisponivel)
        })
        textoPidsSuportados.definirTexto(if (conectado) {
            getString(R.string.obd_ecu_pids_qtd, info.quantidadePids, viewModel.totalPidsCatalogo)
        } else {
            getString(R.string.valor_indisponivel)
        })
        val perfil = info.perfil
        textoPerfilFabricante.definirTexto(when {
            !conectado -> getString(R.string.valor_indisponivel)
            perfil == null -> getString(R.string.obd_perfil_verificando)
            perfil is ResultadoPerfil.SemPerfil -> getString(R.string.obd_perfil_sem)
            perfil is ResultadoPerfil.Indisponivel -> getString(R.string.obd_perfil_indisponivel, perfil.fabricante, perfil.motivo)
            perfil is ResultadoPerfil.Ativo ->
                resources.getQuantityString(R.plurals.obd_perfil_ativo, perfil.pids.size, perfil.fabricante, perfil.pids.size)
            else -> ""
        })
        textoProtocoloEcu.definirTexto(if (conectado) descreverConexao(info.conexao) else getString(R.string.valor_indisponivel))
        val observacao = (perfil as? ResultadoPerfil.Ativo)?.observacao?.takeIf { conectado && it.isNotBlank() }
        textoPerfilObservacao.isVisible = observacao != null
        textoPerfilObservacao.text = observacao
    }

    /**
     * Protocolo (com o fallback físico da camada 2, se houve) e a camada ativada: `OBD2 Padrão`,
     * `Modo 21 (M-OBD / KWP)` ou `Modo 22 (UDS)`, com quantos sensores responderam.
     */
    private fun descreverConexao(conexao: InfoConexao): String {
        val protocolo = conexao.protocolo ?: getString(R.string.obd_ecu_protocolo_desconhecido)
        val comFallback = conexao.protocoloForcado?.let { getString(R.string.obd_ecu_protocolo_forcado, protocolo, it) } ?: protocolo
        val sensores = resources.getQuantityString(
            R.plurals.obd_ecu_sensores_responderam,
            conexao.sensoresResponderam,
            conexao.sensoresResponderam,
        )
        val camada = if (conexao.modo == ModoComunicacaoEcu.OBD2_PADRAO) {
            getString(R.string.obd_ecu_modo_padrao, conexao.nomeModo, sensores)
        } else {
            getString(R.string.obd_ecu_modo_servico, conexao.nomeModo, sensores, conexao.comandosServico.joinToString())
        }
        return "$comFallback\n$camada"
    }

    /** Computador de bordo: km/L e L/h instantâneos, método usado e média da viagem. */
    private fun mostrarComputador(ui: ComputadorBordoUi) = with(binding) {
        val vazio = getString(R.string.valor_indisponivel)
        val consumo = ui.consumo
        textoConsumoKmL.definirTexto(when {
            consumo == null -> vazio
            consumo.kmPorLitro == null -> getString(R.string.obd_bordo_parado)
            else -> getString(R.string.obd_bordo_km_l, consumo.kmPorLitro.coerceAtMost(MAX_KM_L_EXIBIDO).comoNumero(1))
        })
        textoConsumoLH.definirTexto(consumo?.let { getString(R.string.obd_bordo_l_h, it.litrosPorHora.comoNumero(1)) } ?: vazio)
        textoMetodoConsumo.isVisible = consumo != null
        textoMetodoConsumo.definirTexto(when (consumo?.metodo) {
            MetodoConsumo.MAF -> getString(R.string.obd_bordo_metodo_maf)
            MetodoConsumo.SPEED_DENSITY -> getString(R.string.obd_bordo_metodo_sd)
            MetodoConsumo.SENSOR_ECU -> getString(R.string.obd_bordo_metodo_ecu)
            null -> ""
        })
        textoMediaViagem.definirTexto(ui.viagem.mediaKmPorLitro?.let { getString(R.string.obd_bordo_km_l, it.comoNumero(1)) } ?: vazio)
        textoTotaisViagem.definirTexto(getString(
            R.string.obd_bordo_totais, ui.viagem.distanciaKm.comoNumero(1), ui.viagem.litros.comoNumero(2),
        ))

        val conectado = viewModel.estado.value is EstadoConexaoObd.Conectado
        val etanol = viewModel.dados.value?.get(Pids.PERCENTUAL_ETANOL)
        val combustivel = ui.motor.tipoCombustivel.rotulo(requireContext())
        val cilindrada = ui.motor.cilindradaLitros.comoNumero(1)
        textoParametrosMotor.definirTexto(when {
            conectado && consumo == null && ui.motor.tipoCombustivel == TipoCombustivel.DIESEL ->
                getString(R.string.obd_bordo_diesel_sem_ecu)
            conectado && consumo == null -> getString(R.string.obd_bordo_sem_dados)
            ui.motor.tipoCombustivel == TipoCombustivel.FLEX && etanol != null ->
                getString(R.string.obd_bordo_motor_etanol, cilindrada, combustivel, etanol.comoNumero(0))
            else -> getString(R.string.obd_bordo_motor, cilindrada, combustivel)
        })
        botaoZerarViagem.isEnabled = ui.viagem.segundos > 0
    }

    /** Cronômetro 0–100: tempo (ao vivo ou final), instrução do estado e melhor tempo. */
    private fun mostrarCronometro(ui: CronometroUi) = with(binding) {
        val estado = ui.estado
        textoCronometroTempo.definirTexto(getString(
            R.string.obd_cronometro_tempo,
            when (estado) {
                is EstadoCronometro.Medindo -> segundos(estado.decorridoMs, 1)
                is EstadoCronometro.Concluido -> segundos(estado.tempoMs, 2)
                else -> segundos(0, 2)
            },
        ))
        textoCronometroStatus.definirTexto(when (estado) {
            EstadoCronometro.Desarmado -> getString(R.string.obd_cronometro_desarmado)
            EstadoCronometro.AguardandoParada -> getString(R.string.obd_cronometro_aguardando)
            EstadoCronometro.Pronto -> getString(R.string.obd_cronometro_pronto)
            is EstadoCronometro.Medindo -> getString(R.string.obd_cronometro_medindo, estado.velocidade.toInt())
            is EstadoCronometro.Concluido -> getString(R.string.obd_cronometro_concluido)
        })
        textoCronometroMelhor.isVisible = ui.melhorTempoMs != null
        textoCronometroMelhor.definirTexto(ui.melhorTempoMs?.let { getString(R.string.obd_cronometro_melhor, segundos(it, 2)) })
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
        botaoGravarLog.definirTexto(if (log.gravando) {
            getString(R.string.obd_log_gravando, log.amostras)
        } else {
            getString(R.string.obd_log_gravar)
        })
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

    /** Falha de conexão: mensagem longa (o motivo costuma ser detalhado) com atalho para o log bruto. */
    private fun mostrarErroConexao(texto: String) {
        if (texto.isBlank()) return
        Snackbar.make(requireView(), texto, Snackbar.LENGTH_INDEFINITE)
            .setTextMaxLines(MAX_LINHAS_ERRO)
            .setAction(R.string.obd_registro_ver) { mostrarRegistroComunicacao() }
            .show()
    }

    private fun mostrarMensagem(texto: String) {
        if (texto.isNotBlank()) Snackbar.make(requireView(), texto, Snackbar.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------------ ciclo de vida

    // O polling de PIDs é da sessão compartilhada (serviço em primeiro plano): segue com a aba em
    // segundo plano, para a caixa-preta e o HUD. A tela só observa.
    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart: tela observando a sessão OBD")
        // Também relê a permissão: o usuário pode ter voltado agora da tela do Acesso a notificações.
        controladorMidia?.iniciar()
    }

    override fun onResume() {
        super.onResume()
        emPrimeiroPlano = true
        aplicarTelaAcesa()
    }

    override fun onPause() {
        emPrimeiroPlano = false
        aplicarTelaAcesa()
        super.onPause()
    }

    override fun onStop() {
        Log.d(TAG, "onStop: a sessão OBD continua no serviço em primeiro plano")
        controladorMidia?.parar()
        super.onStop()
    }

    override fun onDestroyView() {
        Log.d(TAG, "onDestroyView: libera o binding")
        controladorMidia?.parar()
        controladorMidia = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val TAG = "ObdFragment"
        private const val LARGURA_MINIMA_CELULA_DP = 170
        private const val MAX_LINHAS_ERRO = 6
        /** Em descidas com corte de combustível o km/L instantâneo dispara; acima disso mostra o teto. */
        private const val MAX_KM_L_EXIBIDO = 99.9

        fun novo(veiculoId: Long) = ObdFragment().apply {
            arguments = Bundle().apply { putLong(DiagnosticoActivity.EXTRA_VEICULO_ID, veiculoId) }
        }
    }
}
