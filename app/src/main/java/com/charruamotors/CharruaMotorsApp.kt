package com.charruamotors

import android.app.Application
import android.bluetooth.BluetoothManager
import com.charruamotors.data.local.AppDatabase
import com.charruamotors.data.repository.AbastecimentoRepository
import com.charruamotors.data.repository.DiagnosticoRepository
import com.charruamotors.data.repository.ManutencaoRepository
import com.charruamotors.data.repository.UsuarioRepository
import com.charruamotors.data.repository.VeiculoRepository
import com.charruamotors.data.session.SessaoManager
import com.charruamotors.direcao.hud.TelemetriaCompartilhada
import com.charruamotors.obd.Elm327BluetoothClient
import com.charruamotors.obd.ModoObd
import com.charruamotors.obd.ObdClient
import com.charruamotors.obd.ObdClientFake
import com.charruamotors.obd.dtc.CatalogoDtc
import com.charruamotors.obd.pid.CatalogoCascata
import com.charruamotors.obd.pid.CatalogoEstendido
import com.charruamotors.obd.pid.CatalogoPids
import com.charruamotors.obd.sessao.DestinoLogsDownloads
import com.charruamotors.obd.sessao.ObdSessaoService
import com.charruamotors.obd.sessao.OrigemSessao
import com.charruamotors.obd.sessao.PreferenciasAutoConexao
import com.charruamotors.obd.sessao.SessaoObd
import com.charruamotors.obd.sessao.VeiculoSessao
import com.charruamotors.ui.util.ExportadorCsv
import com.charruamotors.ui.util.PermissoesBluetooth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Classe Application: criada uma única vez quando o processo do app inicia,
 * antes de qualquer Activity. Usada como ponto central para dependências
 * compartilhadas (banco de dados, repositórios, sessão).
 */
class CharruaMotorsApp : Application() {

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
    val telemetriaCompartilhada = TelemetriaCompartilhada()

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

    // ---------------------------------------------------------------- caixa-preta

    /** Auto-conexão pelo Bluetooth do carro, logs automáticos e vínculos (scanner e veículo). */
    val preferenciasAutoConexao: PreferenciasAutoConexao by lazy { PreferenciasAutoConexao(this) }

    /**
     * Sessão OBD2 única do app (conexão, polling e logs automáticos), compartilhada pela aba OBD2,
     * pelo HUD flutuante e pelo `ObdSessaoService` em primeiro plano. Ver [SessaoObd].
     */
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
