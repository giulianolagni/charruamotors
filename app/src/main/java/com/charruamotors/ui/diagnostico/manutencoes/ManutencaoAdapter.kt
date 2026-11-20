package com.charruamotors.ui.diagnostico.manutencoes

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.charruamotors.R
import com.charruamotors.databinding.ItemManutencaoBinding
import com.charruamotors.databinding.ItemProximasManutencoesBinding
import com.charruamotors.domain.model.ManutencaoComPecas
import com.charruamotors.ui.util.comoMilhar
import com.charruamotors.ui.util.comoMoeda
import com.charruamotors.ui.util.comoTexto
import com.charruamotors.ui.util.rotulo

/** Histórico de manutenções: clicar edita; o botão do item exclui (após confirmação). */
class ManutencaoAdapter(
    private val aoClicar: (ManutencaoComPecas) -> Unit,
    private val aoExcluir: (ManutencaoComPecas) -> Unit,
) : ListAdapter<ManutencaoComPecas, ManutencaoAdapter.ViewHolder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemManutencaoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    inner class ViewHolder(private val binding: ItemManutencaoBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ManutencaoComPecas) = with(binding) {
            val context = root.context
            val m = item.manutencao
            textoTipo.text = m.tipo.rotulo(context)
            textoCusto.text = m.custoTotal.comoMoeda()
            textoDetalhes.text = listOfNotNull(
                m.data.comoTexto(),
                context.getString(R.string.item_km, m.km.comoMilhar()),
                m.oficina,
            ).joinToString(" • ")
            textoPecas.text = if (item.pecas.isEmpty()) {
                m.descricao.orEmpty()
            } else {
                context.getString(
                    R.string.manutencao_item_pecas,
                    item.pecas.joinToString(", ") { "${it.nome} (${it.quantidade}x)" },
                )
            }
            textoPecas.visibility = if (textoPecas.text.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
            root.setOnClickListener { aoClicar(item) }
            botaoExcluir.setOnClickListener { aoExcluir(item) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<ManutencaoComPecas>() {
        override fun areItemsTheSame(antigo: ManutencaoComPecas, novo: ManutencaoComPecas) =
            antigo.manutencao.id == novo.manutencao.id

        override fun areContentsTheSame(antigo: ManutencaoComPecas, novo: ManutencaoComPecas) = antigo == novo
    }
}

/** Cabeçalho da lista (1 item) com a previsão das próximas manutenções; some quando o texto é nulo. */
class ProximasManutencoesAdapter : RecyclerView.Adapter<ProximasManutencoesAdapter.ViewHolder>() {

    var texto: CharSequence? = null
        set(valor) {
            if (field == valor) return
            val tinha = field != null
            field = valor
            when {
                !tinha && valor != null -> notifyItemInserted(0)
                tinha && valor == null -> notifyItemRemoved(0)
                else -> notifyItemChanged(0)
            }
        }

    override fun getItemCount() = if (texto != null) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemProximasManutencoesBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.binding.textoProximas.text = texto
    }

    class ViewHolder(val binding: ItemProximasManutencoesBinding) : RecyclerView.ViewHolder(binding.root)
}
