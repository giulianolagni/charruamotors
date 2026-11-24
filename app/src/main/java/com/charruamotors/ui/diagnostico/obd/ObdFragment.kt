package com.charruamotors.ui.diagnostico.obd

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
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
import com.charruamotors.databinding.DialogAtivarHudBinding
import com.charruamotors.databinding.DialogRegistroComunicacaoBinding
import com.charruamotors.databinding.FragmentObdBinding
import com.charruamotors.direcao.desempenho.FonteVelocidade
import com.charruamotors.direcao.hud.HudFlutuanteService
import com.charruamotors.direcao.hud.TelemetriaCompartilhada
import com.charruamotors.direcao.midia.ControladorMidia
import com.charruamotors.direcao.midia.EstadoMidia
import com.charruamotors.direcao.waze.AbridorAppExterno
import com.charruamotors.direcao.waze.AbridorWaze
import com.charruamotors.direcao.waze.AcaoWaze
import com.charruamotors.direcao.painel.BotaoNavegacao
import com.charruamotors.direcao.painel.ConfigPainel
import com.charruamotors.direcao.painel.Mostrador
import com.charruamotors.direcao.painel.SecaoPainel
import com.charruamotors.direcao.painel.SensorPainel
import com.charruamotors.sistema.StatusConexaoPainel
import com.charruamotors.sistema.unidades.ConversorUnidades
import com.charruamotors.ui.componentes.GaugeView
import com.charruamotors.ui.painel.ClusterPainelActivity
import com.charruamotors.ui.painel.EditorConfigHud
import com.charruamotors.ui.painel.MontadorMostradores
import com.charruamotors.domain.perfis.DecodificadorVin
import com.charruamotors.domain.calculo.AlertaProtecao
import com.charruamotors.domain.calculo.EstadoCronometro
import com.charruamotors.domain.calculo.EstequiometriaFlex
import com.charruamotors.domain.calculo.MetodoConsumo
import com.charruamotors.domain.model.TipoCombustivel
import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.obd.DadosObd
import com.charruamotors.obd.InfoConexao
import com.charruamotors.obd.ModoComunicacaoEcu
import com.charruamotors.obd.ModoObd
import com.charruamotors.obd.ResultadoPerfil
import com.charruamotors.obd.log.LogsSessaoSalvos
import com.charruamotors.obd.pid.Pids
import com.charruamotors.obd.sessao.ConfigAutoConexao
import com.charruamotors.obd.sessao.EstadoSessao
import com.charruamotors.obd.sessao.SessaoObd
import com.charruamotors.ui.diagnostico.DiagnosticoActivity
import com.charruamotors.ui.oficina.replay.ReplayLogActivity
import com.charruamotors.ui.util.ArquivoExportado
import com.charruamotors.ui.util.ExportadorCsv
import com.charruamotors.ui.util.PermissoesBluetooth
import com.charruamotors.ui.util.comoNumero
import com.charruamotors.ui.util.definirTexto
import com.charruamotors.ui.util.rotulo
import com.charruamotors.ui.componentes.PontoTelemetria
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/**
 * Aba Painel (v1.5): só telemetria ao vivo. No topo, o card de conexão inteligente — desconectado,
 * o início rápido com "Conectar no Carro (Bluetooth)" (adaptador salvo + trocar o pareado) e "Modo
 * Simulado"; conectado (manual ou pela caixa-preta), uma barra compacta `● Conectado · OBDII · 7E0`
 * com Desconectar. Abaixo, os blocos escolhidos em "Personalizar Painel", na ordem e sem os
 * removidos: Músicas, Navegação (Waze e o Modo HUD), Mostradores (quantos quiser, estilo por relógio),
 * Gráfico, Computador de bordo, Cronômetro 0–100, Lista rápida (com salvar leitura e o Data Logger
 * CSV) e Informações da ECU. Tudo nas unidades globais (métrico/imperial).
 */
class ObdFragment : Fragment() {

    // Binding só é válido entre onCreateView e onDestroyView; é liberado em onDestroyView.
    private var _binding: FragmentObdBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ObdViewModel by viewModels { ObdViewModel.Factory }
    private val adapterSensores = SensorAdapter()

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

    /** Unidades globais (métrico/imperial) e de pressão escolhidas nas Configurações. */
    private var conversor: ConversorUnidades = ConversorUnidades.PADRAO

    /** Blocos, mostradores, botões da Navegação e a lista rápida (Personalizar Painel). */
    private var configPainel: ConfigPainel = ConfigPainel()

    /** Mostradores desenhados agora (sensores + estilos + unidades), para não recriar a cada mudança. */
    private var assinaturaMostradores: Pair<List<Mostrador>, ConversorUnidades>? = null

    /** "Ver todos": ignora a lista rápida e mostra todos os sensores suportados. */
    private var mostrarTodosSensores = false

    /** Um por mostrador de [configPainel], na mesma ordem (montados pelo [MontadorMostradores]). */
    private var gauges: List<GaugeView> = emptyList()

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
                garantirBluetooth(real = true, acao = acaoPendente)
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

        binding.listaSensores.layoutManager = GridLayoutManager(requireContext(), colunasDaGrade())
        binding.listaSensores.adapter = adapterSensores
        // A grade muda a cada ciclo de polling: sem animação de "change" para não piscar.
        binding.listaSensores.itemAnimator = null
        controladorMidia = ControladorMidia(requireContext())
        configurarAcoes()
        configurarDirecao()
        observarViewModel()
    }

    private fun configurarAcoes() = with(binding) {
        botaoConectarCarro.setOnClickListener {
            garantirBluetooth(real = true) { viewModel.conectarNoModo(ModoObd.BLUETOOTH) }
        }
        botaoConectarSimulado.setOnClickListener { viewModel.conectarNoModo(ModoObd.SIMULADO) }
        botaoTrocarAdaptador.setOnClickListener { garantirBluetooth(real = true) { escolherAdaptador() } }
        switchSimularFalha.setOnCheckedChangeListener { _, marcado -> viewModel.definirSimularFalha(marcado) }
        botaoCancelarConexao.setOnClickListener { viewModel.desconectar() }
        botaoDesconectar.setOnClickListener { viewModel.desconectar() }
        botaoSalvarLeitura.setOnClickListener { viewModel.salvarLeitura() }
        botaoGravarLog.setOnClickListener { viewModel.alternarLog() }
        botaoArmarCronometro.setOnClickListener { viewModel.alternarCronometro() }
        botaoZerarViagem.setOnClickListener { viewModel.zerarViagem() }
        botaoRegistroComunicacao.setOnClickListener { mostrarRegistroComunicacao() }
        botaoVerTodosSensores.setOnClickListener {
            mostrarTodosSensores = !mostrarTodosSensores
            mostrarSensores(viewModel.sensores.value)
        }
    }

    // ------------------------------------------------------------------ conexão (card inteligente)

    /** Ícone de trocar: lista os pareados com o adaptador salvo marcado. */
    private fun escolherAdaptador() {
        viewLifecycleOwner.lifecycleScope.launch {
            val pareados = viewModel.pareadosBluetooth()
            if (pareados.isEmpty()) {
                mostrarMensagem(getString(R.string.config_scanner_sem_pareados))
                return@launch
            }
            val atual = pareados.indexOfFirst { it.endereco == viewModel.configAuto.value.scanner?.endereco }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.conexao_escolher_adaptador)
                .setSingleChoiceItems(pareados.map { getString(R.string.caixa_gatilho_pareado, it.nome, it.endereco) }.toTypedArray(), atual) { dialogo, i ->
                    viewModel.escolherAdaptador(pareados[i])
                    dialogo.dismiss()
                }
                .setNegativeButton(R.string.botao_cancelar, null)
                .show()
        }
    }

    /** `Adaptador: OBDII` (o scanner salvo) embaixo do botão do carro. */
    private fun mostrarAdaptador(config: ConfigAutoConexao) {
        binding.textoAdaptador.definirTexto(
            config.scanner?.let { getString(R.string.conexao_adaptador, it.nome) } ?: getString(R.string.conexao_adaptador_nenhum),
        )
    }

    private fun mostrarEstadoSessao(@Suppress("UNUSED_PARAMETER") estado: EstadoSessao) {
        mostrarEstado(viewModel.estado.value)
    }

    /** Sessão encerrada com os logs automáticos salvos: atalho para o visualizador. */
    private fun oferecerLogsSessao(logs: LogsSessaoSalvos) {
        val arquivo = logs.txt ?: logs.csv ?: return
        Snackbar.make(requireView(), getString(R.string.caixa_logs_salvos, arquivo.local), Snackbar.LENGTH_LONG)
            .setAction(R.string.caixa_ver_logs) { startActivity(ReplayLogActivity.intent(requireContext())) }
            .show()
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
        botaoMaps.setOnClickListener {
            if (!AbridorAppExterno.abrir(requireContext(), AbridorAppExterno.PACOTE_MAPS)) mostrarMensagem(getString(R.string.maps_erro))
        }
        botaoSpotify.setOnClickListener {
            if (!AbridorAppExterno.abrir(requireContext(), AbridorAppExterno.PACOTE_SPOTIFY)) mostrarMensagem(getString(R.string.spotify_erro))
        }
        botaoWazePosto.setOnClickListener { abrirWaze(AcaoWaze.POSTO) }
        botaoWazeOficina.setOnClickListener { abrirWaze(AcaoWaze.OFICINA) }
        botaoHud.setOnClickListener {
            if (telemetriaHud.hudAtivo.value) HudFlutuanteService.parar(requireContext()) else escolherHud()
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
     * Tela de ativação do HUD: Minimizado (RPM e Turbo) ou Expandido (até 4 sensores), quais dados
     * fixar e o estilo de texto/gráficos. A escolha fica salva para a próxima vez.
     */
    private fun escolherHud() {
        val conteudo = DialogAtivarHudBinding.inflate(layoutInflater)
        val editor = EditorConfigHud(conteudo.configHud, app.preferenciasPainel.config.value.hud)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.hud_config_titulo)
            .setIcon(R.drawable.ic_hud)
            .setView(conteudo.root)
            .setNegativeButton(R.string.botao_cancelar, null)
            .setPositiveButton(R.string.hud_config_ativar) { _, _ ->
                app.preferenciasPainel.definirHud(editor.config)
                ativarHud()
            }
            .show()
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
     * e Bluetooth ligado. Com [real] = false (modo simulado) executa a [acao] direto.
     */
    private fun garantirBluetooth(real: Boolean = viewModel.modo.value == ModoObd.BLUETOOTH, acao: (() -> Unit)?) {
        if (acao == null) return
        if (!real) {
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
    /**
     * Modo Painel Automotivo: com a aba em primeiro plano, o scanner conectado (ou o Modo Simulado)
     * e o celular na horizontal, abre o cluster em tela cheia. Na vertical libera a dispensa do
     * Voltar, para o próximo giro abrir de novo.
     */
    private fun avaliarCluster() {
        val horizontal = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (!horizontal) {
            ClusterPainelActivity.dispensado = false
            return
        }
        if (!emPrimeiroPlano || ClusterPainelActivity.aberto || ClusterPainelActivity.dispensado) return
        if (viewModel.estado.value !is EstadoConexaoObd.Conectado) return
        startActivity(ClusterPainelActivity.intent(requireContext()))
    }

    private fun aplicarTelaAcesa() {
        val janela = activity?.window ?: return
        if (emPrimeiroPlano && viewModel.manterTelaAcesa.value) {
            janela.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            janela.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun observarViewModel() {
        // viewLifecycleOwner: a coleta acompanha a view do Fragment (para ao destruir a view).
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.estado.collect { mostrarEstado(it) } }
                launch { viewModel.dados.collect { mostrarDados(it) } }
                launch { viewModel.sensores.collect { mostrarSensores(it) } }
                launch { viewModel.infoEcu.collect { mostrarInfoEcu(it) } }
                launch { viewModel.estadoLog.collect { mostrarEstadoLog(it) } }
                launch {
                    viewModel.computador.collect {
                        mostrarComputador(it)
                        // O mostrador de consumo (km/L) acompanha o computador de bordo.
                        if (configPainel.mostradores.any { m -> m.sensor == SensorPainel.CONSUMO_KML }) mostrarDados(viewModel.dados.value)
                    }
                }
                launch { viewModel.limites.collect { app.centralAlertas.limites = it } }
                launch { viewModel.faixas.collect { app.centralAlertas.faixas = it } }
                launch { viewModel.cronometro.collect { mostrarCronometro(it) } }
                launch { viewModel.grafico.collect { mostrarGrafico(it) } }
                launch { viewModel.manterTelaAcesa.collect { aplicarTelaAcesa() } }
                controladorMidia?.let { midia -> launch { midia.estado.collect { mostrarMidia(it) } } }
                launch { telemetriaHud.hudAtivo.collect { mostrarHud(it) } }
                launch { viewModel.configAuto.collect { mostrarAdaptador(it) } }
                launch { app.preferenciasApp.config.collect { aplicarConversor(it.conversor) } }
                launch { app.preferenciasPainel.config.collect { aplicarConfigPainel(it) } }
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

    private fun mostrarEstado(estado: EstadoConexaoObd) {
        avaliarCluster()
        mostrarInfoEcu(viewModel.infoEcu.value)
        val sessao = viewModel.estadoSessao.value
        val aguardandoSessao = sessao.ativa && estado !is EstadoConexaoObd.Conectado && estado !is EstadoConexaoObd.Conectando
        binding.textoEstado.definirTexto(when {
            // Sessão esperando a ECU ou entre tentativas: o estado do cliente ainda não diz tudo.
            aguardandoSessao -> SessaoObd.descreverStatus(sessao)
            estado is EstadoConexaoObd.Erro -> getString(R.string.obd_estado_erro, estado.mensagem)
            estado is EstadoConexaoObd.Conectando -> getString(R.string.obd_estado_conectando)
            estado is EstadoConexaoObd.Conectado -> getString(R.string.obd_estado_conectado)
            else -> getString(R.string.conexao_dica)
        })
        atualizarBotoes()
    }

    /**
     * Card inteligente: desconectado → início rápido; conectando → os mesmos botões travados e
     * "Cancelar" (a conexão RFCOMM com um scanner fora de alcance leva ~10 s); conectado → só a
     * barra compacta, liberando a tela para os mostradores.
     */
    private fun atualizarBotoes() = with(binding) {
        val estado = viewModel.estado.value
        val conectado = estado is EstadoConexaoObd.Conectado
        val conectando = estado is EstadoConexaoObd.Conectando || (viewModel.estadoSessao.value.ativa && !conectado)

        cardInicioRapido.isVisible = !conectado
        barraConectado.isVisible = conectado
        botaoConectarCarro.isEnabled = !conectando
        botaoConectarSimulado.isEnabled = !conectando
        botaoTrocarAdaptador.isEnabled = !conectando
        switchSimularFalha.isEnabled = !conectando
        botaoCancelarConexao.isVisible = conectando
        if (conectado) mostrarStatusCompacto()

        botaoSalvarLeitura.isEnabled = conectado && viewModel.dados.value != null
        // Gravando: o botão continua ativo para permitir parar mesmo após a queda da conexão.
        botaoGravarLog.isEnabled = conectado || viewModel.estadoLog.value.gravando
        botaoArmarCronometro.isEnabled = conectado || viewModel.cronometro.value.estado.armado
        botaoZerarViagem.isEnabled = viewModel.computador.value.viagem.segundos > 0
    }

    /** `Conectado · OBDII · 7E0`. */
    private fun mostrarStatusCompacto() {
        val scanner = viewModel.estadoSessao.value.scanner ?: viewModel.dispositivoSelecionado.value?.nome
        val texto = StatusConexaoPainel.resumo(scanner, viewModel.infoEcu.value.conexao)
        binding.textoStatusCompacto.definirTexto(texto)
        binding.barraConectado.contentDescription = texto
    }

    /** Os mostradores escolhidos, com o valor já convertido para a unidade global. */
    private fun mostrarDados(dados: DadosObd?) {
        com.charruamotors.direcao.painel.HistoricoSensores.registrar(dados)
        val alertas = app.centralAlertas.processar(dados)
        mostrarAlertas(alertas)
        val consumoKmL = viewModel.computador.value.consumo?.kmPorLitro
        configPainel.mostradores.forEachIndexed { i, m ->
            val gauge = gauges.getOrNull(i) ?: return@forEachIndexed
            gauge.emAlerta = m.sensor.emAlerta(alertas) || app.centralAlertas.foraDaFaixa(m.sensor.comandos, dados)
            gauge.definirValor(m.sensor.ler(dados, consumoKmL)?.let { m.sensor.exibir(it, conversor).valor })
        }
        atualizarBotoes()
    }

    /** Faixa vermelha no topo dos blocos enquanto algum limite do veículo (água, ATF, alternador) está ultrapassado. */
    private fun mostrarAlertas(alertas: List<AlertaProtecao>) = with(binding) {
        textoAlertasProtecao.isVisible = alertas.isNotEmpty()
        if (alertas.isNotEmpty()) {
            textoAlertasProtecao.text = getString(R.string.alerta_painel_titulo, alertas.joinToString(" · ") { it.tipo.texto })
        }
    }

    /**
     * Personalizar Painel mudou: mostra só os blocos visíveis, na ordem escolhida; refaz os
     * mostradores (quantidade, sensor, estilo e escala) se mudaram; aplica os botões da Navegação
     * e o filtro da lista rápida.
     */
    private fun aplicarConfigPainel(config: ConfigPainel) = with(binding) {
        configPainel = config
        // Navegação sem nenhum botão marcado: o bloco some (sem card vazio).
        val secoes = config.secoesVisiveis.filter { it != SecaoPainel.NAVEGACAO || config.botoesNavegacao.isNotEmpty() }
        val visiveis = secoes.map(::cardDaSecao)
        val atuais = (0 until containerSecoes.childCount).map(containerSecoes::getChildAt).filter { it.isVisible }
        if (visiveis != atuais) {
            containerSecoes.removeAllViews()
            visiveis.forEach { card ->
                card.isVisible = true
                containerSecoes.addView(card)
            }
            // Os removidos ficam no container, escondidos (as views continuam ligadas ao binding).
            SecaoPainel.entries.map(::cardDaSecao).filter { it !in visiveis }.forEach { card ->
                card.isVisible = false
                containerSecoes.addView(card)
            }
        }
        organizarAtalhos(config)
        configurarMostradores()
        mostrarSensores(viewModel.sensores.value)
    }

    /** Reanexa os atalhos visíveis na grade, na ordem escolhida (só mexe quando a ordem muda). */
    private fun organizarAtalhos(config: ConfigPainel) = with(binding) {
        val porAtalho = mapOf(
            BotaoNavegacao.WAZE to botaoWazeAbrir,
            BotaoNavegacao.MAPS to botaoMaps,
            BotaoNavegacao.SPOTIFY to botaoSpotify,
            BotaoNavegacao.POSTO to botaoWazePosto,
            BotaoNavegacao.OFICINA to botaoWazeOficina,
            BotaoNavegacao.HUD to botaoHud,
        )
        val desejados = config.botoesVisiveis.mapNotNull(porAtalho::get)
        val atuais = (0 until grupoAtalhos.childCount).map(grupoAtalhos::getChildAt)
        if (desejados != atuais) {
            grupoAtalhos.removeAllViews()
            desejados.forEach(grupoAtalhos::addView)
        }
    }

    private fun cardDaSecao(secao: SecaoPainel): View = when (secao) {
        SecaoPainel.MUSICA -> binding.cardMusica
        SecaoPainel.NAVEGACAO -> binding.cardNavegacao
        SecaoPainel.MARCADORES -> binding.cardMarcadores
        SecaoPainel.GRAFICO -> binding.cardGrafico
        SecaoPainel.COMPUTADOR -> binding.cardComputador
        SecaoPainel.CRONOMETRO -> binding.cardCronometro
        SecaoPainel.LISTA_SENSORES -> binding.cardListaSensores
        SecaoPainel.INFO_ECU -> binding.cardInfoEcu
    }

    /** Remonta a grade (2 colunas; Fita em largura total) só quando os relógios ou as unidades mudam. */
    private fun configurarMostradores() {
        val assinatura = configPainel.mostradores to conversor
        if (assinatura == assinaturaMostradores) return
        assinaturaMostradores = assinatura
        gauges = MontadorMostradores.montar(binding.grupoMostradores, configPainel.mostradores, conversor)
        val dados = viewModel.dados.value
        val consumoKmL = viewModel.computador.value.consumo?.kmPorLitro
        configPainel.mostradores.forEachIndexed { i, m ->
            gauges.getOrNull(i)?.definirValor(m.sensor.ler(dados, consumoKmL)?.let { m.sensor.exibir(it, conversor).valor }, animar = false)
        }
    }

    /** Configurações → Unidades globais / pressão: mostradores, lista rápida, computador de bordo e 0–100. */
    private fun aplicarConversor(novo: ConversorUnidades) {
        if (novo == conversor && assinaturaMostradores != null) return
        conversor = novo
        configurarMostradores()
        adapterSensores.definirConversor(novo)
        mostrarComputador(viewModel.computador.value)
        mostrarCronometro(viewModel.cronometro.value)
    }

    /** Lista rápida: os sensores escolhidos (ou todos, sem escolha ou com "Ver todos"). */
    private fun mostrarSensores(todos: List<LeituraSensor>) = with(binding) {
        val filtrados = configPainel.filtrarListaRapida(todos) { it.definicao.comando }
        val sensores = if (mostrarTodosSensores) todos else filtrados
        val temFiltro = configPainel.listaRapida.isNotEmpty() && todos.size > filtrados.size
        // A cada ciclo só os valores mudam: com tamanho fixo, a grade rebinda as células sem pedir
        // um novo layout ao ScrollView (que remediria a aba inteira 4 vezes por segundo). Quando
        // entram ou saem PIDs (conexão, perfil de montadora, filtro), a grade precisa crescer de verdade.
        listaSensores.setHasFixedSize(sensores.size == adapterSensores.itemCount)
        adapterSensores.submitList(sensores)
        textoSensoresVazio.isVisible = sensores.isEmpty()
        botaoVerTodosSensores.isVisible = temFiltro || (mostrarTodosSensores && configPainel.listaRapida.isNotEmpty())
        botaoVerTodosSensores.definirTexto(
            if (mostrarTodosSensores) getString(R.string.painel_so_lista_rapida) else getString(R.string.painel_ver_todos, todos.size),
        )
        tituloSensores.definirTexto(when {
            sensores.isEmpty() -> getString(R.string.obd_sensores_titulo)
            configPainel.listaRapida.isNotEmpty() && !mostrarTodosSensores -> getString(R.string.painel_lista_rapida_qtd, sensores.size)
            else -> getString(R.string.obd_sensores_titulo_qtd, sensores.size)
        })
    }

    /** v2.2: VIN com a montadora (WMI) e o ano-modelo (10º caractere), ex.: `9BG... · Chevrolet 2018`. */
    private fun vinComMontadora(vin: String): String {
        val d = DecodificadorVin.decodificar(vin) ?: return vin
        val extra = listOfNotNull(d.montadora, d.anoModelo?.toString()).joinToString(" ")
        return if (extra.isEmpty()) vin else "$vin · $extra"
    }

    private fun mostrarInfoEcu(info: InfoEcu) = with(binding) {
        val conectado = viewModel.estado.value is EstadoConexaoObd.Conectado
        textoVin.definirTexto(when {
            !conectado -> getString(R.string.obd_ecu_desconectado)
            !info.vinLido -> getString(R.string.obd_ecu_vin_lendo)
            else -> info.vin?.let(::vinComMontadora) ?: getString(R.string.obd_ecu_vin_indisponivel)
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
        // O cabeçalho da ECU (7E0) e o protocolo chegam depois do "Conectado".
        if (conectado) mostrarStatusCompacto()
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
            else -> consumoTexto(consumo.kmPorLitro.coerceAtMost(MAX_KM_L_EXIBIDO))
        })
        textoConsumoLH.definirTexto(consumo?.let {
            getString(R.string.obd_valor_unidade, conversor.vazao(it.litrosPorHora).comoNumero(if (conversor.imperial) 2 else 1), conversor.simboloVazao)
        } ?: vazio)
        textoMetodoConsumo.isVisible = consumo != null
        textoMetodoConsumo.definirTexto(when (consumo?.metodo) {
            MetodoConsumo.MAF -> getString(R.string.obd_bordo_metodo_maf)
            MetodoConsumo.SPEED_DENSITY -> getString(R.string.obd_bordo_metodo_sd)
            MetodoConsumo.SENSOR_ECU -> getString(R.string.obd_bordo_metodo_ecu)
            null -> ""
        })
        textoMediaViagem.definirTexto(ui.viagem.mediaKmPorLitro?.let(::consumoTexto) ?: vazio)
        textoTotaisViagem.definirTexto(getString(
            R.string.obd_bordo_totais,
            conversor.distancia(ui.viagem.distanciaKm).comoNumero(1), conversor.simboloDistancia,
            conversor.volume(ui.viagem.litros).comoNumero(2), conversor.simboloVolume,
        ))

        val conectado = viewModel.estado.value is EstadoConexaoObd.Conectado
        val etanol = viewModel.dados.value?.get(Pids.PERCENTUAL_ETANOL)
        val combustivel = ui.motor.tipoCombustivel.rotulo(requireContext())
        val cilindrada = ui.motor.cilindradaLitros.comoNumero(1)
        textoParametrosMotor.definirTexto(when {
            conectado && consumo == null && ui.motor.tipoCombustivel == TipoCombustivel.DIESEL ->
                getString(R.string.obd_bordo_diesel_sem_ecu)
            conectado && consumo == null -> getString(R.string.obd_bordo_sem_dados)
            // Modo Flex Automático: classe do combustível e A/F de referência pelo 0152 (E27 13,2 → E100 9,0).
            ui.motor.tipoCombustivel == TipoCombustivel.FLEX && etanol != null ->
                getString(R.string.obd_bordo_motor_etanol, cilindrada, combustivel, EstequiometriaFlex.avaliar(TipoCombustivel.FLEX, etanol).resumo)
            else -> getString(R.string.obd_bordo_motor, cilindrada, combustivel)
        })
        botaoZerarViagem.isEnabled = ui.viagem.segundos > 0
    }

    /** km/L ou MPG, com uma casa. */
    private fun consumoTexto(kmPorLitro: Double): String =
        getString(R.string.obd_valor_unidade, conversor.consumo(kmPorLitro).comoNumero(1), conversor.simboloConsumo)

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
        val status = when (estado) {
            EstadoCronometro.Desarmado -> getString(R.string.obd_cronometro_desarmado)
            EstadoCronometro.AguardandoParada -> getString(R.string.obd_cronometro_aguardando)
            EstadoCronometro.Pronto -> getString(R.string.obd_cronometro_pronto)
            is EstadoCronometro.Medindo -> getString(
                R.string.obd_cronometro_medindo, conversor.velocidade(estado.velocidade).toInt(), conversor.simboloVelocidade,
            )
            is EstadoCronometro.Concluido -> getString(R.string.obd_cronometro_concluido)
        }
        val viaGps = ui.fonte == FonteVelocidade.GPS && estado !is EstadoCronometro.Desarmado
        textoCronometroStatus.definirTexto(if (viaGps) getString(R.string.obd_cronometro_via_gps, status) else status)
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
        avaliarCluster()
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
