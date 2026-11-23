package com.charruamotors.ui.painel

import androidx.core.view.isVisible
import com.charruamotors.R
import com.charruamotors.databinding.ConteudoConfigHudBinding
import com.charruamotors.direcao.painel.ConfigHud
import com.charruamotors.direcao.painel.EstiloHud
import com.charruamotors.direcao.painel.SensorPainel
import com.charruamotors.direcao.painel.TamanhoHud
import com.google.android.material.chip.Chip

/**
 * Liga os controles de [ConteudoConfigHudBinding] (tamanho, sensores fixados e estilo) a uma
 * [ConfigHud]: usado no diálogo de ativação do HUD e na tela Personalizar Painel. Cada mudança
 * chama [aoMudar] com a nova configuração (quem chama decide se salva na hora ou ao confirmar).
 */
class EditorConfigHud(
    private val binding: ConteudoConfigHudBinding,
    inicial: ConfigHud,
    private val aoMudar: (ConfigHud) -> Unit = {},
) {
    var config: ConfigHud = inicial
        private set

    private val chips = mutableMapOf<SensorPainel, Chip>()

    init {
        val contexto = binding.root.context
        for (sensor in SensorPainel.entries) {
            val chip = Chip(contexto).apply {
                text = sensor.titulo
                isCheckable = true
                setOnClickListener { alternar(sensor) }
            }
            chips[sensor] = chip
            binding.chipsSensoresHud.addView(chip)
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

    /** Recarrega os controles (ex.: "Restaurar padrão"). */
    fun mostrar(nova: ConfigHud) {
        config = nova
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
        // No minimizado os sensores são fixos (RPM e Turbo): a escolha some.
        val expandido = nova.tamanho == TamanhoHud.EXPANDIDO
        binding.tituloSensoresHud.isVisible = expandido
        binding.chipsSensoresHud.isVisible = expandido
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
