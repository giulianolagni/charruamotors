package com.charruamotors.ui.dashboard

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.charruamotors.R
import com.charruamotors.databinding.ItemVeiculoBinding
import com.charruamotors.domain.model.Veiculo
import com.charruamotors.ui.util.rotulo

/** Adapter da lista de veículos do Dashboard. O DiffUtil anima só os itens que mudaram. */
class VeiculoAdapter(
    private val aoClicar: (Veiculo) -> Unit,
) : ListAdapter<Veiculo, VeiculoAdapter.ViewHolder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemVeiculoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    inner class ViewHolder(private val binding: ItemVeiculoBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(veiculo: Veiculo) = with(binding) {
            val context = root.context
            textoDescricao.text = veiculo.descricao
            textoPlaca.text = veiculo.placaFormatada
            textoDetalhes.text = context.getString(
                R.string.item_veiculo_detalhes,
                veiculo.kmAtual,
                veiculo.tipoCombustivel.rotulo(context),
            )
            root.setOnClickListener { aoClicar(veiculo) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<Veiculo>() {
        override fun areItemsTheSame(antigo: Veiculo, novo: Veiculo) = antigo.id == novo.id
        override fun areContentsTheSame(antigo: Veiculo, novo: Veiculo) = antigo == novo
    }
}
