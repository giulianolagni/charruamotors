package com.charruamotors.ui.dashboard

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.charruamotors.R
import com.charruamotors.databinding.ItemVeiculoBinding
import com.charruamotors.domain.calculo.ResumoVeiculo
import com.charruamotors.ui.util.comoConsumo
import com.charruamotors.ui.util.comoMilhar
import com.charruamotors.ui.util.comoTexto
import com.charruamotors.ui.util.rotulo

/**
 * Um card de Meus Carros: o resumo calculado, se ele é o carro ativo (estrela acesa; v2.3.0 = o
 * perfil em uso) e a tag do perfil isolado (`Modo 21`, `CAN OBD2`, `OBD2 Genérico`).
 */
data class ItemVeiculo(val resumo: ResumoVeiculo, val favorito: Boolean, val tag: String = "")

/**
 * Adapter de Meus Veículos. Apenas exibe o [ResumoVeiculo] já calculado pelo ViewModel/domínio
 * (nenhum cálculo aqui), com a estrela do favorito. O DiffUtil anima só os itens que mudaram.
 */
class VeiculoAdapter(
    private val aoClicar: (ResumoVeiculo) -> Unit,
    private val aoAbrirPainel: (ResumoVeiculo) -> Unit,
    private val aoFavoritar: (ResumoVeiculo) -> Unit,
    /** v2.3.0: menu de ações (selecionar, editar, perfil técnico, duplicar, mapeamento). */
    private val aoMaisAcoes: (View, ResumoVeiculo) -> Unit = { _, _ -> },
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

            val conversor = (context.applicationContext as com.charruamotors.CharruaMotorsApp).preferenciasApp.config.value.conversor
            textoConsumo.text = resumo.consumoMedio?.comoConsumo(conversor)
                ?.let { context.getString(R.string.item_veiculo_consumo, it) }
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

            textoPerfil.text = if (item.favorito) context.getString(R.string.carros_perfil_ativo, item.tag) else item.tag
            textoPerfil.isVisible = item.tag.isNotEmpty() || item.favorito
            botaoMaisAcoes.contentDescription = context.getString(R.string.carros_mais_acoes, veiculo.descricao)
            botaoMaisAcoes.setOnClickListener { aoMaisAcoes(it, resumo) }

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
