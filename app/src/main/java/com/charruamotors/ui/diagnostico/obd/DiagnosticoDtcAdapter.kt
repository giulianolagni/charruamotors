package com.charruamotors.ui.diagnostico.obd

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.charruamotors.R
import com.charruamotors.databinding.ItemCodigoFalhaBinding
import com.charruamotors.databinding.ItemDtcCabecalhoBinding
import com.charruamotors.databinding.ItemDtcVazioBinding
import com.charruamotors.obd.dtc.TipoDtc

/** Falhas separadas por categoria (confirmadas, pendentes, permanentes), com badge por tipo. */
class DiagnosticoDtcAdapter : ListAdapter<ItemDiagnostico, RecyclerView.ViewHolder>(Diff) {

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is ItemDiagnostico.Cabecalho -> TIPO_CABECALHO
        is ItemDiagnostico.Codigo -> TIPO_CODIGO
        is ItemDiagnostico.Vazio -> TIPO_VAZIO
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TIPO_CABECALHO -> CabecalhoHolder(ItemDtcCabecalhoBinding.inflate(inflater, parent, false))
            TIPO_CODIGO -> CodigoHolder(ItemCodigoFalhaBinding.inflate(inflater, parent, false))
            else -> VazioHolder(ItemDtcVazioBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ItemDiagnostico.Cabecalho -> (holder as CabecalhoHolder).bind(item)
            is ItemDiagnostico.Codigo -> (holder as CodigoHolder).bind(item)
            is ItemDiagnostico.Vazio -> (holder as VazioHolder).bind(item)
        }
    }

    class CabecalhoHolder(private val binding: ItemDtcCabecalhoBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ItemDiagnostico.Cabecalho) {
            val contexto = binding.root.context
            val nome = contexto.getString(item.tipo.titulo)
            binding.textoCategoria.text = item.quantidade?.let { contexto.getString(R.string.dtc_categoria_qtd, nome, it) } ?: nome
            binding.marcador.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(contexto, item.tipo.cor))
        }
    }

    class CodigoHolder(private val binding: ItemCodigoFalhaBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ItemDiagnostico.Codigo) {
            val contexto = binding.root.context
            val dtc = item.dtc
            val cor = ContextCompat.getColor(contexto, dtc.tipo.cor)
            binding.textoCodigo.text = dtc.codigo
            binding.textoCodigo.setTextColor(cor)
            binding.badgeTipo.setText(dtc.tipo.badge)
            binding.badgeTipo.backgroundTintList = ColorStateList.valueOf(cor)
            binding.textoTitulo.text = dtc.info.titulo
            binding.textoDescricao.text = dtc.info.descricao?.takeIf { it.isNotBlank() }
                ?: contexto.getString(R.string.dtc_sem_descricao)
            binding.textoFabricante.isVisible = dtc.info.fabricante != null
            binding.textoFabricante.text = dtc.info.fabricante?.let { contexto.getString(R.string.dtc_fabricante, it) }
        }
    }

    class VazioHolder(private val binding: ItemDtcVazioBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ItemDiagnostico.Vazio) {
            binding.textoVazio.setText(
                if (item.suportado) R.string.dtc_categoria_vazia else R.string.dtc_categoria_nao_suportada,
            )
        }
    }

    private object Diff : DiffUtil.ItemCallback<ItemDiagnostico>() {
        override fun areItemsTheSame(antigo: ItemDiagnostico, novo: ItemDiagnostico) = antigo.chave == novo.chave
        override fun areContentsTheSame(antigo: ItemDiagnostico, novo: ItemDiagnostico) = antigo == novo
    }

    private companion object {
        const val TIPO_CABECALHO = 0
        const val TIPO_CODIGO = 1
        const val TIPO_VAZIO = 2
    }
}

@get:StringRes
private val TipoDtc.titulo: Int
    get() = when (this) {
        TipoDtc.CONFIRMADO -> R.string.dtc_tipo_confirmado
        TipoDtc.PENDENTE -> R.string.dtc_tipo_pendente
        TipoDtc.PERMANENTE -> R.string.dtc_tipo_permanente
        TipoDtc.CAMBIO -> R.string.dtc_tipo_cambio
    }

@get:StringRes
private val TipoDtc.badge: Int
    get() = when (this) {
        TipoDtc.CONFIRMADO -> R.string.dtc_badge_confirmado
        TipoDtc.PENDENTE -> R.string.dtc_badge_pendente
        TipoDtc.PERMANENTE -> R.string.dtc_badge_permanente
        TipoDtc.CAMBIO -> R.string.dtc_badge_cambio
    }

@get:ColorRes
private val TipoDtc.cor: Int
    get() = when (this) {
        TipoDtc.CONFIRMADO -> R.color.dtc_confirmado
        TipoDtc.PENDENTE -> R.color.dtc_pendente
        TipoDtc.PERMANENTE -> R.color.dtc_permanente
        TipoDtc.CAMBIO -> R.color.dtc_cambio
    }
