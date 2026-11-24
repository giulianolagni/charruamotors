package com.charruamotors.direcao.painel

/**
 * (De)serialização da [ConfigPainel] em pares chave → texto: as mesmas chaves vão para o arquivo
 * de preferências `painel` ([PreferenciasPainel]) e para o objeto `painel` do Backup `.json`.
 *
 * Compatível com a v1.4: sem `mostradores_v2`, os relógios vêm da lista antiga `mostradores`
 * (só os sensores) com o estilo global `estilo` aplicado a todos.
 */
object CodecPainel {

    const val ORDEM = "ordem"
    const val OCULTAS = "ocultas"
    const val BOTOES_NAVEGACAO = "botoes_navegacao"
    const val ORDEM_BOTOES = "ordem_botoes"
    const val MOSTRADORES_V2 = "mostradores_v2"
    const val LISTA_RAPIDA = "lista_rapida"
    const val HUD_TAMANHO = "hud_tamanho"
    const val HUD_SENSORES = "hud_sensores"
    const val HUD_ESTILO = "hud_estilo"

    /** v1.4 (só leitura). */
    const val MOSTRADORES_LEGADO = "mostradores"
    const val ESTILO_LEGADO = "estilo"

    /** Todas as chaves que o codec grava. */
    val CHAVES = listOf(ORDEM, OCULTAS, BOTOES_NAVEGACAO, ORDEM_BOTOES, MOSTRADORES_V2, LISTA_RAPIDA, HUD_TAMANHO, HUD_SENSORES, HUD_ESTILO)

    fun codificar(c: ConfigPainel): Map<String, String> = mapOf(
        ORDEM to juntar(OrdemPainel.normalizar(c.ordem).map { it.name }),
        OCULTAS to juntar(c.ocultas.sortedBy { it.ordinal }.map { it.name }),
        BOTOES_NAVEGACAO to juntar(c.botoesNavegacao.sortedBy { it.ordinal }.map { it.name }),
        ORDEM_BOTOES to juntar(OrdemPainel.normalizarBotoes(c.ordemBotoes).map { it.name }),
        MOSTRADORES_V2 to juntar(c.mostradores.map { "${it.sensor.name}$SEPARADOR_ESTILO${it.estilo.name}" }),
        LISTA_RAPIDA to juntar(c.listaRapida),
        HUD_TAMANHO to c.hud.tamanho.name,
        HUD_SENSORES to juntar(c.hud.sensores.map { it.name }),
        HUD_ESTILO to c.hud.estilo.name,
    )

    /** Lê com [ler] (null = chave ausente); valores ausentes ou corrompidos voltam aos padrões. */
    fun decodificar(ler: (String) -> String?): ConfigPainel {
        val estiloLegado = enumOu(ler(ESTILO_LEGADO), EstiloMostrador.ESPORTIVO)
        val mostradores = ler(MOSTRADORES_V2)?.let(::decodificarMostradores)
            ?: decodificarSensores(ler(MOSTRADORES_LEGADO)).map { Mostrador(it, estiloLegado) }
        // Ausente = todos os botões (padrão); presente e vazio = o usuário escondeu todos.
        val botoes = ler(BOTOES_NAVEGACAO)?.let { texto -> partes(texto).mapNotNull { enumOuNull<BotaoNavegacao>(it) }.toSet() }
            ?: BotaoNavegacao.entries.toSet()
        return normalizar(
            ConfigPainel(
                ordem = partes(ler(ORDEM)).mapNotNull { enumOuNull<SecaoPainel>(it) },
                ocultas = partes(ler(OCULTAS)).mapNotNull { enumOuNull<SecaoPainel>(it) }.toSet(),
                botoesNavegacao = botoes,
                ordemBotoes = partes(ler(ORDEM_BOTOES)).mapNotNull { enumOuNull<BotaoNavegacao>(it) },
                mostradores = mostradores,
                listaRapida = partes(ler(LISTA_RAPIDA)),
                hud = ConfigHud(
                    tamanho = enumOu(ler(HUD_TAMANHO), TamanhoHud.EXPANDIDO),
                    sensores = decodificarSensores(ler(HUD_SENSORES)),
                    estilo = enumOu(ler(HUD_ESTILO), EstiloHud.NUMEROS),
                ),
            ),
        )
    }

    /** Sem repetidos, dentro dos limites e com todos os blocos na ordem. */
    fun normalizar(c: ConfigPainel): ConfigPainel = c.copy(
        ordem = OrdemPainel.normalizar(c.ordem),
        ordemBotoes = OrdemPainel.normalizarBotoes(c.ordemBotoes),
        mostradores = OrdemPainel.normalizarMostradores(c.mostradores),
        listaRapida = OrdemPainel.normalizarListaRapida(c.listaRapida),
        hud = c.hud.copy(sensores = c.hud.sensores.distinct().take(ConfigHud.MAX_SENSORES).ifEmpty { ConfigHud.PADRAO_EXPANDIDO }),
    )

    /** `RPM:ESPORTIVO,TURBO:FITA` (estilo desconhecido = Esportivo; sensor desconhecido some). */
    private fun decodificarMostradores(texto: String): List<Mostrador> = partes(texto).mapNotNull { parte ->
        val sensor = SensorPainel.deCodigo(parte.substringBefore(SEPARADOR_ESTILO)) ?: return@mapNotNull null
        Mostrador(sensor, enumOu(parte.substringAfter(SEPARADOR_ESTILO, ""), EstiloMostrador.ESPORTIVO))
    }

    private fun decodificarSensores(texto: String?): List<SensorPainel> = partes(texto).mapNotNull(SensorPainel::deCodigo).distinct()

    private fun juntar(itens: List<String>) = itens.joinToString(SEPARADOR)

    private fun partes(texto: String?): List<String> =
        texto.orEmpty().split(SEPARADOR).map { it.trim() }.filter { it.isNotEmpty() }

    private inline fun <reified E : Enum<E>> enumOuNull(texto: String?): E? =
        enumValues<E>().firstOrNull { it.name.equals(texto?.trim(), ignoreCase = true) }

    private inline fun <reified E : Enum<E>> enumOu(texto: String?, padrao: E): E = enumOuNull<E>(texto) ?: padrao

    private const val SEPARADOR = ","
    private const val SEPARADOR_ESTILO = ":"
}
