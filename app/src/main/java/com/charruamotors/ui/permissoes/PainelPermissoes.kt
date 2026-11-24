package com.charruamotors.ui.permissoes

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.charruamotors.R
import com.charruamotors.databinding.ItemPermissaoBinding
import com.charruamotors.sistema.permissoes.ItemPermissao
import com.charruamotors.sistema.permissoes.LeitorPermissoes
import com.charruamotors.sistema.permissoes.SituacaoPermissao
import com.charruamotors.sistema.permissoes.StatusPermissao

/**
 * Linhas de permissão (bolinha verde/vermelha, título, descrição, status e ação direta) dentro de
 * um [LinearLayout]. Usado pelo Checklist de Permissões e pelas etapas 1 e 4 do assistente.
 */
class PainelPermissoes(
    private val container: LinearLayout,
    private val itens: List<ItemPermissao>,
    private val solicitador: SolicitadorPermissoes,
) {
    private val linhas: Map<ItemPermissao, ItemPermissaoBinding> = itens.associateWith { item ->
        ItemPermissaoBinding.inflate(LayoutInflater.from(container.context), container, true).apply {
            textoTitulo.setText(titulo(item))
            textoDescricao.setText(descricao(item))
            botaoAcao.setText(if (item == ItemPermissao.BLUETOOTH || item == ItemPermissao.NOTIFICACOES) R.string.permissao_acao_permitir else R.string.permissao_acao_abrir)
            botaoAcao.setOnClickListener { solicitador.solicitar(item) }
        }
    }

    /** Relê o sistema e redesenha; devolve os status (para o resumo da tela). */
    fun atualizar(): List<StatusPermissao> {
        val contexto = container.context
        val status = LeitorPermissoes.avaliar(contexto, itens)
        status.forEach { s ->
            val linha = linhas.getValue(s.item)
            val cor = ContextCompat.getColor(contexto, if (s.ok) R.color.status_ok else R.color.status_pendente)
            linha.pontoStatus.backgroundTintList = ColorStateList.valueOf(cor)
            linha.textoStatus.setTextColor(cor)
            linha.textoStatus.setText(
                when (s.situacao) {
                    SituacaoPermissao.OK -> R.string.permissao_status_ok
                    SituacaoPermissao.PENDENTE -> R.string.permissao_status_pendente
                    SituacaoPermissao.NAO_APLICAVEL -> R.string.permissao_status_nao_aplicavel
                },
            )
            linha.botaoAcao.isVisible = s.situacao == SituacaoPermissao.PENDENTE
        }
        return status
    }

    private companion object {
        fun titulo(item: ItemPermissao) = when (item) {
            ItemPermissao.BLUETOOTH -> R.string.permissao_bluetooth
            ItemPermissao.NOTIFICACOES -> R.string.permissao_notificacoes
            ItemPermissao.BATERIA_SEM_RESTRICOES -> R.string.permissao_bateria
            ItemPermissao.ACESSO_NOTIFICACOES -> R.string.permissao_acesso_notificacoes
            ItemPermissao.SOBREPOR_APPS -> R.string.permissao_sobrepor
        }

        fun descricao(item: ItemPermissao) = when (item) {
            ItemPermissao.BLUETOOTH -> R.string.permissao_bluetooth_descricao
            ItemPermissao.NOTIFICACOES -> R.string.permissao_notificacoes_descricao
            ItemPermissao.BATERIA_SEM_RESTRICOES -> R.string.permissao_bateria_descricao
            ItemPermissao.ACESSO_NOTIFICACOES -> R.string.permissao_acesso_notificacoes_descricao
            ItemPermissao.SOBREPOR_APPS -> R.string.permissao_sobrepor_descricao
        }
    }
}
