package com.charruamotors.ui.painel

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.charruamotors.R
import com.charruamotors.databinding.ItemMostradorEditorBinding
import com.charruamotors.databinding.ItemOrdemSimplesBinding
import com.charruamotors.databinding.ItemSecaoPainelBinding
import com.charruamotors.direcao.painel.EstiloMostrador
import com.charruamotors.direcao.painel.HistoricoSensores
import com.charruamotors.direcao.painel.Mostrador
import com.charruamotors.direcao.painel.SecaoPainel
import com.charruamotors.direcao.painel.SensorPainel
import com.charruamotors.ui.componentes.ArrasteReordenavel

/**
 * Base das listas arrastáveis do Personalizar Painel: guarda os itens exibidos, move durante o
 * arraste (só o visual; a configuração muda ao soltar) e recarrega quando a configuração muda.
 */
abstract class ListaArrastavelAdapter<T, VH : RecyclerView.ViewHolder> : RecyclerView.Adapter<VH>() {
    protected val itens = mutableListOf<T>()

    /** Ligado depois de criar o [ArrasteReordenavel] da lista. */
    var arraste: ArrasteReordenavel? = null

    @SuppressLint("NotifyDataSetChanged")
    fun definir(novos: List<T>) {
        if (novos == itens) {
            // Mesma lista (ex.: logo após soltar um arraste): só renumera as posições.
            notifyItemRangeChanged(0, itens.size)
            return
        }
        itens.clear()
        itens.addAll(novos)
        notifyDataSetChanged()
    }

    fun moverVisual(de: Int, para: Int) {
        if (de !in itens.indices || para !in itens.indices) return
        itens.add(para, itens.removeAt(de))
        notifyItemMoved(de, para)
    }

    override fun getItemCount() = itens.size
}

/** Blocos visíveis do Painel: posição, título, descrição, olho cortado (remover) e alça. */
class SecoesAdapter(private val aoOcultar: (SecaoPainel) -> Unit) :
    ListaArrastavelAdapter<SecaoPainel, SecoesAdapter.Item>() {

    class Item(val b: ItemSecaoPainelBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Item {
        val item = Item(ItemSecaoPainelBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        item.b.botaoOcultar.setOnClickListener {
            itens.getOrNull(item.bindingAdapterPosition)?.let(aoOcultar)
        }
        return item
    }

    override fun onBindViewHolder(holder: Item, position: Int) {
        val secao = itens[position]
        val contexto = holder.b.root.context
        holder.b.textoPosicao.text = (position + 1).toString()
        holder.b.textoTitulo.text = secao.titulo
        holder.b.textoDescricao.text = secao.descricao
        holder.b.root.contentDescription = contexto.getString(R.string.personalizar_secao_descricao, position + 1, secao.titulo)
        holder.b.botaoOcultar.contentDescription = contexto.getString(R.string.personalizar_ocultar_bloco, secao.titulo)
        arraste?.ligarAlca(holder.b.alcaArrastar, holder)
    }
}

/** Itens simples (sensores do HUD, PIDs da lista rápida): alça, posição, nome e remover. */
class ItensOrdenaveisAdapter<T>(
    private val titulo: (T) -> String,
    private val aoRemover: (T) -> Unit,
) : ListaArrastavelAdapter<T, ItensOrdenaveisAdapter.Item>() {

    class Item(val b: ItemOrdemSimplesBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Item {
        val item = Item(ItemOrdemSimplesBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        item.b.botaoRemover.setOnClickListener { itens.getOrNull(item.bindingAdapterPosition)?.let(aoRemover) }
        return item
    }

    override fun onBindViewHolder(holder: Item, position: Int) {
        val nome = titulo(itens[position])
        holder.b.textoPosicao.text = (position + 1).toString()
        holder.b.textoTitulo.text = nome
        holder.b.botaoRemover.contentDescription = holder.b.root.context.getString(R.string.personalizar_remover_nome, nome)
        arraste?.ligarAlca(holder.b.alcaArrastar, holder)
    }
}

/**
 * Lista dinâmica de mostradores: alça, seletor do sensor, estilo individual e remover. O remover
 * some quando só resta um relógio.
 */
class MostradoresAdapter(
    private val aoEscolherSensor: (posicao: Int, SensorPainel) -> Unit,
    private val aoEscolherEstilo: (posicao: Int, EstiloMostrador) -> Unit,
    private val aoRemover: (posicao: Int) -> Unit,
) : ListaArrastavelAdapter<Mostrador, MostradoresAdapter.Item>() {

    class Item(val b: ItemMostradorEditorBinding) : RecyclerView.ViewHolder(b.root) {
        /** Evita tratar como escolha do usuário o `check()` feito no bind. */
        var vinculando = false
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Item {
        val item = Item(ItemMostradorEditorBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        val contexto = parent.context
        val adaptadorSensores = SensoresAgrupadosAdapter(contexto, SensorPainel.agrupados(HistoricoSensores.naoSuportados()))
        item.b.campoMostrador.setAdapter(adaptadorSensores)
        item.b.campoMostrador.setOnItemClickListener { _, _, i, _ ->
            val posicao = item.bindingAdapterPosition
            val sensor = (adaptadorSensores.getItem(i) as? LinhaSensor.Item)?.sensor
            if (posicao != RecyclerView.NO_POSITION && sensor != null) aoEscolherSensor(posicao, sensor)
        }
        item.b.grupoEstiloMostrador.addOnButtonCheckedListener { _, id, marcado ->
            val posicao = item.bindingAdapterPosition
            if (!marcado || item.vinculando || posicao == RecyclerView.NO_POSITION) return@addOnButtonCheckedListener
            aoEscolherEstilo(posicao, estiloDoBotao(id))
        }
        item.b.botaoRemover.setOnClickListener {
            val posicao = item.bindingAdapterPosition
            if (posicao != RecyclerView.NO_POSITION) aoRemover(posicao)
        }
        return item
    }

    override fun onBindViewHolder(holder: Item, position: Int) {
        val m = itens[position]
        val contexto = holder.b.root.context
        holder.vinculando = true
        holder.b.layoutMostrador.hint = contexto.getString(R.string.personalizar_relogio, position + 1)
        // filter = false: mantém a lista completa no dropdown com um sensor já escolhido.
        holder.b.campoMostrador.setText(m.sensor.titulo, false)
        holder.b.grupoEstiloMostrador.check(botaoDoEstilo(m.estilo))
        holder.b.botaoRemover.isEnabled = itens.size > 1
        holder.b.botaoRemover.alpha = if (itens.size > 1) 1f else ALFA_DESABILITADO
        holder.b.botaoRemover.contentDescription = contexto.getString(R.string.personalizar_remover_nome, m.sensor.titulo)
        holder.vinculando = false
        arraste?.ligarAlca(holder.b.alcaArrastar, holder)
    }

    companion object {
        private const val ALFA_DESABILITADO = 0.35f

        fun estiloDoBotao(id: Int): EstiloMostrador = when (id) {
            R.id.botao_estilo_digital -> EstiloMostrador.DIGITAL
            R.id.botao_estilo_fita -> EstiloMostrador.FITA
            else -> EstiloMostrador.ESPORTIVO
        }

        fun botaoDoEstilo(estilo: EstiloMostrador): Int = when (estilo) {
            EstiloMostrador.ESPORTIVO -> R.id.botao_estilo_esportivo
            EstiloMostrador.DIGITAL -> R.id.botao_estilo_digital
            EstiloMostrador.FITA -> R.id.botao_estilo_fita
        }
    }
}

/** Linha do seletor de sensores: cabeçalho de categoria (não selecionável) ou um sensor. */
sealed interface LinhaSensor {
    data class Cabecalho(val titulo: String) : LinhaSensor {
        override fun toString() = titulo
    }

    /** [indisponivel]: da seção "Não suportados neste veículo" (aparece esmaecido, mas continua escolhível). */
    data class Item(val sensor: SensorPainel, val indisponivel: Boolean) : LinhaSensor {
        override fun toString() = sensor.titulo
    }

    companion object {
        /** Cabeçalhos (mesmas categorias do HUD) e sensores, com os não suportados recolhidos no fim. */
        fun montar(grupos: List<Pair<String, List<SensorPainel>>>): List<LinhaSensor> = buildList {
            for ((titulo, sensores) in grupos) {
                val fora = titulo == SensorPainel.TITULO_NAO_SUPORTADOS
                add(Cabecalho(if (fora) "$titulo (${sensores.size})" else titulo))
                sensores.forEach { add(Item(it, fora)) }
            }
        }
    }
}

/** Dropdown do mostrador agrupado por categoria, no formato do HUD. */
class SensoresAgrupadosAdapter(contexto: Context, grupos: List<Pair<String, List<SensorPainel>>>) :
    ArrayAdapter<LinhaSensor>(contexto, android.R.layout.simple_list_item_1, LinhaSensor.montar(grupos)) {

    override fun isEnabled(position: Int) = getItem(position) is LinhaSensor.Item

    override fun areAllItemsEnabled() = false

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val vista = super.getView(position, convertView, parent) as TextView
        when (val linha = getItem(position)) {
            is LinhaSensor.Cabecalho -> {
                vista.setTypeface(null, Typeface.BOLD)
                vista.textSize = TAMANHO_CABECALHO
                vista.alpha = 0.7f
            }
            is LinhaSensor.Item -> {
                vista.setTypeface(null, Typeface.NORMAL)
                vista.textSize = TAMANHO_ITEM
                vista.alpha = if (linha.indisponivel) 0.5f else 1f
            }
            null -> Unit
        }
        return vista
    }

    private companion object {
        const val TAMANHO_CABECALHO = 13f
        const val TAMANHO_ITEM = 16f
    }
}
