package com.charruamotors.ui.diagnostico

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.charruamotors.R
import com.charruamotors.ui.diagnostico.historico.HistoricoFragment
import com.charruamotors.ui.diagnostico.manutencoes.ManutencoesFragment
import com.charruamotors.ui.diagnostico.obd.ObdFragment
import com.charruamotors.ui.diagnostico.oficina.OficinaFragment

/** Páginas das 4 abas principais: cada uma é um Fragment que recebe o id do veículo como argumento. */
class DiagnosticoPagerAdapter(
    activity: FragmentActivity,
    private val veiculoId: Long,
) : FragmentStateAdapter(activity) {

    override fun getItemCount() = ABAS

    override fun createFragment(position: Int): Fragment = when (position) {
        ABA_PAINEL -> ObdFragment.novo(veiculoId)
        ABA_OFICINA -> OficinaFragment.novo(veiculoId)
        ABA_MANUTENCOES -> ManutencoesFragment.novo(veiculoId)
        else -> HistoricoFragment.novo(veiculoId)
    }

    companion object {
        const val ABA_PAINEL = 0
        const val ABA_OFICINA = 1
        const val ABA_MANUTENCOES = 2
        const val ABA_HISTORICO = 3
        const val ABAS = 4

        /** Item da navegação inferior ↔ página. */
        fun paginaDoItem(itemId: Int): Int = when (itemId) {
            R.id.aba_oficina -> ABA_OFICINA
            R.id.aba_manutencoes -> ABA_MANUTENCOES
            R.id.aba_historico -> ABA_HISTORICO
            else -> ABA_PAINEL
        }

        fun itemDaPagina(pagina: Int): Int = when (pagina) {
            ABA_OFICINA -> R.id.aba_oficina
            ABA_MANUTENCOES -> R.id.aba_manutencoes
            ABA_HISTORICO -> R.id.aba_historico
            else -> R.id.aba_painel
        }
    }
}
