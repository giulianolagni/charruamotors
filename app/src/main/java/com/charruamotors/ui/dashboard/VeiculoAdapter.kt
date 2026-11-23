package com.charruamotors.ui.dashboard

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.charruamotors.R
import com.charruamotors.databinding.ItemVeiculoBinding
import com.charruamotors.domain.calculo.ResumoVeiculo
import com.charruamotors.ui.util.comoDecimal
import com.charruamotors.ui.util.comoMilhar
import com.charruamotors.ui.util.comoTexto
import com.charruamotors.ui.util.rotulo

/** Um card de Meus Veículos: o resumo calculado e se ele é o favorito (estrela acesa). */
data class ItemVeiculo(val resumo: ResumoVeiculo, val favorito: Boolean)

/**
 * Adapter de Meus Veículos. Apenas exibe o [ResumoVeiculo] já calculado pelo ViewModel/domínio
 * (nenhum cálculo aqui), com a estrela do favorito. O DiffUtil anima só os itens que mudaram.
 */
class VeiculoAdapter(
    private val aoClicar: (ResumoVeiculo) -> Unit,
    private val aoAbrirPainel: (ResumoVeiculo) -> Unit,
    private val aoFavoritar: (ResumoVeiculo) -> Unit,
) : ListAdapter<ItemVeiculo, VeiculoAdapter.ViewHolder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemVeiculoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    inner class ViewHolder(private val binding: ItemVeiculoBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ItemVeiculo) = with(binding) {
            val resumo = item.resumo
            val context = root.context
            val veiculo = resumo.veiculo
            textoDescricao.text = veiculo.descricao
            textoPlaca.text = veiculo.placaFormatada
            textoDetalhes.text = context.getString(
                R.string.item_veiculo_detalhes,
                veiculo.kmAtual.comoMilhar(),
                veiculo.tipoCombustivel.rotulo(context),
            )

            textoConsumo.text = resumo.consumoMedio
                ?.let { context.getString(R.string.item_veiculo_consumo, it.comoDecimal(1)) }
                ?: context.getString(R.string.item_veiculo_sem_consumo)

            val proxima = resumo.proximaManutencao
            textoProximaManutencao.text = proxima
                ?.let { context.getString(R.string.item_veiculo_proxima_manutencao, it.comoTexto(context)) }
                ?: context.getString(R.string.item_veiculo_sem_previsao)
            val corProxima = if (proxima?.vencida == true) R.color.erro else R.color.texto_secundario
            textoProximaManutencao.setTextColor(ContextCompat.getColor(context, corProxima))

            textoAlertaFalhas.isVisible = resumo.temFalhasAtivas
            textoAlertaFalhas.text = context.resources.getQuantityString(
                R.plurals.item_veiculo_falhas_ativas, resumo.falhasAtivas, resumo.falhasAtivas,
            )

            botaoFavorito.setImageResource(if (item.favorito) R.drawable.ic_estrela else R.drawable.ic_estrela_borda)
            botaoFavorito.contentDescription = context.getString(
                if (item.favorito) R.string.veiculos_e_favorito else R.string.veiculos_marcar_favorito,
                veiculo.descricao,
            )
            botaoFavorito.setOnClickListener { if (!item.favorito) aoFavoritar(resumo) }

            root.setOnClickListener { aoClicar(resumo) }
            botaoDiagnostico.setOnClickListener { aoAbrirPainel(resumo) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<ItemVeiculo>() {
        override fun areItemsTheSame(antigo: ItemVeiculo, novo: ItemVeiculo) =
            antigo.resumo.veiculo.id == novo.resumo.veiculo.id

        override fun areContentsTheSame(antigo: ItemVeiculo, novo: ItemVeiculo) = antigo == novo
    }
}
