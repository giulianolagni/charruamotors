package com.charruamotors.ui.diagnostico.obd

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.charruamotors.R
import com.charruamotors.databinding.ItemLeituraHistoricoBinding
import com.charruamotors.domain.model.LeituraObd
import com.charruamotors.ui.util.comoMilhar
import com.charruamotors.ui.util.comoTexto

/** Histórico compacto das leituras OBD2 salvas: data/hora, RPM, velocidade e temperatura. */
class LeituraObdAdapter : ListAdapter<LeituraObd, LeituraObdAdapter.ViewHolder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemLeituraHistoricoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    class ViewHolder(private val binding: ItemLeituraHistoricoBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(leitura: LeituraObd) = with(binding) {
            val context = root.context
            val vazio = context.getString(R.string.valor_indisponivel)
            textoDataHora.text = leitura.dataHora.comoTexto()
            textoValores.text = context.getString(
                R.string.obd_historico_valores,
                leitura.rpm?.let { context.getString(R.string.obd_valor_rpm, it.comoMilhar()) } ?: vazio,
                leitura.velocidade?.let { context.getString(R.string.obd_valor_velocidade, it) } ?: vazio,
                leitura.temperaturaMotor?.let { context.getString(R.string.obd_valor_temperatura, it) } ?: vazio,
            )
        }
    }

    private object Diff : DiffUtil.ItemCallback<LeituraObd>() {
        override fun areItemsTheSame(antigo: LeituraObd, novo: LeituraObd) = antigo.id == novo.id
        override fun areContentsTheSame(antigo: LeituraObd, novo: LeituraObd) = antigo == novo
    }
}
