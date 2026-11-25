package com.charruamotors.ui.navegacao

import android.content.Intent
import android.content.res.ColorStateList
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.R
import com.charruamotors.databinding.MenuLateralCabecalhoBinding
import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.obd.sessao.ConfigAutoConexao
import com.charruamotors.obd.sessao.EstadoSessao
import com.charruamotors.sistema.StatusCaixaPreta
import com.charruamotors.sistema.navegacao.AcaoMenuLateral
import com.charruamotors.ui.atualizacao.AtualizadorApp
import com.charruamotors.ui.configuracoes.ConfiguracoesActivity
import com.charruamotors.ui.configuracoes.ControladorBackup
import com.charruamotors.ui.login.LoginActivity
import com.charruamotors.ui.permissoes.ChecklistPermissoesActivity
import com.charruamotors.ui.util.definirTexto
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Menu lateral (3 riscos) das telas principais — as 4 abas do veículo e, sem veículo cadastrado,
 * Meus Veículos —, só com ações globais ([AcaoMenuLateral]): Meus Veículos, Configurações,
 * Checklist de Permissões, Backup e Restauração, Sobre e Sair.
 *
 * As telas abertas daqui ficam **por cima** da atual (sem flags de pilha): a seta ou o gesto de
 * Voltar retornam exatamente para a aba/tela de onde o usuário saiu. Só o logout limpa a pilha.
 *
 * Crie como propriedade da Activity (registra os seletores de arquivo do backup antes do
 * `onCreate`) e chame [ligar] no `onCreate`.
 */
class MenuLateralGlobal(private val activity: AppCompatActivity) {

    private val app get() = activity.application as CharruaMotorsApp
    private lateinit var gaveta: DrawerLayout
    private lateinit var raiz: View
    private lateinit var cabecalho: MenuLateralCabecalhoBinding
    private var aoMeusVeiculos: () -> Unit = {}

    /** GitHub Releases: "Verificar atualizações" no Sobre e a verificação silenciosa da abertura. */
    private val atualizador = AtualizadorApp(activity)

    /** Backup e Restauração via Storage Access Framework (o mesmo controlador das Configurações). */
    private val backup = ControladorBackup(activity) { raiz }

    private val fecharMenuAoVoltar = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = gaveta.closeDrawer(GravityCompat.START)
    }

    /**
     * Liga a gaveta, o ícone de 3 riscos da [toolbar] e o cabeçalho (usuário, veículo favorito e a
     * bolinha da sessão OBD2). [raiz] recebe os Snackbars; [aoMeusVeiculos] trata o item Meus Veículos.
     */
    fun ligar(gaveta: DrawerLayout, menu: NavigationView, toolbar: MaterialToolbar, raiz: View, aoMeusVeiculos: () -> Unit) {
        this.gaveta = gaveta
        this.raiz = raiz
        this.aoMeusVeiculos = aoMeusVeiculos
        toolbar.setNavigationIcon(R.drawable.ic_menu)
        toolbar.setNavigationContentDescription(R.string.menu_abrir)
        toolbar.setNavigationOnClickListener { gaveta.openDrawer(GravityCompat.START) }
        cabecalho = MenuLateralCabecalhoBinding.bind(menu.getHeaderView(0))
        cabecalho.textoUsuario.text = activity.getString(R.string.menu_cabecalho_usuario, app.sessao.nomeUsuario.orEmpty())
        menu.setNavigationItemSelectedListener { item ->
            gaveta.closeDrawer(GravityCompat.START)
            acaoDoItem(item.itemId)?.let(::executar)
            false // sem destaque permanente: cada item abre uma tela ou um diálogo
        }
        activity.onBackPressedDispatcher.addCallback(activity, fecharMenuAoVoltar)
        gaveta.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                fecharMenuAoVoltar.isEnabled = true
            }

            override fun onDrawerClosed(drawerView: View) {
                fecharMenuAoVoltar.isEnabled = false
            }
        })
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.preferenciasAutoConexao.config.combine(app.sessaoObd.estadoSessao) { c, e -> c to e }
                    .collect { (config, estado) -> mostrarCabecalho(config, estado) }
            }
        }
    }

    private fun acaoDoItem(id: Int): AcaoMenuLateral? = when (id) {
        R.id.menu_veiculos -> AcaoMenuLateral.VEICULOS
        R.id.menu_configuracoes -> AcaoMenuLateral.CONFIGURACOES
        R.id.menu_checklist -> AcaoMenuLateral.CHECKLIST
        R.id.menu_backup -> AcaoMenuLateral.BACKUP
        R.id.menu_sobre -> AcaoMenuLateral.SOBRE
        R.id.menu_sair -> AcaoMenuLateral.SAIR
        else -> null
    }

    private fun executar(acao: AcaoMenuLateral) {
        when (acao) {
            AcaoMenuLateral.VEICULOS -> aoMeusVeiculos()
            // startActivity simples: a tela entra por cima e o Voltar volta para cá, na mesma aba.
            AcaoMenuLateral.CONFIGURACOES -> activity.startActivity(ConfiguracoesActivity.intent(activity))
            AcaoMenuLateral.CHECKLIST -> activity.startActivity(ChecklistPermissoesActivity.intent(activity))
            AcaoMenuLateral.BACKUP -> backup.escolher()
            AcaoMenuLateral.SOBRE -> mostrarSobre()
            AcaoMenuLateral.SAIR -> confirmarSaida()
        }
    }

    /** Cabeçalho: usuário, veículo favorito e bolinha verde/cinza da sessão OBD2. */
    private fun mostrarCabecalho(config: ConfigAutoConexao, estado: EstadoSessao) {
        val status = StatusCaixaPreta.cabecalho(estado)
        cabecalho.textoVeiculoAtivo.definirTexto(
            config.veiculoDescricao?.let { "★ $it" } ?: activity.getString(R.string.menu_cabecalho_sem_veiculo),
        )
        cabecalho.textoStatusSessao.definirTexto(status.texto)
        cabecalho.pontoStatusSessao.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(activity, if (status.conectado) R.color.status_ok else R.color.status_espera),
        )
        cabecalho.grupoStatusSessao.contentDescription = activity.getString(R.string.menu_status_descricao, status.texto)
    }

    /**
     * Abertura da tela principal (cold start, não a recriação por rotação): procura uma versão nova
     * no GitHub em segundo plano e só mostra o diálogo se ela existir.
     */
    fun verificarAtualizacaoAoAbrir(primeiraCriacao: Boolean) {
        if (primeiraCriacao) atualizador.verificarAoAbrir()
    }

    // ------------------------------------------------------------------ rodapé

    /** Versão, versionCode e o estado do catálogo/protocolo. */
    private fun mostrarSobre() {
        val pm = activity.packageManager
        val info = runCatching { pm.getPackageInfo(activity.packageName, 0) }.getOrNull()
        val versionCode = info?.let { PackageInfoCompat.getLongVersionCode(it).toInt() } ?: 0
        val conectado = app.sessaoObd.estado.value is EstadoConexaoObd.Conectado
        val conexao = app.sessaoObd.infoConexao.value
        val protocolo = if (conectado) {
            listOfNotNull(conexao.protocolo, conexao.nomeModo).joinToString(" · ")
        } else {
            activity.getString(R.string.sobre_sem_conexao)
        }
        val estendido = app.catalogoEstendido
        val cascata = app.catalogoCascata
        val detalhes = activity.getString(
            R.string.sobre_detalhes,
            versionCode,
            activity.packageName,
            app.catalogoPids.pids.size,
            estendido.perfis.size,
            estendido.definicoes.size,
            app.catalogoDtc.total,
            cascata.protocolosFisicos.size,
            cascata.modosServico.size,
            protocolo,
        )
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.sobre_versao, info?.versionName ?: "?"))
            .setIcon(R.mipmap.ic_launcher_round)
            .setMessage(detalhes)
            .setPositiveButton(R.string.botao_fechar, null)
            .setNeutralButton(R.string.atualizacao_verificar) { _, _ -> atualizador.verificarManualmente() }
            .show()
    }

    /** Confirmação para evitar o logout acidental (o banco local e os logs ficam). */
    private fun confirmarSaida() {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.sair_titulo)
            .setMessage(R.string.sair_mensagem)
            .setNegativeButton(R.string.botao_cancelar, null)
            .setPositiveButton(R.string.sair_confirmar) { _, _ -> sair() }
            .show()
    }

    /** Logout: o único item que limpa a pilha (volta ao login sem telas antigas por baixo). */
    private fun sair() {
        app.sessao.encerrar()
        activity.startActivity(
            Intent(activity, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
    }

    private companion object {
        const val TAG = "MenuLateralGlobal"
    }
}
