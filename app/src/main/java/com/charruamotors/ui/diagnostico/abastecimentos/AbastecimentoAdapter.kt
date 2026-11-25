package com.charruamotors.ui.diagnostico.abastecimentos

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.charruamotors.R
import com.charruamotors.databinding.ItemAbastecimentoBinding
import com.charruamotors.databinding.ItemIndicadoresAbastecimentoBinding
import com.charruamotors.domain.model.Abastecimento
import com.charruamotors.ui.util.comoConsumo
import com.charruamotors.ui.util.comoDecimal
import com.charruamotors.ui.util.comoMilhar
import com.charruamotors.ui.util.comoMoeda
import com.charruamotors.ui.util.comoTexto
import com.charruamotors.ui.util.rotulo

/** Histórico de abastecimentos: clicar edita; o botão do item exclui (após confirmação). */
class AbastecimentoAdapter(
    private val aoClicar: (Abastecimento) -> Unit,
    private val aoExcluir: (Abastecimento) -> Unit,
) : ListAdapter<Abastecimento, AbastecimentoAdapter.ViewHolder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemAbastecimentoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    inner class ViewHolder(private val binding: ItemAbastecimentoBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(a: Abastecimento) = with(binding) {
            val context = root.context
            textoTitulo.text = context.getString(
                R.string.abastecimento_item_titulo,
                a.litros.comoDecimal(2),
                a.tipoCombustivel.rotulo(context),
            )
            textoValor.text = a.valorTotal.comoMoeda()
            textoDetalhes.text = listOfNotNull(
                a.data.comoTexto(),
                context.getString(R.string.item_km, a.km.comoMilhar()),
                if (a.tanqueCheio) context.getString(R.string.abastecimento_tanque_cheio) else null,
                context.getString(R.string.abastecimento_preco_litro, a.precoPorLitro.comoMoeda()),
            ).joinToString(" • ")
            root.setOnClickListener { aoClicar(a) }
            botaoExcluir.setOnClickListener { aoExcluir(a) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<Abastecimento>() {
        override fun areItemsTheSame(antigo: Abastecimento, novo: Abastecimento) = antigo.id == novo.id
        override fun areContentsTheSame(antigo: Abastecimento, novo: Abastecimento) = antigo == novo
    }
}

/** Cabeçalho da lista (1 item) com consumo médio e custo por km; some até haver dados. */
class IndicadoresAbastecimentoAdapter : RecyclerView.Adapter<IndicadoresAbastecimentoAdapter.ViewHolder>() {

    private var visivel = false
    private var consumo: Double? = null
    private var custoPorKm: Double? = null

    /** Mostra o cartão quando há ao menos um abastecimento; valores nulos viram "—". */
    fun atualizar(mostrar: Boolean, consumoMedio: Double?, custoKm: Double?) {
        val mudouVisibilidade = mostrar != visivel
        visivel = mostrar
        consumo = consumoMedio
        custoPorKm = custoKm
        when {
            mudouVisibilidade && mostrar -> notifyItemInserted(0)
            mudouVisibilidade -> notifyItemRemoved(0)
            mostrar -> notifyItemChanged(0)
        }
    }

    override fun getItemCount() = if (visivel) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemIndicadoresAbastecimentoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = with(holder.binding) {
        val context = root.context
        val vazio = context.getString(R.string.valor_indisponivel)
        val conversor = (context.applicationContext as com.charruamotors.CharruaMotorsApp).preferenciasApp.config.value.conversor
        valorConsumo.text = consumo?.comoConsumo(conversor) ?: vazio
        valorCustoKm.text = custoPorKm?.let { context.getString(R.string.abastecimento_valor_custo_km, it.comoMoeda()) } ?: vazio
    }

    class ViewHolder(val binding: ItemIndicadoresAbastecimentoBinding) : RecyclerView.ViewHolder(binding.root)
}
