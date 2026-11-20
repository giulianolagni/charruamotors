package com.charruamotors.ui.diagnostico.obd

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.charruamotors.R
import com.charruamotors.databinding.ItemSensorBinding
import com.charruamotors.ui.util.comoNumero

/** Grade com todos os PIDs suportados pela ECU e o valor ao vivo de cada um. */
class SensorAdapter : ListAdapter<LeituraSensor, SensorAdapter.ViewHolder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemSensorBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    /** Só o valor mudou: atualiza o texto sem refazer a célula inteira (evita piscar a cada ciclo). */
    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) holder.bind(getItem(position)) else holder.bindValor(getItem(position))
    }

    class ViewHolder(private val binding: ItemSensorBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(leitura: LeituraSensor) {
            binding.textoNome.text = leitura.definicao.nome
            binding.textoComando.text = leitura.definicao.comando
            bindValor(leitura)
        }

        fun bindValor(leitura: LeituraSensor) {
            val contexto = binding.root.context
            val def = leitura.definicao
            binding.textoValor.text = leitura.valor?.let {
                contexto.getString(R.string.obd_valor_unidade, it.comoNumero(def.casasDecimais), def.unidade)
            } ?: contexto.getString(R.string.valor_indisponivel)
            binding.root.contentDescription = "${def.nome}: ${binding.textoValor.text}"
        }
    }

    private object Diff : DiffUtil.ItemCallback<LeituraSensor>() {
        override fun areItemsTheSame(antigo: LeituraSensor, novo: LeituraSensor) =
            antigo.definicao.comando == novo.definicao.comando

        override fun areContentsTheSame(antigo: LeituraSensor, novo: LeituraSensor) = antigo.valor == novo.valor

        override fun getChangePayload(antigo: LeituraSensor, novo: LeituraSensor): Any = PAYLOAD_VALOR
    }

    private companion object {
        const val PAYLOAD_VALOR = "valor"
    }
}
