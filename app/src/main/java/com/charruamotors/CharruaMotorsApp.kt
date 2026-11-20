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
import com.charruamotors.obd.Elm327BluetoothClient
import com.charruamotors.obd.ModoObd
import com.charruamotors.obd.ObdClient
import com.charruamotors.obd.ObdClientFake
import com.charruamotors.obd.pid.CatalogoPids

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
     * Modo atual do scanner. Para alternar facilmente: troque o valor inicial abaixo
     * (SIMULADO no emulador/apresentação, BLUETOOTH com o scanner ELM327 real) ou chame
     * [selecionarModoObd] — de preferência com o scanner desconectado.
     */
    var modoObd: ModoObd = ModoObd.SIMULADO
        private set

    /** Catálogo de PIDs (fórmulas, unidades, faixas), lido uma vez de `assets/pids_padrao.json`. */
    val catalogoPids: CatalogoPids by lazy {
        CatalogoPids.deJson(assets.open(CatalogoPids.ARQUIVO_PADRAO).bufferedReader().use { it.readText() })
    }

    // Um cliente por modo, criados sob demanda e mantidos vivos (a conexão sobrevive às telas).
    private val obdSimulado: ObdClient by lazy { ObdClientFake() }

    private val obdBluetooth: ObdClient by lazy {
        val adaptador = getSystemService(BluetoothManager::class.java)?.adapter
        Elm327BluetoothClient(adaptador, catalogoPids)
    }

    /** Cliente OBD2 do modo atual; ViewModels dependem apenas da interface [ObdClient]. */
    val obdClient: ObdClient
        get() = obdClientDe(modoObd)

    /** Cliente OBD2 de um modo específico (usado pela UI para observar o estado de cada modo). */
    fun obdClientDe(modo: ModoObd): ObdClient = when (modo) {
        ModoObd.SIMULADO -> obdSimulado
        ModoObd.BLUETOOTH -> obdBluetooth
    }

    fun selecionarModoObd(modo: ModoObd) {
        modoObd = modo
    }
}
