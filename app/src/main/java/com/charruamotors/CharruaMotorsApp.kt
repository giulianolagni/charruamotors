package com.charruamotors

import android.app.Activity
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.pm.PackageInfoCompat
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
import com.charruamotors.ui.util.SomAlerta
import com.charruamotors.direcao.painel.PreferenciasPainel
import com.charruamotors.obd.Elm327BluetoothClient
import com.charruamotors.obd.ModoObd
import com.charruamotors.obd.ObdClient
import com.charruamotors.obd.ObdClientFake
import com.charruamotors.obd.dtc.CatalogoDtc
import com.charruamotors.obd.pid.CatalogoCascata
import com.charruamotors.obd.pid.CatalogoEstendido
import com.charruamotors.obd.pid.CatalogoPids
import com.charruamotors.obd.sessao.DestinoLogsDownloads
import com.charruamotors.obd.sessao.FerramentaManualObd
import com.charruamotors.obd.sessao.ObdSessaoService
import com.charruamotors.obd.sessao.OrigemSessao
import com.charruamotors.obd.sessao.PreferenciasAutoConexao
import com.charruamotors.obd.sessao.ProtecaoSessao
import com.charruamotors.obd.sessao.SessaoObd
import com.charruamotors.obd.sessao.VeiculoSessao
import com.charruamotors.oficina.varredura.IdentificadorPids
import com.charruamotors.sistema.backup.BackupRepository
import com.charruamotors.sistema.preferencias.PreferenciasApp
import com.charruamotors.ui.util.ExportadorCsv
import com.charruamotors.ui.util.PermissoesBluetooth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
        // Tema escolhido nas Configurações (Seguir sistema / Claro / Escuro), antes da 1ª Activity.
        AppCompatDelegate.setDefaultNightMode(preferenciasApp.tema.modoNoturno)
        registerActivityLifecycleCallbacks(rastreadorPrimeiroPlano)
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
        )
    }

    /** Melhores 0–60 e 0–100 por veículo. */
    val recordesDesempenho: RecordesDesempenho by lazy { RecordesDesempenho(this) }

    /** Veículo da sessão em curso (nome curto) ou o padrão, que identifica os recordes. */
    fun chaveRecorde(): String = sessaoObd.estadoSessao.value.veiculo ?: RecordesDesempenho.CHAVE_PADRAO

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

    /** Personalização da aba Painel (blocos, mostradores com estilo próprio, lista rápida) e do HUD flutuante. */
    val preferenciasPainel: PreferenciasPainel by lazy { PreferenciasPainel(this) }

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
        BackupRepository(database.backupDao(), preferenciasAutoConexao, preferenciasApp, preferenciasPainel, versaoApp)
    }

    // ---------------------------------------------------------------- caixa-preta

    /** Auto-conexão pelo Bluetooth do carro, logs automáticos e vínculos (scanner e veículo). */
    val preferenciasAutoConexao: PreferenciasAutoConexao by lazy { PreferenciasAutoConexao(this) }

    /**
     * Sessão OBD2 única do app (conexão, polling e logs automáticos), compartilhada pela aba OBD2,
     * pelo HUD flutuante e pelo `ObdSessaoService` em primeiro plano. Ver [SessaoObd].
     */
    val protecaoSessao: ProtecaoSessao by lazy {
        val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
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

    val sessaoObd: SessaoObd by lazy {
        SessaoObd(
            clientePorModo = ::obdClientDe,
            modoInicial = ModoObd.SIMULADO,
            catalogoPids = catalogoPids,
            catalogoDtc = catalogoDtc,
            config = { preferenciasAutoConexao.config.value },
            buscarVeiculo = { id -> veiculoRepository.buscarPorId(id)?.let(VeiculoSessao::de) },
            destinoLogs = DestinoLogsDownloads(this),
            pastaTemporaria = File(cacheDir, "sessoes_obd"),
            escopo = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            telemetria = telemetriaCompartilhada,
            aoAtivar = { origem ->
                // A automática já nasce dentro do serviço. No modo simulado sem a permissão de
                // Bluetooth (emulador), o tipo connectedDevice do Android 14+ recusaria o serviço.
                if (origem == OrigemSessao.MANUAL && PermissoesBluetooth.concedidas(this)) ObdSessaoService.iniciar(this)
            },
        )
    }
}
