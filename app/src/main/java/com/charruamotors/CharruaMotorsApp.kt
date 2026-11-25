package com.charruamotors

import android.app.Activity
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.pm.PackageInfoCompat
import com.charruamotors.carros.GerenciadorPerfisCarro
import com.charruamotors.carros.MigracaoPerfisCarro
import com.charruamotors.carros.PerfilCarro
import com.charruamotors.carros.PreferenciasLegadas
import com.charruamotors.direcao.painel.CodecPainel
import com.charruamotors.sistema.preferencias.ArmazenamentoChaveValor
import com.charruamotors.ui.painel.ClusterPainelActivity
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import com.charruamotors.data.local.AppDatabase
import com.charruamotors.data.repository.AbastecimentoRepository
import com.charruamotors.data.repository.DiagnosticoRepository
import com.charruamotors.data.repository.ManutencaoRepository
import com.charruamotors.data.repository.UsuarioRepository
import com.charruamotors.data.repository.VeiculoRepository
import com.charruamotors.data.session.SessaoManager
import com.charruamotors.direcao.desempenho.CronometroDesempenho
import com.charruamotors.direcao.desempenho.RecordesDesempenho
import com.charruamotors.direcao.hud.TelemetriaCompartilhada
import com.charruamotors.domain.calculo.CentralAlertas
import com.charruamotors.domain.calculo.TipoAlertaProtecao
import com.charruamotors.domain.perfis.CarregadorPerfis
import com.charruamotors.domain.perfis.FontePerfis
import com.charruamotors.ui.util.SomAlerta
import com.charruamotors.direcao.painel.PreferenciasPainel
import com.charruamotors.obd.Elm327BluetoothClient
import com.charruamotors.obd.ModoObd
import com.charruamotors.obd.ObdClient
import com.charruamotors.obd.ObdClientFake
import com.charruamotors.obd.aprendizado.AprendizLogsVeiculo
import com.charruamotors.obd.aprendizado.IndiceLogsReais
import com.charruamotors.obd.dtc.CatalogoDtc
import com.charruamotors.obd.pid.CatalogoCascata
import com.charruamotors.obd.pid.CatalogoEstendido
import com.charruamotors.obd.pid.CatalogoPids
import com.charruamotors.obd.sessao.DestinoLogsDownloads
import com.charruamotors.obd.sessao.FaseSessao
import com.charruamotors.obd.sessao.FerramentaManualObd
import com.charruamotors.obd.sessao.ObdSessaoService
import com.charruamotors.obd.sessao.OrigemSessao
import com.charruamotors.obd.sessao.PreferenciasAutoConexao
import com.charruamotors.obd.sessao.ProtecaoSessao
import com.charruamotors.obd.sessao.SessaoObd
import com.charruamotors.obd.sessao.VeiculoSessao
import com.charruamotors.sistema.gps.ProvedorVelocidadeGps
import com.charruamotors.oficina.varredura.IdentificadorPids
import com.charruamotors.sistema.backup.BackupRepository
import com.charruamotors.sistema.preferencias.PreferenciasApp
import com.charruamotors.ui.util.ExportadorCsv
import com.charruamotors.ui.util.PermissoesBluetooth
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/**
 * Classe Application: criada uma única vez quando o processo do app inicia,
 * antes de qualquer Activity. Usada como ponto central para dependências
 * compartilhadas (banco de dados, repositórios, sessão).
 */
class CharruaMotorsApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // v2.2: catálogo modular de perfis (assets/perfis/: veículos → motor + câmbio), lido sob demanda.
        CarregadorPerfis.instalar(FontePerfis.deStreams(assets::open))
        // Tema escolhido nas Configurações (Seguir sistema / Claro / Escuro), antes da 1ª Activity.
        AppCompatDelegate.setDefaultNightMode(preferenciasApp.tema.modoNoturno)
        registerActivityLifecycleCallbacks(rastreadorPrimeiroPlano)
        prepararPerfisCarro()
    }

    // ---------------------------------------------------------------- perfis isolados por veículo (v2.3.0)

    /** Painel/HUD, Cluster, perfil técnico, Bluetooth do carro e recordes de cada veículo. */
    val perfisCarro: GerenciadorPerfisCarro by lazy { GerenciadorPerfisCarro(this) }

    /**
     * Antes de qualquer tela: migra uma única vez as preferências globais da v2.2.9 para o veículo
     * padrão (sem apagar os arquivos antigos), escolhe o veículo ativo e passa a acompanhar o
     * favorito e o Bluetooth do carro da caixa-preta, que agora são do perfil do carro ativo.
     */
    private fun prepararPerfisCarro() {
        if (!perfisCarro.migrado) {
            runCatching { runBlocking(Dispatchers.IO) { withTimeoutOrNull(PRAZO_MIGRACAO_MS) { migrarPreferenciasLegadas() } } }
                .onFailure { Log.e("CharruaMotorsApp", "Falha ao migrar as preferências para os perfis por veículo", it) }
        }
        val favorito = preferenciasAutoConexao.config.value.veiculoId
        if (perfisCarro.veiculoAtivo.value <= 0 && favorito > 0) perfisCarro.ativar(favorito)
        val escopo = escopoDoApp()
        escopo.launch {
            // Favorito trocado em Configurações, no Assistente, no login ou na estrela: vira o carro ativo.
            preferenciasAutoConexao.config.map { it.veiculoId to it.veiculoDescricao }.distinctUntilChanged()
                .collect { (id, descricao) -> if (id > 0 && id != perfisCarro.veiculoAtivo.value) ativarVeiculo(id, descricao) }
        }
        escopo.launch {
            // Bluetooth do carro escolhido nas Configurações/Assistente: fica no perfil do carro ativo.
            preferenciasAutoConexao.config.map { it.gatilhoNome to it.gatilhoEndereco }.distinctUntilChanged().drop(1)
                .collect { (nome, endereco) ->
                    val id = perfisCarro.veiculoAtivo.value
                    if (id > 0) perfisCarro.atualizar(id) { it.copy(gatilhoNome = nome, gatilhoEndereco = endereco) }
                }
        }
    }

    /** Migração única (v2.3.0): ver [MigracaoPerfisCarro]. Sem veículos cadastrados, espera o primeiro. */
    private suspend fun migrarPreferenciasLegadas() {
        val veiculos = database.backupDao().veiculos()
        if (veiculos.isEmpty()) return
        val caixa = preferenciasAutoConexao.config.value
        val painelLegado = ArmazenamentoChaveValor.deSharedPreferences(getSharedPreferences(PreferenciasPainel.ARQUIVO, MODE_PRIVATE))
        val temPainel = listOf(CodecPainel.ORDEM, CodecPainel.MOSTRADORES_V2, CodecPainel.MOSTRADORES_LEGADO, CodecPainel.HUD_PRESET)
            .any { painelLegado.lerTexto(it) != null }
        val legado = PreferenciasLegadas(
            painel = if (temPainel) CodecPainel.decodificar(painelLegado::lerTexto) else null,
            cluster = ClusterPainelActivity.lerClusterLegado(this),
            gatilhoNome = caixa.gatilhoNome,
            gatilhoEndereco = caixa.gatilhoEndereco,
            recordes = RecordesDesempenho.lerLegado(this),
        )
        val perfis = MigracaoPerfisCarro.migrar(veiculos, caixa.veiculoId, legado)
        perfis.forEach { p -> perfisCarro.criarSeAusente(p.veiculoId) { p } }
        val alvo = MigracaoPerfisCarro.veiculoPadrao(veiculos, caixa.veiculoId)
        if (perfisCarro.veiculoAtivo.value <= 0 && alvo != null) perfisCarro.ativar(alvo.id)
        perfisCarro.marcarMigrado()
        Log.i("CharruaMotorsApp", "Preferências migradas para ${perfis.size} perfil(is) por veículo; padrão: ${alvo?.descricao}")
    }

    /**
     * Seleciona o carro ativo (seletor do topo, Meus Carros, abrir o Painel de um veículo): o Painel,
     * o HUD e o Cluster passam a ler o perfil dele, ele vira o veículo padrão da caixa-preta e, se
     * tiver Bluetooth do carro próprio, esse passa a ser o gatilho.
     */
    fun ativarVeiculo(id: Long, descricao: String?) {
        if (id <= 0) return
        perfisCarro.criarSeAusente(id) { PerfilCarro(id) }
        perfisCarro.ativar(id)
        preferenciasPainel.trocarArmazenamento(perfisCarro.escopo(id))
        val desc = descricao ?: preferenciasAutoConexao.config.value.takeIf { it.veiculoId == id }?.veiculoDescricao
        preferenciasAutoConexao.vincularVeiculo(id, desc)
        // Selecionado sem a descrição em mãos (Bluetooth do carro): completa pelo banco.
        if (desc == null) escopoDoApp().launch { veiculoRepository.buscarPorId(id)?.let { preferenciasAutoConexao.vincularVeiculo(id, it.descricao) } }
        val perfil = perfisCarro.ler(id)
        if (perfil.temGatilho) preferenciasAutoConexao.definirGatilho(perfil.gatilhoNome!!, perfil.gatilhoEndereco)
    }

    /** Depois de restaurar um backup: relê o perfil do carro ativo (Painel/HUD) do armazenamento. */
    fun recarregarPerfilAtivo() {
        preferenciasPainel.trocarArmazenamento(perfisCarro.escopo(perfisCarro.veiculoAtivo.value))
    }

    /**
     * Conta as Activities visíveis (`onStart`/`onStop`) para a [protecaoSessao] saber se o app está em
     * primeiro plano e se há uma ferramenta manual aberta (Teste de Bateria, Terminal/Varredura, Diagnóstico de Erros).
     */
    private val rastreadorPrimeiroPlano = object : ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) = protecaoSessao.activityIniciada(activity is FerramentaManualObd)
        override fun onActivityStopped(activity: Activity) = protecaoSessao.activityParada(activity is FerramentaManualObd)
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    val sessao: SessaoManager by lazy { SessaoManager(this) }

    val usuarioRepository: UsuarioRepository by lazy { UsuarioRepository(database.usuarioDao()) }

    val veiculoRepository: VeiculoRepository by lazy { VeiculoRepository(database.veiculoDao()) }

    val manutencaoRepository: ManutencaoRepository by lazy {
        ManutencaoRepository(database.manutencaoDao(), database.veiculoDao())
    }

    val abastecimentoRepository: AbastecimentoRepository by lazy {
        AbastecimentoRepository(database.abastecimentoDao(), database.veiculoDao())
    }

    val diagnosticoRepository: DiagnosticoRepository by lazy {
        DiagnosticoRepository(database.leituraObdDao(), database.codigoFalhaDao())
    }

    // ---------------------------------------------------------------- OBD2

    /**
     * Modo atual do scanner, guardado na [sessaoObd] (SIMULADO no emulador/apresentação, BLUETOOTH
     * com o ELM327 real; a sessão automática do carro troca para BLUETOOTH sozinha). Só muda com o
     * scanner desconectado — ver [selecionarModoObd].
     */
    val modoObd: ModoObd
        get() = sessaoObd.modo.value

    /** Catálogo de PIDs (fórmulas, unidades, faixas), lido uma vez de `assets/pids_padrao.json`. */
    val catalogoPids: CatalogoPids by lazy {
        CatalogoPids.deJson(assets.open(CatalogoPids.ARQUIVO_PADRAO).bufferedReader().use { it.readText() })
    }

    /** Perfis de PIDs de montadora (`assets/pids_estendidos.json`); se o arquivo faltar ou for inválido, nenhum perfil. */
    val catalogoEstendido: CatalogoEstendido by lazy {
        runCatching {
            CatalogoEstendido.deJson(assets.open(CatalogoEstendido.ARQUIVO_PADRAO).bufferedReader().use { it.readText() })
        }.getOrElse { CatalogoEstendido.VAZIO }
    }

    /**
     * Camadas 2 e 3 da cascata de conexão (`assets/cascata_conexao.json`): protocolos físicos e
     * modos 21/22 com os PIDs espelhados. Se faltar ou for inválido, só o OBD2 padrão (camada 1).
     */
    val catalogoCascata: CatalogoCascata by lazy {
        runCatching {
            CatalogoCascata.deJson(
                assets.open(CatalogoCascata.ARQUIVO_PADRAO).bufferedReader().use { it.readText() },
                catalogoPids,
            )
        }.getOrElse { CatalogoCascata.VAZIO }
    }

    /** Banco offline de códigos de falha (`assets/dtc_catalogo.json`); se faltar, usa o fallback por sistema. */
    val catalogoDtc: CatalogoDtc by lazy {
        runCatching {
            CatalogoDtc.deJson(assets.open(CatalogoDtc.ARQUIVO_PADRAO).bufferedReader().use { it.readText() })
        }.getOrElse { CatalogoDtc.VAZIO }
    }

    /**
     * Ponte da telemetria ao vivo entre a aba OBD2 e o HUD flutuante sobre o Waze (e o sinal de
     * HUD ativo, que mantém o polling com o app minimizado).
     */
    val telemetriaCompartilhada: TelemetriaCompartilhada by lazy {
        // lazy: as preferências precisam do Context, que só existe depois da construção do Application.
        TelemetriaCompartilhada(
            conversor = preferenciasApp.config.map { it.conversor },
            configHud = preferenciasPainel.config.map { it.hud },
            cronometro = CronometroDesempenho(
                aoParcial060 = { t -> recordesDesempenho.registrar(chaveRecorde(), t, null) },
                aoConcluir0100 = { t60, t100 -> recordesDesempenho.registrar(chaveRecorde(), t60, t100) },
            ),
            fonteCronometro = { preferenciasApp.config.value.fonteCronometroEfetiva },
        )
    }

    /** Velocidade pelo GPS do celular (v2.0): só escuta com a opção ligada e a sessão OBD conectada. */
    val velocidadeGps: ProvedorVelocidadeGps by lazy { ProvedorVelocidadeGps(this) }

    /** Liga/desliga a escuta do GPS conforme a opção e a sessão (também depois de conceder a permissão). */
    fun reavaliarGps() {
        val conectada = sessaoObd.estadoSessao.value.fase == FaseSessao.Conectada
        if (conectada && preferenciasApp.config.value.velocidadeGps) velocidadeGps.iniciar() else velocidadeGps.parar()
    }

    /** Melhores 0–60 e 0–100 por veículo (v2.3.0: no perfil isolado de cada carro). */
    val recordesDesempenho: RecordesDesempenho by lazy { RecordesDesempenho(perfisCarro) }

    /** Veículo da sessão em curso ou, sem ela, o carro ativo: o dono dos recordes. */
    fun chaveRecorde(): Long = sessaoObd.estadoSessao.value.veiculoId.takeIf { it > 0 } ?: perfisCarro.veiculoAtivo.value

    /** Alertas de proteção (água, ATF, alternador) com o aviso sonoro; os limites vêm do veículo ativo. */
    val centralAlertas: CentralAlertas by lazy { 
        CentralAlertas(som = SomAlerta::tocar).also { central ->
            central.aoEntrarEmAlerta = { a ->
                val unidade = if (a.tipo == TipoAlertaProtecao.TENSAO) "V" else "°C"
                val sinal = if (a.tipo == TipoAlertaProtecao.TENSAO) "<" else "≥"
                sessaoObd.registrarEvento("ALERTA ${a.tipo.texto}: ${a.valor} $unidade ($sinal limite ${a.limite} $unidade) às ${horaAlerta()}")
            }
        }
    }

    private fun horaAlerta(): String = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())

    /** Salva os logs CSV do Data Logger em Downloads. */
    val exportadorCsv: ExportadorCsv by lazy { ExportadorCsv(this) }

    // Um cliente por modo, criados sob demanda e mantidos vivos (a conexão sobrevive às telas).
    private val obdSimulado: ObdClient by lazy { ObdClientFake(catalogoEstendido = catalogoEstendido) }

    private val obdBluetooth: ObdClient by lazy {
        val adaptador = getSystemService(BluetoothManager::class.java)?.adapter
        Elm327BluetoothClient(
            adaptador,
            catalogoPids,
            catalogoEstendido = catalogoEstendido,
            catalogoCascata = catalogoCascata,
        )
    }

    /** Cliente OBD2 do modo atual; ViewModels dependem apenas da interface [ObdClient]. */
    val obdClient: ObdClient
        get() = obdClientDe(modoObd)

    /** Cliente OBD2 de um modo específico (usado pela UI para observar o estado de cada modo). */
    fun obdClientDe(modo: ModoObd): ObdClient = when (modo) {
        ModoObd.SIMULADO -> obdSimulado
        ModoObd.BLUETOOTH -> obdBluetooth
    }

    fun selecionarModoObd(modo: ModoObd): Boolean = sessaoObd.selecionarModo(modo)

    // ---------------------------------------------------------------- sistema & app

    /** Tema, unidade de pressão, unidades globais e a flag do Assistente de Primeira Abertura. */
    val preferenciasApp: PreferenciasApp by lazy { PreferenciasApp(this) }

    /**
     * Personalização da aba Painel (blocos, mostradores com estilo próprio, lista rápida) e do HUD
     * flutuante — v2.3.0: sempre a do carro ativo ([ativarVeiculo] troca o escopo).
     */
    val preferenciasPainel: PreferenciasPainel by lazy { PreferenciasPainel(perfisCarro.escopo(perfisCarro.veiculoAtivo.value)) }

    /** Cruza as respostas da varredura e do Terminal com os três catálogos de PIDs do app. */
    val identificadorPids: IdentificadorPids by lazy { IdentificadorPids.de(catalogoPids, catalogoEstendido, catalogoCascata) }

    /** `1.4 (5)`, lido do pacote instalado (o build não gera BuildConfig). */
    val versaoApp: String by lazy {
        runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
        }.getOrElse { if (it is PackageManager.NameNotFoundException) "?" else throw it }
    }

    /** Backup e Restauração (.json via Storage Access Framework). */
    val backupRepository: BackupRepository by lazy {
        BackupRepository(database.backupDao(), preferenciasAutoConexao, preferenciasApp, perfisCarro, versaoApp) { recarregarPerfilAtivo() }
    }

    // ---------------------------------------------------------------- caixa-preta

    /**
     * Escopo de vida do processo (sessão OBD, proteção da sessão, GPS) na main thread. Falha
     * inesperada numa corrotina filha é registrada e não derruba o app: a sessão OBD já fecha o
     * socket e os logs no `finally` e volta a aceitar uma nova conexão.
     */
    private fun escopoDoApp(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + tratadorFalhas)

    private val tratadorFalhas = CoroutineExceptionHandler { _, erro ->
        Log.e("CharruaMotorsApp", "Falha inesperada numa corrotina do app", erro)
    }

    /** Auto-conexão pelo Bluetooth do carro, logs automáticos e vínculos (scanner e veículo). */
    val preferenciasAutoConexao: PreferenciasAutoConexao by lazy { PreferenciasAutoConexao(this) }

    /**
     * Sessão OBD2 única do app (conexão, polling e logs automáticos), compartilhada pela aba OBD2,
     * pelo HUD flutuante e pelo `ObdSessaoService` em primeiro plano. Ver [SessaoObd].
     */
    val protecaoSessao: ProtecaoSessao by lazy {
        val escopo = escopoDoApp()
        ProtecaoSessao(
            escopo = escopo,
            estadoSessao = { sessaoObd.estadoSessao.value },
            encerrar = { motivo -> sessaoObd.desconectar(motivo) },
            registrar = { texto ->
                Log.i("ProtecaoSessao", texto)
                sessaoObd.registrarEvento(texto)
            },
            assumir = { sessaoObd.assumir() },
        ).also { protecao ->
            escopo.launch { sessaoObd.estadoSessao.collect { if (!it.ativa) protecao.sessaoEncerrada() } }
        }
    }

    /** Quais CSVs reais pertencem a qual `veiculoId` (v2.2.4); sessões simuladas nunca entram. */
    val indiceLogsReais: IndiceLogsReais by lazy { IndiceLogsReais(File(filesDir, "aprendizado/indice_logs_reais.json")) }

    /** Aprendizado por veículo dos logs reais (`modelo_aprendido_<veiculoId>.json`), usado no Modo Simulado. */
    val aprendizLogs: AprendizLogsVeiculo by lazy {
        AprendizLogsVeiculo(File(filesDir, "aprendizado"), indiceLogsReais) { entrada ->
            runCatching { contentResolver.openInputStream(android.net.Uri.parse(entrada.uri))?.bufferedReader(Charsets.UTF_8) }.getOrNull()
        }
    }

    private companion object {
        /** Teto da migração única de preferências na abertura (só lê os veículos do banco). */
        const val PRAZO_MIGRACAO_MS = 5_000L
    }

    val sessaoObd: SessaoObd by lazy {
        SessaoObd(
            clientePorModo = ::obdClientDe,
            modoInicial = ModoObd.SIMULADO,
            catalogoPids = catalogoPids,
            catalogoDtc = catalogoDtc,
            config = { preferenciasAutoConexao.config.value },
            // v2.3.0: o veículo vem com o perfil isolado dele (faixas, câmbio e camada escolhidos).
            buscarVeiculo = { id -> veiculoRepository.buscarPorId(id)?.let { VeiculoSessao.de(it, perfisCarro.ler(id)) } },
            destinoLogs = DestinoLogsDownloads(this),
            pastaTemporaria = File(cacheDir, "sessoes_obd"),
            escopo = escopoDoApp(),
            telemetria = telemetriaCompartilhada,
            aoAtivar = { origem ->
                // A automática já nasce dentro do serviço. No modo simulado sem a permissão de
                // Bluetooth (emulador), o tipo connectedDevice do Android 14+ recusaria o serviço.
                if (origem == OrigemSessao.MANUAL && PermissoesBluetooth.concedidas(this)) ObdSessaoService.iniciar(this)
            },
            gpsAtivo = { preferenciasApp.config.value.velocidadeGps },
            velocidadeGps = { velocidadeGps.velocidadeKmh() },
            modeloAprendidoDe = { id -> aprendizLogs.atualizar(id) },
            aoSalvarLogReal = { id, csv -> if (indiceLogsReais.registrar(id, csv.nome, csv.uri, simulado = false)) aprendizLogs.atualizar(id) },
        ).also { sessao ->
            val escopo = escopoDoApp()
            escopo.launch {
                sessao.estadoSessao.map { it.fase == FaseSessao.Conectada }
                    .combine(preferenciasApp.config.map { it.velocidadeGps }) { conectada, gps -> conectada && gps }
                    .distinctUntilChanged()
                    // Usa o valor do fluxo (não reavaliarGps): aqui o lazy de sessaoObd ainda está inicializando.
                    .collect { ligar -> if (ligar) velocidadeGps.iniciar() else velocidadeGps.parar() }
            }
        }
    }
}
