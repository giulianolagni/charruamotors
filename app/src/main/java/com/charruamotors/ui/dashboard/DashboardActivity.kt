package com.charruamotors.ui.dashboard

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.R
import com.charruamotors.databinding.ActivityDashboardBinding
import com.charruamotors.databinding.MenuLateralCabecalhoBinding
import com.charruamotors.domain.calculo.ResumoVeiculo
import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.domain.model.Veiculo
import com.charruamotors.obd.sessao.ConfigAutoConexao
import com.charruamotors.obd.sessao.EstadoSessao
import com.charruamotors.sistema.StatusCaixaPreta
import com.charruamotors.sistema.backup.BackupInvalidoException
import com.charruamotors.sistema.backup.BackupRepository
import com.charruamotors.sistema.backup.DadosBackup
import com.charruamotors.sistema.backup.ResumoBackup
import com.charruamotors.ui.assistente.AssistenteActivity
import com.charruamotors.ui.configuracoes.ConfiguracoesActivity
import com.charruamotors.ui.configuracoes.DialogosCaixaPreta
import com.charruamotors.ui.diagnostico.DiagnosticoActivity
import com.charruamotors.ui.login.LoginActivity
import com.charruamotors.ui.oficina.bateria.TesteBateriaActivity
import com.charruamotors.ui.oficina.replay.ReplayLogActivity
import com.charruamotors.ui.oficina.terminal.TerminalObdActivity
import com.charruamotors.ui.permissoes.ChecklistPermissoesActivity
import com.charruamotors.ui.util.aplicarInsetsDoSistema
import com.charruamotors.ui.util.definirTexto
import com.charruamotors.ui.veiculo.VeiculoFormActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Activity 2 — Dashboard / Home: lista de veículos, resumo e atalho para o scanner OBD2, com o
 * menu lateral (3 riscos na toolbar): navegação rápida (veículos, logs, oficina), sistema & app
 * (Configurações, Checklist de Permissões, Backup e Restauração) e rodapé (Sobre e Sair).
 * Na primeira abertura após o login, abre o Assistente Inicial.
 */
class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding
    private lateinit var cabecalho: MenuLateralCabecalhoBinding
    private val app get() = application as CharruaMotorsApp
    private val sessao by lazy { app.sessao }
    private val viewModel: DashboardViewModel by viewModels { DashboardViewModel.Factory }
    private val dialogos = DialogosCaixaPreta(this) { startActivity(ChecklistPermissoesActivity.intent(this)) }
    private val adapter = VeiculoAdapter(
        aoClicar = { resumo -> startActivity(VeiculoFormActivity.intent(this, resumo.veiculo.id)) },
        aoDiagnosticar = { resumo -> abrirDiagnostico(resumo.veiculo) },
    )

    // Backup e Restauração via Storage Access Framework (sem permissão de armazenamento).
    private val criarBackup = registerForActivityResult(ActivityResultContracts.CreateDocument(BackupRepository.MIME_JSON)) { uri ->
        uri?.let(::exportarBackup)
    }
    private val abrirBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::lerBackup)
    }

    private val fecharMenuAoVoltar = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = binding.gaveta.closeDrawer(GravityCompat.START)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Ícones claros na status bar, porque a toolbar é azul.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")

        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.appbar.aplicarInsetsDoSistema(base = false)
        binding.conteudo.aplicarInsetsDoSistema(topo = false)

        configurarMenuLateral()
        binding.textoBoasVindas.text = getString(R.string.dashboard_boas_vindas, sessao.nomeUsuario.orEmpty())
        binding.botaoScanner.setOnClickListener { escolherVeiculoParaScanner() }
        binding.botaoNovoVeiculo.setOnClickListener {
            startActivity(VeiculoFormActivity.intent(this))
        }
        binding.listaVeiculos.adapter = adapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.resumos.collect { resumos -> resumos?.let(::mostrarResumos) } }
                launch {
                    app.preferenciasAutoConexao.config.combine(app.sessaoObd.estadoSessao) { c, e -> c to e }
                        .collect { (config, estado) -> mostrarCabecalho(config, estado) }
                }
            }
        }

        // Primeira abertura após o login: Assistente Inicial (refazível nas Configurações).
        if (savedInstanceState == null && app.preferenciasApp.deveMostrarAssistente) {
            startActivity(AssistenteActivity.intent(this))
        }
    }

    private fun mostrarResumos(resumos: List<ResumoVeiculo>) {
        adapter.submitList(resumos)
        binding.textoListaVazia.isVisible = resumos.isEmpty()
        binding.textoTotalVeiculos.text =
            resources.getQuantityString(R.plurals.dashboard_total_veiculos, resumos.size, resumos.size)
    }

    private fun abrirDiagnostico(veiculo: Veiculo) {
        startActivity(DiagnosticoActivity.intent(this, veiculo.id))
    }

    /** Atalho do scanner: abre direto com um único veículo; com vários, pergunta qual. */
    private fun escolherVeiculoParaScanner() {
        val veiculos = viewModel.resumos.value.orEmpty().map { it.veiculo }
        when (veiculos.size) {
            0 -> Snackbar.make(binding.root, R.string.scanner_sem_veiculo, Snackbar.LENGTH_SHORT).show()
            1 -> abrirDiagnostico(veiculos.first())
            else -> MaterialAlertDialogBuilder(this)
                .setTitle(R.string.scanner_escolher_veiculo)
                .setItems(veiculos.map { "${it.descricao} (${it.placaFormatada})" }.toTypedArray()) { _, indice ->
                    abrirDiagnostico(veiculos[indice])
                }
                .setNegativeButton(R.string.botao_cancelar, null)
                .show()
        }
    }

    // ------------------------------------------------------------------ menu lateral

    private fun configurarMenuLateral() {
        binding.toolbar.setNavigationOnClickListener { binding.gaveta.openDrawer(GravityCompat.START) }
        cabecalho = MenuLateralCabecalhoBinding.bind(binding.menuLateral.getHeaderView(0))
        cabecalho.textoUsuario.text = getString(R.string.menu_cabecalho_usuario, sessao.nomeUsuario.orEmpty())
        binding.menuLateral.setNavigationItemSelectedListener { item ->
            binding.gaveta.closeDrawer(GravityCompat.START)
            selecionarItemMenu(item)
            false // sem destaque permanente: cada item abre uma tela ou um diálogo
        }
        onBackPressedDispatcher.addCallback(this, fecharMenuAoVoltar)
        binding.gaveta.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                fecharMenuAoVoltar.isEnabled = true
            }

            override fun onDrawerClosed(drawerView: View) {
                fecharMenuAoVoltar.isEnabled = false
            }
        })
    }

    /** Cabeçalho: usuário, veículo ativo e bolinha verde/cinza da sessão OBD2. */
    private fun mostrarCabecalho(config: ConfigAutoConexao, estado: EstadoSessao) {
        val status = StatusCaixaPreta.cabecalho(estado)
        cabecalho.textoVeiculoAtivo.definirTexto(config.veiculoDescricao ?: getString(R.string.menu_cabecalho_sem_veiculo))
        cabecalho.textoStatusSessao.definirTexto(status.texto)
        cabecalho.pontoStatusSessao.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (status.conectado) R.color.status_ok else R.color.status_espera),
        )
        cabecalho.grupoStatusSessao.contentDescription = getString(R.string.menu_status_descricao, status.texto)
    }

    private fun selecionarItemMenu(item: MenuItem) {
        when (item.itemId) {
            R.id.menu_veiculos -> trocarVeiculo()
            R.id.menu_logs -> startActivity(ReplayLogActivity.intent(this))
            R.id.menu_oficina -> escolherFerramenta()
            R.id.menu_configuracoes -> startActivity(ConfiguracoesActivity.intent(this))
            R.id.menu_checklist -> startActivity(ChecklistPermissoesActivity.intent(this))
            R.id.menu_backup -> escolherBackup()
            R.id.menu_sobre -> mostrarSobre()
            R.id.menu_sair -> confirmarSaida()
        }
    }

    /** Troca rápida do veículo ativo (padrão da caixa-preta), com atalho para a aba OBD2 dele. */
    private fun trocarVeiculo() {
        dialogos.escolherVeiculo(
            aoEscolher = { veiculo ->
                Snackbar.make(binding.root, getString(R.string.veiculos_ativo_definido, veiculo.descricao), Snackbar.LENGTH_LONG)
                    .setAction(R.string.veiculos_abrir_obd) { abrirDiagnostico(veiculo) }
                    .show()
            },
            aoNenhum = { Snackbar.make(binding.root, R.string.veiculos_nenhum, Snackbar.LENGTH_LONG).show() },
        )
    }

    /** Veículo das ferramentas: o ativo, se ainda existir; senão o primeiro da lista. */
    private fun veiculoAtivo(): Veiculo? {
        val veiculos = viewModel.resumos.value.orEmpty().map { it.veiculo }
        return veiculos.firstOrNull { it.id == app.preferenciasAutoConexao.config.value.veiculoId } ?: veiculos.firstOrNull()
    }

    /** Ferramentas de Oficina: bateria e terminal usam a conexão ativa; o replay funciona sempre. */
    private fun escolherFerramenta() {
        val opcoes = arrayOf(
            getString(R.string.oficina_menu_bateria),
            getString(R.string.oficina_menu_terminal),
            getString(R.string.oficina_menu_replay),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.oficina_menu_titulo)
            .setItems(opcoes) { _, i ->
                if (i == 2) {
                    startActivity(ReplayLogActivity.intent(this))
                    return@setItems
                }
                val veiculo = veiculoAtivo()
                when {
                    veiculo == null -> Snackbar.make(binding.root, R.string.scanner_sem_veiculo, Snackbar.LENGTH_SHORT).show()
                    app.sessaoObd.estado.value !is EstadoConexaoObd.Conectado ->
                        Snackbar.make(binding.root, R.string.oficina_menu_requer_conexao, Snackbar.LENGTH_LONG)
                            .setAction(R.string.veiculos_abrir_obd) { abrirDiagnostico(veiculo) }
                            .show()
                    i == 0 -> startActivity(TesteBateriaActivity.intent(this, veiculo.id))
                    else -> startActivity(TerminalObdActivity.intent(this, veiculo.id))
                }
            }
            .setNegativeButton(R.string.botao_cancelar, null)
            .show()
    }

    // ------------------------------------------------------------------ backup e restauração

    private fun escolherBackup() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.backup_titulo)
            .setMessage(R.string.backup_descricao)
            .setPositiveButton(R.string.backup_exportar) { _, _ ->
                runCatching { criarBackup.launch(BackupRepository.nomeSugerido()) }.onFailure { mostrarErro(it) }
            }
            .setNeutralButton(R.string.backup_restaurar) { _, _ ->
                runCatching { abrirBackup.launch(arrayOf(BackupRepository.MIME_JSON, "text/plain", "application/octet-stream")) }
                    .onFailure { mostrarErro(it) }
            }
            .setNegativeButton(R.string.botao_cancelar, null)
            .show()
    }

    private fun exportarBackup(destino: Uri) {
        lifecycleScope.launch {
            runCatching { app.backupRepository.exportar(contentResolver, destino) }
                .onSuccess { r ->
                    val texto = getString(R.string.backup_exportado, r.veiculos, r.manutencoes, r.abastecimentos)
                    Snackbar.make(binding.root, texto, Snackbar.LENGTH_LONG).show()
                }
                .onFailure { e ->
                    Log.e(TAG, "Falha ao exportar o backup", e)
                    Snackbar.make(binding.root, getString(R.string.backup_erro_exportar, e.message.orEmpty()), Snackbar.LENGTH_LONG).show()
                }
        }
    }

    /** Lê e valida o arquivo; só pede confirmação se ele for um backup válido. */
    private fun lerBackup(origem: Uri) {
        lifecycleScope.launch {
            runCatching { app.backupRepository.ler(contentResolver, origem) }
                .onSuccess(::confirmarRestauracao)
                .onFailure { e ->
                    Log.w(TAG, "Backup recusado", e)
                    val motivo = (e as? BackupInvalidoException)?.message ?: e.message.orEmpty()
                    Snackbar.make(binding.root, getString(R.string.backup_erro_ler, motivo), Snackbar.LENGTH_LONG).show()
                }
        }
    }

    private fun confirmarRestauracao(dados: DadosBackup) {
        val r = ResumoBackup.de(dados)
        val sessaoAtiva = app.sessaoObd.estadoSessao.value.ativa
        val mensagem = getString(
            R.string.backup_confirmar_mensagem,
            r.geradoEm,
            r.versaoApp ?: getString(R.string.backup_versao_desconhecida),
            r.veiculos, r.manutencoes, r.abastecimentos, r.leituras, r.codigosFalha,
        ) + if (sessaoAtiva) getString(R.string.backup_confirmar_sessao) else ""
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.backup_confirmar_titulo)
            .setMessage(mensagem)
            .setNegativeButton(R.string.botao_cancelar, null)
            .setPositiveButton(R.string.backup_confirmar_acao) { _, _ -> restaurar(dados) }
            .show()
    }

    private fun restaurar(dados: DadosBackup) {
        // A sessão grava no veículo atual: encerra antes de trocar o banco (os logs são salvos).
        if (app.sessaoObd.estadoSessao.value.ativa) app.sessaoObd.desconectar("Restauração de backup")
        lifecycleScope.launch {
            runCatching { app.backupRepository.restaurar(dados) }
                .onSuccess {
                    AppCompatDelegate.setDefaultNightMode(app.preferenciasApp.tema.modoNoturno)
                    Snackbar.make(binding.root, getString(R.string.backup_restaurado, dados.veiculos.size), Snackbar.LENGTH_LONG).show()
                }
                .onFailure { e ->
                    Log.e(TAG, "Falha ao restaurar o backup", e)
                    Snackbar.make(binding.root, getString(R.string.backup_erro_restaurar, e.message.orEmpty()), Snackbar.LENGTH_LONG).show()
                }
        }
    }

    private fun mostrarErro(e: Throwable) {
        Snackbar.make(binding.root, e.message ?: getString(R.string.permissao_erro_abrir), Snackbar.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------------ rodapé

    /** Versão, versionCode e o estado do catálogo/protocolo. */
    private fun mostrarSobre() {
        val info = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        val versionCode = info?.let { PackageInfoCompat.getLongVersionCode(it).toInt() } ?: 0
        val conectado = app.sessaoObd.estado.value is EstadoConexaoObd.Conectado
        val conexao = app.sessaoObd.infoConexao.value
        val protocolo = if (conectado) {
            listOfNotNull(conexao.protocolo, conexao.nomeModo).joinToString(" · ")
        } else {
            getString(R.string.sobre_sem_conexao)
        }
        val estendido = app.catalogoEstendido
        val cascata = app.catalogoCascata
        val detalhes = getString(
            R.string.sobre_detalhes,
            versionCode,
            packageName,
            app.catalogoPids.pids.size,
            estendido.perfis.size,
            estendido.definicoes.size,
            app.catalogoDtc.total,
            cascata.protocolosFisicos.size,
            cascata.modosServico.size,
            protocolo,
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.sobre_versao, info?.versionName ?: "?"))
            .setIcon(R.mipmap.ic_launcher_round)
            .setMessage(detalhes)
            .setPositiveButton(R.string.botao_fechar, null)
            .show()
    }

    /** Confirmação para evitar o logout acidental (o banco local e os logs ficam). */
    private fun confirmarSaida() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sair_titulo)
            .setMessage(R.string.sair_mensagem)
            .setNegativeButton(R.string.botao_cancelar, null)
            .setPositiveButton(R.string.sair_confirmar) { _, _ -> sair() }
            .show()
    }

    /** Logout: encerra só a sessão de login e volta ao login limpando a pilha de Activities. */
    private fun sair() {
        sessao.encerrar()
        startActivity(
            Intent(this, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
    }

    // Callbacks do ciclo de vida registrados no Logcat (filtro "DashboardActivity")
    // para demonstrar a ordem em que o Android os chama.
    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart: tela ficando visível")
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume: tela em primeiro plano, interagindo com o usuário")
    }

    override fun onPause() {
        Log.d(TAG, "onPause: tela perdendo o foco")
        super.onPause()
    }

    override fun onStop() {
        Log.d(TAG, "onStop: tela não está mais visível")
        super.onStop()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        super.onDestroy()
    }

    private companion object {
        const val TAG = "DashboardActivity"
    }
}
