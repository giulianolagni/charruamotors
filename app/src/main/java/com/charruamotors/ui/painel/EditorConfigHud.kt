package com.charruamotors.ui.painel

import android.content.Context
import android.widget.TextView
import androidx.core.view.isVisible
import com.charruamotors.R
import com.charruamotors.databinding.ConteudoConfigHudBinding
import com.charruamotors.direcao.painel.ConfigHud
import com.charruamotors.direcao.painel.EstiloHud
import com.charruamotors.direcao.painel.HistoricoSensores
import com.charruamotors.direcao.painel.PresetHud
import com.charruamotors.direcao.painel.SensorPainel
import com.charruamotors.direcao.painel.TamanhoHud
import com.charruamotors.ui.componentes.ArrasteReordenavel
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/**
 * Liga os controles de [ConteudoConfigHudBinding] (visual Padrão/Esportivo, tamanho, sensores fixados,
 * a ordem deles por arraste e o estilo) a uma [ConfigHud]: usado no diálogo de ativação do HUD e na tela Personalizar
 * Painel. Cada mudança chama [aoMudar] com a nova configuração (quem chama decide se salva na hora
 * ou ao confirmar).
 */
class EditorConfigHud(
    private val binding: ConteudoConfigHudBinding,
    inicial: ConfigHud,
    private val aoMudar: (ConfigHud) -> Unit = {},
) {
    var config: ConfigHud = inicial
        private set

    private val chips = mutableMapOf<SensorPainel, Chip>()
    private val adapterOrdem = ItensOrdenaveisAdapter<SensorPainel>(
        titulo = { it.titulo },
        aoRemover = { sensor -> atualizar(config.alternarSensor(sensor)) },
    )

    init {
        val contexto = binding.root.context
        montarChips(contexto)
        binding.listaOrdemHud.adapter = adapterOrdem
        adapterOrdem.arraste = ArrasteReordenavel(
            binding.listaOrdemHud,
            moverVisual = adapterOrdem::moverVisual,
            aoSoltar = { de, para -> atualizar(config.moverSensor(de, para)) },
        )
        binding.grupoPresetHud.addOnButtonCheckedListener { _, id, marcado ->
            if (!marcado) return@addOnButtonCheckedListener
            val preset = if (id == R.id.botao_hud_preset_esportivo) PresetHud.ESPORTIVO else PresetHud.PADRAO
            if (preset != config.preset) atualizar(config.copy(preset = preset))
        }
        binding.grupoTamanhoHud.addOnButtonCheckedListener { _, id, marcado ->
            if (!marcado) return@addOnButtonCheckedListener
            val tamanho = if (id == R.id.botao_hud_minimizado) TamanhoHud.MINIMIZADO else TamanhoHud.EXPANDIDO
            if (tamanho != config.tamanho) atualizar(config.copy(tamanho = tamanho))
        }
        binding.grupoEstiloHud.addOnButtonCheckedListener { _, id, marcado ->
            if (!marcado) return@addOnButtonCheckedListener
            val estilo = when (id) {
                R.id.botao_hud_grandes -> EstiloHud.NUMEROS_GRANDES
                R.id.botao_hud_barras -> EstiloHud.BARRAS
                else -> EstiloHud.NUMEROS
            }
            if (estilo != config.estilo) atualizar(config.copy(estilo = estilo))
        }
        mostrar(config)
    }

    /**
     * Chips por seção (Principal, Temperaturas...). Os sensores que a ECU atual nunca respondeu
     * ([HistoricoSensores]) ficam numa seção recolhida no fim, para não poluir a lista.
     */
    private fun montarChips(contexto: Context) {
        val naoSuportados = HistoricoSensores.naoSuportados()
        for ((titulo, sensores) in SensorPainel.agrupados(naoSuportados)) {
            val fora = titulo == SensorPainel.TITULO_NAO_SUPORTADOS
            val grupo = ChipGroup(contexto)
            val cabecalho = TextView(contexto, null, com.google.android.material.R.attr.textAppearanceLabelMedium).apply {
                text = if (fora) "▸ $titulo (${sensores.size})" else titulo
                setPadding(0, 12, 0, 4)
                if (fora) alpha = 0.6f
            }
            if (fora) {
                grupo.isVisible = false
                cabecalho.setOnClickListener {
                    grupo.isVisible = !grupo.isVisible
                    cabecalho.text = (if (grupo.isVisible) "▾ " else "▸ ") + "$titulo (${sensores.size})"
                }
            }
            for (sensor in sensores) {
                val chip = Chip(contexto).apply {
                    text = sensor.titulo
                    isCheckable = true
                    setOnClickListener { alternar(sensor) }
                }
                chips[sensor] = chip
                grupo.addView(chip)
            }
            binding.chipsSensoresHud.addView(cabecalho)
            binding.chipsSensoresHud.addView(grupo)
        }
    }

    /** Recarrega os controles (ex.: "Restaurar padrão"). */
    fun mostrar(nova: ConfigHud) {
        config = nova
        val preset = if (nova.preset == PresetHud.ESPORTIVO) R.id.botao_hud_preset_esportivo else R.id.botao_hud_preset_padrao
        if (binding.grupoPresetHud.checkedButtonId != preset) binding.grupoPresetHud.check(preset)
        // O Esportivo tem desenho fixo: tamanho, sensores e estilo só valem no Padrão.
        binding.textoPresetHud.isVisible = nova.preset == PresetHud.ESPORTIVO
        binding.opcoesPadraoHud.isVisible = nova.preset == PresetHud.PADRAO
        val tamanho = if (nova.tamanho == TamanhoHud.MINIMIZADO) R.id.botao_hud_minimizado else R.id.botao_hud_expandido
        if (binding.grupoTamanhoHud.checkedButtonId != tamanho) binding.grupoTamanhoHud.check(tamanho)
        val estilo = when (nova.estilo) {
            EstiloHud.NUMEROS -> R.id.botao_hud_numeros
            EstiloHud.NUMEROS_GRANDES -> R.id.botao_hud_grandes
            EstiloHud.BARRAS -> R.id.botao_hud_barras
        }
        if (binding.grupoEstiloHud.checkedButtonId != estilo) binding.grupoEstiloHud.check(estilo)
        val contexto = binding.root.context
        binding.textoTamanhoHud.text = if (nova.tamanho == TamanhoHud.MINIMIZADO) {
            contexto.getString(R.string.hud_config_minimizado_dica)
        } else {
            contexto.getString(R.string.hud_config_expandido_dica, nova.sensores.size, ConfigHud.MAX_SENSORES)
        }
        // No minimizado os sensores são fixos (RPM e Turbo): a escolha e a ordem somem.
        val expandido = nova.tamanho == TamanhoHud.EXPANDIDO
        binding.tituloSensoresHud.isVisible = expandido
        binding.chipsSensoresHud.isVisible = expandido
        binding.tituloOrdemHud.isVisible = expandido && nova.sensores.size > 1
        binding.listaOrdemHud.isVisible = expandido
        adapterOrdem.definir(nova.sensores)
        val cheio = nova.sensores.size >= ConfigHud.MAX_SENSORES
        chips.forEach { (sensor, chip) ->
            chip.isChecked = sensor in nova.sensores
            chip.isEnabled = chip.isChecked || !cheio
        }
    }

    private fun alternar(sensor: SensorPainel) = atualizar(config.alternarSensor(sensor))

    private fun atualizar(nova: ConfigHud) {
        mostrar(nova)
        aoMudar(nova)
    }
}
