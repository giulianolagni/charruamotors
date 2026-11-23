package com.charruamotors.direcao.painel

import android.content.Context
import com.charruamotors.sistema.preferencias.ArmazenamentoChaveValor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [ConfigPainel] persistida (arquivo `painel`): a tela "Personalizar Painel" e a ativação do HUD
 * gravam aqui; a aba Painel e o HUD flutuante observam [config] e se redesenham na hora.
 */
class PreferenciasPainel(private val armazenamento: ArmazenamentoChaveValor) {

    constructor(context: Context) : this(
        ArmazenamentoChaveValor.deSharedPreferences(
            context.applicationContext.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE),
        ),
    )

    private val _config = MutableStateFlow(ler())
    val config: StateFlow<ConfigPainel> = _config.asStateFlow()

    fun atualizar(transformar: (ConfigPainel) -> ConfigPainel) = synchronized(this) {
        val nova = normalizar(transformar(_config.value))
        if (nova == _config.value) return@synchronized
        armazenamento.gravar(
            mapOf(
                ORDEM to OrdemPainel.codificar(nova.ordem),
                ESTILO to nova.estilo.name,
                MOSTRADORES to OrdemPainel.codificar(nova.mostradores),
                LISTA_RAPIDA to OrdemPainel.codificarTextos(nova.listaRapida),
                HUD_TAMANHO to nova.hud.tamanho.name,
                HUD_SENSORES to OrdemPainel.codificar(nova.hud.sensores),
                HUD_ESTILO to nova.hud.estilo.name,
            ),
        )
        _config.value = nova
    }

    fun definirHud(hud: ConfigHud) = atualizar { it.copy(hud = hud) }

    /** "Restaurar padrão" da tela de personalização. */
    fun restaurarPadrao() = atualizar { ConfigPainel() }

    private fun ler(): ConfigPainel = normalizar(
        ConfigPainel(
            ordem = OrdemPainel.decodificarOrdem(armazenamento.lerTexto(ORDEM)),
            estilo = enumOu(armazenamento.lerTexto(ESTILO), EstiloMostrador.ESPORTIVO),
            mostradores = OrdemPainel.decodificarSensores(armazenamento.lerTexto(MOSTRADORES)),
            listaRapida = OrdemPainel.decodificarTextos(armazenamento.lerTexto(LISTA_RAPIDA)),
            hud = ConfigHud(
                tamanho = enumOu(armazenamento.lerTexto(HUD_TAMANHO), TamanhoHud.EXPANDIDO),
                sensores = OrdemPainel.decodificarSensores(armazenamento.lerTexto(HUD_SENSORES))
                    .ifEmpty { ConfigHud.PADRAO_EXPANDIDO },
                estilo = enumOu(armazenamento.lerTexto(HUD_ESTILO), EstiloHud.NUMEROS),
            ),
        ),
    )

    private fun normalizar(c: ConfigPainel) = c.copy(
        ordem = OrdemPainel.normalizar(c.ordem),
        mostradores = OrdemPainel.normalizarMostradores(c.mostradores),
        hud = c.hud.copy(sensores = c.hud.sensores.distinct().take(ConfigHud.MAX_SENSORES).ifEmpty { ConfigHud.PADRAO_EXPANDIDO }),
    )

    private inline fun <reified E : Enum<E>> enumOu(texto: String?, padrao: E): E =
        enumValues<E>().firstOrNull { it.name.equals(texto, ignoreCase = true) } ?: padrao

    companion object {
        const val ARQUIVO = "painel"
        private const val ORDEM = "ordem"
        private const val ESTILO = "estilo"
        private const val MOSTRADORES = "mostradores"
        private const val LISTA_RAPIDA = "lista_rapida"
        private const val HUD_TAMANHO = "hud_tamanho"
        private const val HUD_SENSORES = "hud_sensores"
        private const val HUD_ESTILO = "hud_estilo"
    }
}
