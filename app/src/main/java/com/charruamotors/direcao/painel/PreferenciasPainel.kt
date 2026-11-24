package com.charruamotors.direcao.painel

import android.content.Context
import com.charruamotors.sistema.preferencias.ArmazenamentoChaveValor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [ConfigPainel] persistida (arquivo `painel`, chaves do [CodecPainel]): a tela "Personalizar
 * Painel", a ativação do HUD e a restauração do backup gravam aqui; a aba Painel e o HUD flutuante
 * observam [config] e se redesenham na hora.
 */
class PreferenciasPainel(private val armazenamento: ArmazenamentoChaveValor) {

    constructor(context: Context) : this(
        ArmazenamentoChaveValor.deSharedPreferences(
            context.applicationContext.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE),
        ),
    )

    private val _config = MutableStateFlow(CodecPainel.decodificar(armazenamento::lerTexto))
    val config: StateFlow<ConfigPainel> = _config.asStateFlow()

    fun atualizar(transformar: (ConfigPainel) -> ConfigPainel) = synchronized(this) {
        val nova = CodecPainel.normalizar(transformar(_config.value))
        if (nova == _config.value) return@synchronized
        armazenamento.gravar(CodecPainel.codificar(nova))
        _config.value = nova
    }

    fun definirHud(hud: ConfigHud) = atualizar { it.copy(hud = hud) }

    /** "Restaurar padrão" da tela de personalização. */
    fun restaurarPadrao() = atualizar { ConfigPainel() }

    /** Restauração do backup: troca a personalização inteira pela do arquivo. */
    fun restaurar(config: ConfigPainel) = atualizar { config }

    companion object {
        const val ARQUIVO = "painel"
    }
}
