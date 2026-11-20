package com.charruamotors.ui.diagnostico

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

/** Páginas do ViewPager2: cada aba é um Fragment que recebe o id do veículo como argumento. */
class DiagnosticoPagerAdapter(
    activity: FragmentActivity,
    private val veiculoId: Long,
) : FragmentStateAdapter(activity) {

    override fun getItemCount() = ABAS

    override fun createFragment(position: Int): Fragment = when (position) {
        else -> Fragment() // abas adicionadas nos próximos commits
    }

    companion object {
        const val ABA_OBD = 0
        const val ABA_MANUTENCOES = 1
        const val ABA_ABASTECIMENTOS = 2
        const val ABAS = 3
    }
}
