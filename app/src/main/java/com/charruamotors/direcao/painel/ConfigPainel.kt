package com.charruamotors.direcao.painel

/** Blocos reordenáveis do topo da aba Painel (arrastar e soltar em "Personalizar Painel"). */
enum class SecaoPainel(val titulo: String, val descricao: String) {
    MUSICA("Músicas (Spotify)", "Mini-player do Spotify / YouTube Music"),
    NAVEGACAO("Navegação (Atalhos Waze)", "Abrir o Waze, posto, oficina e o Modo HUD"),
    MARCADORES("Marcadores (Sensores)", "Os 4 mostradores principais"),
}

/** Estilo de desenho dos 4 mostradores do Painel. */
enum class EstiloMostrador(val titulo: String, val descricao: String) {
    ESPORTIVO("Esportivo", "Ponteiro em arco com escala e zona vermelha"),
    DIGITAL("Digital Moderno", "Números grandes com anel de progresso"),
    FITA("Fita / Off-road", "Barra linear, fácil de ler na trilha"),
}

/** Tamanho do HUD flutuante sobre o Waze. */
enum class TamanhoHud(val titulo: String, val descricao: String) {
    MINIMIZADO("Minimizado", "Só RPM e Turbo"),
    EXPANDIDO("Expandido", "Até 4 sensores à sua escolha"),
}

/** Estilo de texto/gráficos das células do HUD. */
enum class EstiloHud(val titulo: String) {
    NUMEROS("Números"),
    NUMEROS_GRANDES("Números grandes"),
    BARRAS("Números com barra"),
}

/** Preferências do HUD flutuante (escolhidas na tela de ativação). */
data class ConfigHud(
    val tamanho: TamanhoHud = TamanhoHud.EXPANDIDO,
    /** Sensores fixados no modo expandido (1 a [MAX_SENSORES]). */
    val sensores: List<SensorPainel> = PADRAO_EXPANDIDO,
    val estilo: EstiloHud = EstiloHud.NUMEROS,
) {
    /** O que o HUD desenha: RPM e Turbo no minimizado; os escolhidos (sem repetir, até 4) no expandido. */
    val sensoresVisiveis: List<SensorPainel>
        get() = when (tamanho) {
            TamanhoHud.MINIMIZADO -> MINIMIZADO_FIXOS
            TamanhoHud.EXPANDIDO -> sensores.distinct().take(MAX_SENSORES).ifEmpty { PADRAO_EXPANDIDO }
        }

    /** Liga/desliga um sensor no expandido; ignora acima do limite e nunca esvazia a lista. */
    fun alternarSensor(sensor: SensorPainel): ConfigHud {
        val atuais = sensores.distinct()
        val novos = when {
            sensor in atuais -> (atuais - sensor).ifEmpty { atuais }
            atuais.size >= MAX_SENSORES -> atuais
            else -> atuais + sensor
        }
        return copy(sensores = novos)
    }

    companion object {
        const val MAX_SENSORES = 4
        val MINIMIZADO_FIXOS = listOf(SensorPainel.RPM, SensorPainel.TURBO)
        val PADRAO_EXPANDIDO = listOf(SensorPainel.RPM, SensorPainel.TURBO, SensorPainel.TEMP_MOTOR, SensorPainel.RAIL)
    }
}

/**
 * Personalização da aba Painel: ordem dos blocos do topo, estilo dos mostradores, os 4 sensores
 * dos relógios, a lista rápida (comandos de PID; vazia = todos os suportados) e o HUD.
 */
data class ConfigPainel(
    val ordem: List<SecaoPainel> = ORDEM_PADRAO,
    val estilo: EstiloMostrador = EstiloMostrador.ESPORTIVO,
    val mostradores: List<SensorPainel> = MOSTRADORES_PADRAO,
    val listaRapida: Set<String> = emptySet(),
    val hud: ConfigHud = ConfigHud(),
) {
    /** Move a seção de [de] para [para] (arrastar e soltar). */
    fun mover(de: Int, para: Int): ConfigPainel = copy(ordem = OrdemPainel.mover(ordem, de, para))

    /**
     * Coloca [sensor] no mostrador [posicao] (0–3). Se ele já estava em outro, os dois trocam de
     * lugar: o painel nunca mostra o mesmo sensor duas vezes.
     */
    fun definirMostrador(posicao: Int, sensor: SensorPainel): ConfigPainel {
        val atuais = OrdemPainel.normalizarMostradores(mostradores).toMutableList()
        if (posicao !in atuais.indices) return this
        val anterior = atuais.indexOf(sensor)
        if (anterior >= 0) atuais[anterior] = atuais[posicao]
        atuais[posicao] = sensor
        return copy(mostradores = atuais)
    }

    /** Liga/desliga um PID na lista rápida. */
    fun alternarListaRapida(comando: String): ConfigPainel {
        val chave = comando.uppercase()
        return copy(listaRapida = if (chave in listaRapida) listaRapida - chave else listaRapida + chave)
    }

    /** Filtra a grade de sensores pela lista rápida, mantendo a ordem original; vazia = todos. */
    fun <T> filtrarListaRapida(itens: List<T>, comando: (T) -> String): List<T> =
        if (listaRapida.isEmpty()) itens else itens.filter { comando(it).uppercase() in listaRapida }

    companion object {
        val ORDEM_PADRAO = listOf(SecaoPainel.MUSICA, SecaoPainel.NAVEGACAO, SecaoPainel.MARCADORES)
        val MOSTRADORES_PADRAO = listOf(SensorPainel.RPM, SensorPainel.VELOCIDADE, SensorPainel.TURBO, SensorPainel.TEMP_MOTOR)
        const val QUANTIDADE_MOSTRADORES = 4
    }
}

/** Regras puras da ordem e dos mostradores, e a (de)serialização em texto para as preferências. */
object OrdemPainel {

    /** Sem repetidos e com todas as seções (as que faltarem entram no fim, na ordem padrão). */
    fun normalizar(ordem: List<SecaoPainel>): List<SecaoPainel> = (ordem + SecaoPainel.entries).distinct()

    fun mover(ordem: List<SecaoPainel>, de: Int, para: Int): List<SecaoPainel> {
        val lista = normalizar(ordem).toMutableList()
        if (de !in lista.indices || para !in lista.indices || de == para) return lista
        lista.add(para, lista.removeAt(de))
        return lista
    }

    /** Exatamente 4, sem repetidos: completa com os padrões (e depois com os demais sensores). */
    fun normalizarMostradores(mostradores: List<SensorPainel>): List<SensorPainel> =
        (mostradores + ConfigPainel.MOSTRADORES_PADRAO + SensorPainel.entries).distinct().take(ConfigPainel.QUANTIDADE_MOSTRADORES)

    fun codificar(itens: List<Enum<*>>): String = itens.joinToString(SEPARADOR) { it.name }

    fun codificarTextos(itens: Collection<String>): String = itens.joinToString(SEPARADOR)

    fun decodificarOrdem(texto: String?): List<SecaoPainel> =
        normalizar(partes(texto).mapNotNull { p -> SecaoPainel.entries.firstOrNull { it.name == p } })

    fun decodificarSensores(texto: String?): List<SensorPainel> = partes(texto).mapNotNull(SensorPainel::deCodigo).distinct()

    fun decodificarTextos(texto: String?): Set<String> = partes(texto).map { it.uppercase() }.toSet()

    private fun partes(texto: String?): List<String> =
        texto.orEmpty().split(SEPARADOR).map { it.trim() }.filter { it.isNotEmpty() }

    private const val SEPARADOR = ","
}
