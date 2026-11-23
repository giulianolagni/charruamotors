package com.charruamotors.ui.painel

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.R
import com.charruamotors.databinding.ActivityPersonalizarPainelBinding
import com.charruamotors.direcao.painel.BotaoNavegacao
import com.charruamotors.direcao.painel.ConfigPainel
import com.charruamotors.direcao.painel.EstiloMostrador
import com.charruamotors.direcao.painel.Mostrador
import com.charruamotors.direcao.painel.SecaoPainel
import com.charruamotors.direcao.painel.SensorPainel
import com.charruamotors.sistema.unidades.ConversorUnidades
import com.charruamotors.ui.componentes.ArrasteReordenavel
import com.charruamotors.ui.util.aplicarInsetsDoSistema
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * Personalizar Painel 2.0 (ícone na Toolbar da aba Painel):
 * 1. **Blocos do Painel**: todos arrastáveis (`≡`), cada um com o botão de remover; os removidos
 *    ficam em "Blocos removidos" para voltar com 1 toque; e os atalhos da Navegação (Waze, Maps,
 *    Spotify, Posto, Oficina e HUD), cada um com visibilidade e ordem próprias;
 * 2. **Mostradores**: lista dinâmica sem limite de 4 (um por sensor), cada um com alça, sensor,
 *    estilo próprio (Relógio Esportivo, Digital Grande ou Barra/Fita) e remover, mais "+ Adicionar mostrador" e "Aplicar estilo a todos";
 * 3. **Lista rápida**: os PIDs escolhidos, na ordem arrastada;
 * 4. **HUD flutuante**: tamanho, sensores (na ordem arrastada) e estilo.
 *
 * Cada escolha é gravada na hora nas [com.charruamotors.direcao.painel.PreferenciasPainel] e a aba
 * Painel (e o HUD, se estiver aberto) se redesenha sozinha.
 */
class PersonalizarPainelActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPersonalizarPainelBinding
    private val app get() = application as CharruaMotorsApp
    private val prefs get() = app.preferenciasPainel
    private val chipsLista = mutableMapOf<String, Chip>()
    private val chipsNavegacao = mutableMapOf<BotaoNavegacao, Chip>()
    /** PIDs que a lista rápida oferece: o catálogo padrão e os perfis de montadora (Toyota SW4, VW...). */
    private val pidsDisponiveis by lazy { app.catalogoPids.pids + app.catalogoEstendido.definicoes }
    private val nomesPids: Map<String, String> by lazy { pidsDisponiveis.associate { it.comando.uppercase() to rotuloDoPid(it.comando, it.nome) } }

    /** Mesmo rótulo claro, com unidade, que o mostrador usa (`SensorPainel.titulo`); sem sensor equivalente, o nome do catálogo. */
    private fun rotuloDoPid(comando: String, nomeCatalogo: String): String =
        SensorPainel.entries.firstOrNull { it.comando.equals(comando, ignoreCase = true) }?.titulo ?: nomeCatalogo
    private lateinit var editorHud: EditorConfigHud

    private val adapterSecoes = SecoesAdapter(aoOcultar = { secao -> prefs.atualizar { it.ocultar(secao) } })
    private val adapterMostradores = MostradoresAdapter(
        aoEscolherSensor = { i, sensor -> prefs.atualizar { it.definirMostrador(i, sensor) } },
        aoEscolherEstilo = { i, estilo -> prefs.atualizar { it.definirEstilo(i, estilo) } },
        aoRemover = { i -> prefs.atualizar { it.removerMostrador(i) } },
    )
    private val adapterAtalhos = ItensOrdenaveisAdapter<BotaoNavegacao>(
        titulo = { it.titulo },
        aoRemover = { botao -> prefs.atualizar { it.alternarBotaoNavegacao(botao) } },
    )
    private val adapterListaRapida = ItensOrdenaveisAdapter<String>(
        titulo = { comando -> nomesPids[comando] ?: comando },
        aoRemover = { comando -> prefs.atualizar { it.alternarListaRapida(comando) } },
    )

    /** Última prévia desenhada (sensores, estilos e unidades), para não recriar à toa. */
    private var assinaturaPrevia: Pair<List<Mostrador>, ConversorUnidades>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        binding = ActivityPersonalizarPainelBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.appbar.aplicarInsetsDoSistema(base = false)
        binding.conteudo.aplicarInsetsDoSistema(topo = false)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        configurarBlocos()
        configurarMostradores()
        configurarListaRapida()
        editorHud = EditorConfigHud(binding.configHud, prefs.config.value.hud) { hud -> prefs.definirHud(hud) }
        binding.botaoRestaurarPadrao.setOnClickListener { confirmarRestauracao() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { prefs.config.collect(::mostrar) }
                launch { app.preferenciasApp.config.collect { mostrarPrevia(prefs.config.value.mostradores, it.conversor) } }
            }
        }
    }

    // ---------------------------------------------------------------- 1. blocos

    private fun configurarBlocos() {
        binding.listaSecoes.adapter = adapterSecoes
        adapterSecoes.arraste = ArrasteReordenavel(
            binding.listaSecoes,
            moverVisual = adapterSecoes::moverVisual,
            aoSoltar = { de, para -> prefs.atualizar { it.mover(de, para) } },
        )
        binding.listaOrdemAtalhos.adapter = adapterAtalhos
        adapterAtalhos.arraste = ArrasteReordenavel(
            binding.listaOrdemAtalhos,
            moverVisual = adapterAtalhos::moverVisual,
            aoSoltar = { de, para -> prefs.atualizar { it.moverBotao(de, para) } },
        )
        for (botao in BotaoNavegacao.entries) {
            val chip = Chip(this).apply {
                text = botao.titulo
                isCheckable = true
                setOnClickListener { prefs.atualizar { it.alternarBotaoNavegacao(botao) } }
            }
            chipsNavegacao[botao] = chip
            binding.chipsBotoesNavegacao.addView(chip)
        }
    }

    private fun mostrarBlocos(config: ConfigPainel) = with(binding) {
        adapterSecoes.definir(config.secoesVisiveis)
        textoTodosRemovidos.isVisible = config.secoesVisiveis.isEmpty()
        val removidas = config.secoesRemovidas
        grupoRemovidos.isVisible = removidas.isNotEmpty()
        chipsRemovidos.removeAllViews()
        for (secao in removidas) {
            chipsRemovidos.addView(Chip(this@PersonalizarPainelActivity).apply {
                text = getString(R.string.personalizar_adicionar_de_volta, secao.titulo)
                contentDescription = getString(R.string.personalizar_adicionar_de_volta_descricao, secao.titulo)
                setChipIconResource(R.drawable.ic_adicionar)
                setOnClickListener { prefs.atualizar { it.restaurar(secao) } }
            })
        }
        grupoBotoesNavegacao.isVisible = config.visivel(SecaoPainel.NAVEGACAO)
        chipsNavegacao.forEach { (botao, chip) -> chip.isChecked = config.botaoVisivel(botao) }
        adapterAtalhos.definir(config.botoesVisiveis)
        listaOrdemAtalhos.isVisible = config.botoesVisiveis.isNotEmpty()
    }

    // ---------------------------------------------------------------- 2. mostradores

    private fun configurarMostradores() = with(binding) {
        listaMostradores.adapter = adapterMostradores
        adapterMostradores.arraste = ArrasteReordenavel(
            listaMostradores,
            moverVisual = adapterMostradores::moverVisual,
            aoSoltar = { de, para -> prefs.atualizar { it.moverMostrador(de, para) } },
        )
        botaoAdicionarMostrador.setOnClickListener { prefs.atualizar { it.adicionarMostrador() } }
        botaoEstiloTodos.setOnClickListener { escolherEstiloParaTodos() }
        botaoLayoutPadrao.setOnClickListener { prefs.atualizar { it.aplicarLayout(ConfigPainel.LAYOUT_PADRAO) } }
        botaoLayoutTurboFlex.setOnClickListener { prefs.atualizar { it.aplicarLayout(ConfigPainel.LAYOUT_TURBO_FLEX) } }
    }

    /** "Aplicar estilo a todos": padroniza os relógios num estilo só. */
    private fun escolherEstiloParaTodos() {
        val estilos = EstiloMostrador.entries
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.personalizar_estilo_todos)
            .setItems(estilos.map { "${it.titulo} — ${it.descricao}" }.toTypedArray()) { _, i ->
                prefs.atualizar { it.aplicarEstiloATodos(estilos[i]) }
            }
            .setNegativeButton(R.string.botao_cancelar, null)
            .show()
    }

    private fun mostrarMostradores(config: ConfigPainel) = with(binding) {
        adapterMostradores.definir(config.mostradores)
        tituloMostradores.text = getString(R.string.personalizar_mostradores_qtd, config.mostradores.size, ConfigPainel.MAX_MOSTRADORES)
        botaoAdicionarMostrador.isEnabled = config.podeAdicionarMostrador
        mostrarPrevia(config.mostradores, app.preferenciasApp.config.value.conversor)
    }

    /** Prévia com os dois primeiros relógios, cada um no próprio estilo, com valores de exemplo. */
    private fun mostrarPrevia(mostradores: List<Mostrador>, conversor: ConversorUnidades) {
        val primeiros = mostradores.take(QUANTIDADE_PREVIA)
        if (assinaturaPrevia == primeiros to conversor) return
        assinaturaPrevia = primeiros to conversor
        val gauges = MontadorMostradores.montar(binding.grupoPrevia, primeiros, conversor)
        gauges.forEachIndexed { i, gauge ->
            val sensor = primeiros[i].sensor
            gauge.definirValor(sensor.exibir(VALORES_EXEMPLO[sensor] ?: 0.0, conversor).valor, animar = false)
        }
    }

    // ---------------------------------------------------------------- 3. lista rápida

    private fun configurarListaRapida() {
        // Todos os PIDs que o app sabe ler: modo 01, a tensão do adaptador e os de montadora
        // (pedal, marcha/alavanca, ATF, 4x4 e VSC da SW4), que só aparecem se o módulo responder.
        for (definicao in pidsDisponiveis) {
            val chip = Chip(this).apply {
                text = nomesPids[definicao.comando.uppercase()] ?: definicao.nome
                isCheckable = true
                setOnClickListener { prefs.atualizar { it.alternarListaRapida(definicao.comando) } }
            }
            chipsLista[definicao.comando.uppercase()] = chip
            binding.chipsListaRapida.addView(chip)
        }
        binding.listaOrdemRapida.adapter = adapterListaRapida
        adapterListaRapida.arraste = ArrasteReordenavel(
            binding.listaOrdemRapida,
            moverVisual = adapterListaRapida::moverVisual,
            aoSoltar = { de, para -> prefs.atualizar { it.moverListaRapida(de, para) } },
        )
        binding.botaoLimparListaRapida.setOnClickListener { prefs.atualizar { it.copy(listaRapida = emptyList()) } }
    }

    private fun mostrarListaRapida(config: ConfigPainel) = with(binding) {
        chipsLista.forEach { (comando, chip) -> chip.isChecked = comando in config.listaRapida }
        adapterListaRapida.definir(config.listaRapida)
        listaOrdemRapida.isVisible = config.listaRapida.isNotEmpty()
        textoListaRapida.text = if (config.listaRapida.isEmpty()) {
            getString(R.string.personalizar_lista_vazia)
        } else {
            resources.getQuantityString(R.plurals.personalizar_lista_qtd, config.listaRapida.size, config.listaRapida.size)
        }
        botaoLimparListaRapida.isEnabled = config.listaRapida.isNotEmpty()
    }

    // ---------------------------------------------------------------- estado

    private fun mostrar(config: ConfigPainel) {
        mostrarBlocos(config)
        mostrarMostradores(config)
        mostrarListaRapida(config)
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
        private const val QUANTIDADE_PREVIA = 2

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
            SensorPainel.CONSUMO_KML to 9.8,
            SensorPainel.TEMP_CAMBIO to 78.0,
            SensorPainel.MARCHA_ALAVANCA to 3.0,
            SensorPainel.MARCHA_ATUAL to 4.0,
            SensorPainel.RELACAO_AF to 14.7,
            SensorPainel.ETANOL to 27.0,
            SensorPainel.SONDA_LAMBDA to 1.0,
            SensorPainel.TRACAO_4X4 to 0.0,
            SensorPainel.VSC to 0.0,
        )

        fun intent(context: Context): Intent = Intent(context, PersonalizarPainelActivity::class.java)
    }
}
