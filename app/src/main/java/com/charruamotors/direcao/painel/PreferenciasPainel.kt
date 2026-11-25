package com.charruamotors.direcao.painel

import com.charruamotors.sistema.preferencias.ArmazenamentoChaveValor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [ConfigPainel] persistida (chaves do [CodecPainel]): a tela "Personalizar Painel", a ativação do
 * HUD e a restauração do backup gravam aqui; a aba Painel e o HUD flutuante observam [config] e se
 * redesenham na hora.
 *
 * v2.3.0 (perfis isolados): no app o [armazenamento] é o escopo do **veículo ativo**
 * (`carro.<id>.` no arquivo `perfis_carro`), trocado por [trocarArmazenamento] quando o usuário
 * seleciona outro carro — uma mudança no Painel/HUD de um veículo nunca chega a outro.
 */
class PreferenciasPainel(armazenamento: ArmazenamentoChaveValor) {

    @Volatile
    private var armazenamento: ArmazenamentoChaveValor = armazenamento

    private val _config = MutableStateFlow(CodecPainel.decodificar(armazenamento::lerTexto))
    val config: StateFlow<ConfigPainel> = _config.asStateFlow()

    /**
     * Passa a ler/gravar em [novo] (o escopo de outro veículo) e republica a configuração dele.
     * Chamado também depois de uma restauração de backup, para reler o escopo atual.
     */
    fun trocarArmazenamento(novo: ArmazenamentoChaveValor) = synchronized(this) {
        armazenamento = novo
        _config.value = CodecPainel.decodificar(novo::lerTexto)
    }

    fun atualizar(transformar: (ConfigPainel) -> ConfigPainel) = synchronized(this) {
        val nova = CodecPainel.normalizar(transformar(_config.value))
        if (nova == _config.value) return@synchronized
        armazenamento.gravar(CodecPainel.codificar(nova))
        _config.value = nova
    }

    fun definirHud(hud: ConfigHud) = atualizar { it.copy(hud = hud) }

    /** Visual do HUD flutuante (v2.2.7): o overlay reabre sempre no preset salvo. */
    fun definirPresetHud(preset: PresetHud) = atualizar { it.copy(hud = it.hud.copy(preset = preset)) }

    /** Atalho do HUD (dois toques): troca Padrão ↔ Esportivo e devolve o novo. */
    fun alternarPresetHud(): PresetHud {
        val novo = config.value.hud.preset.alternado
        definirPresetHud(novo)
        return novo
    }

    /** "Restaurar padrão" da tela de personalização. */
    fun restaurarPadrao() = atualizar { ConfigPainel() }

    /** Restauração do backup: troca a personalização inteira pela do arquivo. */
    fun restaurar(config: ConfigPainel) = atualizar { config }

    companion object {
        /** Arquivo da v1.5–v2.2.9 (um Painel para todos os carros); migrado na v2.3.0 para o veículo padrão. */
        const val ARQUIVO = "painel"
    }
}
