package com.charruamotors.ui.oficina.sensores

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.charruamotors.R
import com.charruamotors.databinding.ItemLinhaSensorBinding
import com.charruamotors.oficina.sensores.LinhaSensor
import com.charruamotors.ui.util.definirTexto

/** Linhas da tela Todos os Sensores, com o botão "+ Painel" (estrela). */
class SensorLinhaAdapter(private val aoFixar: (LinhaSensor) -> Unit) :
    ListAdapter<LinhaSensor, SensorLinhaAdapter.Holder>(Diferencas) {

    class Holder(val binding: ItemLinhaSensorBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemLinhaSensorBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val linha = getItem(position)
        val contexto = holder.itemView.context
        with(holder.binding) {
            textoNome.definirTexto(linha.nome)
            textoValor.definirTexto(linha.valor)
            textoUnidade.definirTexto(linha.unidade)
            textoExtremos.isVisible = linha.minimo.isNotEmpty()
            textoExtremos.definirTexto(contexto.getString(R.string.sensores_extremos, linha.minimo, linha.maximo))
            textoPid.definirTexto(linha.pid)
            root.alpha = if (linha.respondeu) 1f else ALFA_SEM_RESPOSTA
            botaoPainel.setIconResource(if (linha.fixado) R.drawable.ic_estrela else R.drawable.ic_estrela_borda)
            botaoPainel.contentDescription = contexto.getString(
                if (linha.fixado) R.string.sensores_tirar_do_painel else R.string.sensores_fixar_no_painel,
                linha.nome,
            )
            botaoPainel.setOnClickListener { aoFixar(linha) }
        }
    }

    private object Diferencas : DiffUtil.ItemCallback<LinhaSensor>() {
        override fun areItemsTheSame(oldItem: LinhaSensor, newItem: LinhaSensor) = oldItem.comando == newItem.comando
        override fun areContentsTheSame(oldItem: LinhaSensor, newItem: LinhaSensor) = oldItem == newItem
    }

    private companion object {
        const val ALFA_SEM_RESPOSTA = 0.55f
    }
}
