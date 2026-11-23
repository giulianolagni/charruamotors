package com.charruamotors.ui.permissoes

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.charruamotors.R
import com.charruamotors.databinding.ActivityChecklistPermissoesBinding
import com.charruamotors.sistema.permissoes.ChecklistPermissoes
import com.charruamotors.sistema.permissoes.ItemPermissao
import com.charruamotors.ui.util.aplicarInsetsDoSistema

/**
 * Checklist de Permissões (menu lateral → Sistema & App): status em tempo real de Bluetooth,
 * Notificações, Bateria Sem Restrições, Acesso a Notificações (Spotify) e Sobrepor Apps (HUD),
 * com a ação direta de cada um. Relê tudo ao voltar de uma tela do sistema (onResume).
 */
class ChecklistPermissoesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChecklistPermissoesBinding
    private val solicitador = SolicitadorPermissoes(this) { atualizar() }
    private var essenciais: PainelPermissoes? = null
    private var direcao: PainelPermissoes? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        binding = ActivityChecklistPermissoesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.appbar.aplicarInsetsDoSistema(base = false)
        binding.conteudo.aplicarInsetsDoSistema(topo = false)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        essenciais = PainelPermissoes(binding.listaEssenciais, ItemPermissao.ESSENCIAIS, solicitador)
        direcao = PainelPermissoes(binding.listaDirecao, ItemPermissao.DIRECAO, solicitador)
    }

    override fun onResume() {
        super.onResume()
        atualizar()
    }

    private fun atualizar() {
        val status = essenciais?.atualizar().orEmpty() + direcao?.atualizar().orEmpty()
        val pendentes = ChecklistPermissoes.pendentes(status)
        binding.toolbar.subtitle = if (pendentes == 0) {
            getString(R.string.checklist_resumo_ok)
        } else {
            getString(R.string.checklist_resumo_pendente, pendentes, status.size)
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, ChecklistPermissoesActivity::class.java)
    }
}
