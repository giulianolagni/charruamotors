package com.charruamotors.ui.diagnostico.obd

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.charruamotors.databinding.ItemCodigoFalhaBinding
import com.charruamotors.obd.DtcLido

/** Lista dos códigos de falha (DTCs) lidos da ECU. */
class CodigoFalhaAdapter : ListAdapter<DtcLido, CodigoFalhaAdapter.ViewHolder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemCodigoFalhaBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    class ViewHolder(private val binding: ItemCodigoFalhaBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(dtc: DtcLido) {
            binding.textoCodigo.text = dtc.codigo
            binding.textoDescricao.text = dtc.descricao
        }
    }

    private object Diff : DiffUtil.ItemCallback<DtcLido>() {
        override fun areItemsTheSame(antigo: DtcLido, novo: DtcLido) = antigo.codigo == novo.codigo
        override fun areContentsTheSame(antigo: DtcLido, novo: DtcLido) = antigo == novo
    }
}
