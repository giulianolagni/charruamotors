package com.charruamotors.ui.painel

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.R
import com.charruamotors.databinding.ActivityPersonalizarPainelBinding
import com.charruamotors.databinding.ItemSecaoPainelBinding
import com.charruamotors.databinding.ItemSeletorMostradorBinding
import com.charruamotors.direcao.painel.ConfigPainel
import com.charruamotors.direcao.painel.EstiloMostrador
import com.charruamotors.direcao.painel.SecaoPainel
import com.charruamotors.direcao.painel.SensorPainel
import com.charruamotors.sistema.unidades.ConversorUnidades
import com.charruamotors.ui.componentes.GaugeView
import com.charruamotors.ui.util.aplicarInsetsDoSistema
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * Personalizar Painel (engrenagem no topo da aba Painel): arrastar e soltar a ordem de Músicas,
 * Navegação e Marcadores; estilo dos mostradores (Esportivo, Digital Moderno ou Fita/Off-road)
 * com prévia; os 4 sensores dos relógios; a lista rápida; e o HUD flutuante. Cada escolha é
 * gravada na hora nas [com.charruamotors.direcao.painel.PreferenciasPainel] e a aba Painel (e o
 * HUD, se estiver aberto) se redesenha sozinha.
 */
class PersonalizarPainelActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPersonalizarPainelBinding
    private val app get() = application as CharruaMotorsApp
    private val prefs get() = app.preferenciasPainel
    private val adapterSecoes = SecoesAdapter()
    private val seletores = mutableListOf<ItemSeletorMostradorBinding>()
    private val chipsLista = mutableMapOf<String, Chip>()
    private lateinit var editorHud: EditorConfigHud
    private lateinit var toque: ItemTouchHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        binding = ActivityPersonalizarPainelBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.appbar.aplicarInsetsDoSistema(base = false)
        binding.conteudo.aplicarInsetsDoSistema(topo = false)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        configurarOrdem()
        configurarEstilo()
        configurarMostradores()
        configurarListaRapida()
        editorHud = EditorConfigHud(binding.configHud, prefs.config.value.hud) { hud -> prefs.definirHud(hud) }
        binding.botaoRestaurarPadrao.setOnClickListener { confirmarRestauracao() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { prefs.config.collect(::mostrar) }
                launch { app.preferenciasApp.config.collect { mostrarPrevia(prefs.config.value.estilo, it.conversor) } }
            }
        }
    }

    // ---------------------------------------------------------------- ordem (arrastar e soltar)

    private fun configurarOrdem() {
        binding.listaSecoes.adapter = adapterSecoes
        toque = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(rv: RecyclerView, origem: RecyclerView.ViewHolder, destino: RecyclerView.ViewHolder): Boolean {
                adapterSecoes.mover(origem.bindingAdapterPosition, destino.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direcao: Int) = Unit

            // Arraste só pela alça (o toque longo no card rolaria a tela sem querer).
            override fun isLongPressDragEnabled() = false

            override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(rv, viewHolder)
                prefs.atualizar { it.copy(ordem = adapterSecoes.itens.toList()) }
                adapterSecoes.renumerar()
            }
        })
        toque.attachToRecyclerView(binding.listaSecoes)
    }

    private inner class SecoesAdapter : RecyclerView.Adapter<SecoesAdapter.Item>() {
        val itens = mutableListOf<SecaoPainel>()

        inner class Item(val b: ItemSecaoPainelBinding) : RecyclerView.ViewHolder(b.root)

        @SuppressLint("NotifyDataSetChanged")
        fun definir(ordem: List<SecaoPainel>) {
            if (ordem == itens) return
            itens.clear()
            itens.addAll(ordem)
            notifyDataSetChanged()
        }

        fun mover(de: Int, para: Int) {
            if (de !in itens.indices || para !in itens.indices) return
            itens.add(para, itens.removeAt(de))
            notifyItemMoved(de, para)
        }

        fun renumerar() = notifyItemRangeChanged(0, itens.size)

        override fun getItemCount() = itens.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Item(ItemSecaoPainelBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: Item, position: Int) {
            val secao = itens[position]
            holder.b.textoPosicao.text = (position + 1).toString()
            holder.b.textoTitulo.text = secao.titulo
            holder.b.textoDescricao.text = secao.descricao
            holder.b.root.contentDescription = getString(R.string.personalizar_secao_descricao, position + 1, secao.titulo)
            holder.b.alcaArrastar.setOnTouchListener { _, evento ->
                if (evento.actionMasked == MotionEvent.ACTION_DOWN) toque.startDrag(holder)
                false
            }
        }
    }

    // ---------------------------------------------------------------- estilo

    private fun configurarEstilo() = with(binding) {
        grupoEstilo.addOnButtonCheckedListener { _, id, marcado ->
            if (!marcado) return@addOnButtonCheckedListener
            val estilo = when (id) {
                R.id.botao_estilo_digital -> EstiloMostrador.DIGITAL
                R.id.botao_estilo_fita -> EstiloMostrador.FITA
                else -> EstiloMostrador.ESPORTIVO
            }
            if (estilo != prefs.config.value.estilo) prefs.atualizar { it.copy(estilo = estilo) }
        }
    }

    /** Prévia com os dois primeiros relógios escolhidos e valores de exemplo (marcha lenta quente). */
    private fun mostrarPrevia(estilo: EstiloMostrador, conversor: ConversorUnidades) {
        val mostradores = prefs.config.value.mostradores
        listOf(binding.previa1, binding.previa2).forEachIndexed { i, gauge ->
            val sensor = mostradores.getOrNull(i) ?: return@forEachIndexed
            configurarGauge(gauge, sensor, estilo, conversor)
            gauge.definirValor(sensor.exibir(VALORES_EXEMPLO[sensor] ?: 0.0, conversor).valor, animar = false)
        }
    }

    // ---------------------------------------------------------------- mostradores

    private fun configurarMostradores() {
        val nomes = SensorPainel.entries.map { it.titulo }
        repeat(ConfigPainel.QUANTIDADE_MOSTRADORES) { posicao ->
            val s = ItemSeletorMostradorBinding.inflate(layoutInflater, binding.grupoMostradores, true)
            s.layoutMostrador.hint = getString(R.string.personalizar_relogio, posicao + 1)
            s.campoMostrador.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, nomes))
            s.campoMostrador.setOnItemClickListener { _, _, i, _ ->
                prefs.atualizar { it.definirMostrador(posicao, SensorPainel.entries[i]) }
            }
            seletores += s
        }
    }

    // ---------------------------------------------------------------- lista rápida

    private fun configurarListaRapida() {
        // Todos os PIDs que o app sabe ler (modo 01 e a tensão do adaptador).
        for (definicao in app.catalogoPids.pids) {
            val chip = Chip(this).apply {
                text = definicao.nome
                isCheckable = true
                setOnClickListener { prefs.atualizar { it.alternarListaRapida(definicao.comando) } }
            }
            chipsLista[definicao.comando.uppercase()] = chip
            binding.chipsListaRapida.addView(chip)
        }
        binding.botaoLimparListaRapida.setOnClickListener { prefs.atualizar { it.copy(listaRapida = emptySet()) } }
    }

    // ---------------------------------------------------------------- estado

    private fun mostrar(config: ConfigPainel) = with(binding) {
        adapterSecoes.definir(config.ordem)
        val estilo = when (config.estilo) {
            EstiloMostrador.ESPORTIVO -> R.id.botao_estilo_esportivo
            EstiloMostrador.DIGITAL -> R.id.botao_estilo_digital
            EstiloMostrador.FITA -> R.id.botao_estilo_fita
        }
        if (grupoEstilo.checkedButtonId != estilo) grupoEstilo.check(estilo)
        textoEstiloDescricao.text = config.estilo.descricao
        mostrarPrevia(config.estilo, app.preferenciasApp.config.value.conversor)
        config.mostradores.forEachIndexed { i, sensor -> seletores.getOrNull(i)?.campoMostrador?.setText(sensor.titulo, false) }
        chipsLista.forEach { (comando, chip) -> chip.isChecked = comando in config.listaRapida }
        textoListaRapida.text = if (config.listaRapida.isEmpty()) {
            getString(R.string.personalizar_lista_vazia)
        } else {
            resources.getQuantityString(R.plurals.personalizar_lista_qtd, config.listaRapida.size, config.listaRapida.size)
        }
        botaoLimparListaRapida.isEnabled = config.listaRapida.isNotEmpty()
        if (editorHud.config != config.hud) editorHud.mostrar(config.hud)
    }

    private fun confirmarRestauracao() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.personalizar_restaurar)
            .setMessage(R.string.personalizar_restaurar_mensagem)
            .setNegativeButton(R.string.botao_cancelar, null)
            .setPositiveButton(R.string.personalizar_restaurar_confirmar) { _, _ -> prefs.restaurarPadrao() }
            .show()
    }

    companion object {
        /** Valores da prévia (unidades do catálogo). */
        private val VALORES_EXEMPLO = mapOf(
            SensorPainel.RPM to 2350.0,
            SensorPainel.VELOCIDADE to 88.0,
            SensorPainel.TURBO to 182.0,
            SensorPainel.TEMP_MOTOR to 92.0,
            SensorPainel.TEMP_ADMISSAO to 38.0,
            SensorPainel.TEMP_OLEO to 101.0,
            SensorPainel.CARGA to 46.0,
            SensorPainel.BORBOLETA to 22.0,
            SensorPainel.ACELERADOR to 18.0,
            SensorPainel.TENSAO to 14.1,
            SensorPainel.RAIL to 95_000.0,
            SensorPainel.MAF to 32.0,
            SensorPainel.COMBUSTIVEL to 64.0,
            SensorPainel.CONSUMO to 4.2,
        )

        /** Título, unidade, escala, casas e estilo do [sensor] num mostrador (também usado pela aba Painel). */
        fun configurarGauge(gauge: GaugeView, sensor: SensorPainel, estilo: EstiloMostrador, conversor: ConversorUnidades) {
            gauge.titulo = sensor.titulo
            gauge.unidade = sensor.unidade(conversor)
            gauge.escala = sensor.escala(conversor)
            gauge.casasDecimais = sensor.exibir(0.0, conversor).casas
            gauge.estilo = estilo
        }

        fun intent(context: Context): Intent = Intent(context, PersonalizarPainelActivity::class.java)
    }
}
