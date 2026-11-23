package com.charruamotors.direcao.painel

/**
 * Blocos do Painel, todos reordenáveis e removíveis em "Personalizar Painel". Os nomes são
 * persistidos (o `MARCADORES` vem da v1.4 e continua valendo para os mostradores/relógios).
 */
enum class SecaoPainel(val titulo: String, val descricao: String) {
    MUSICA("Músicas (Spotify)", "Mini-player do Spotify / YouTube Music"),
    NAVEGACAO("Navegação (Atalhos Waze)", "Abrir o Waze, posto, oficina e o Modo HUD"),
    MARCADORES("Mostradores (Relógios)", "Os relógios ao vivo, no estilo de cada um"),
    GRAFICO("Gráfico ao vivo", "Últimos 60 s de RPM e Turbo/MAP"),
    COMPUTADOR("Computador de bordo", "Consumo instantâneo e média da viagem"),
    CRONOMETRO("Cronômetro 0–100", "Arrancada medida automaticamente"),
    LISTA_SENSORES("Lista rápida de sensores", "Grade de sensores, salvar leitura e gravar log CSV"),
    INFO_ECU("Informações da ECU", "VIN, PIDs suportados, perfil e protocolo"),
}

/** Botões do bloco Navegação que o usuário pode exibir ou esconder um a um. */
enum class BotaoNavegacao(val titulo: String) {
    WAZE("Abrir Waze"),
    POSTO("Posto"),
    OFICINA("Oficina"),
    HUD("Modo HUD"),
}

/** Estilo de desenho de cada mostrador do Painel (escolhido relógio a relógio). */
enum class EstiloMostrador(val titulo: String, val descricao: String) {
    ESPORTIVO("Esportivo", "Ponteiro em arco com escala e zona vermelha"),
    DIGITAL("Digital Moderno", "Números grandes com anel de progresso"),
    FITA("Fita / Off-road", "Barra linear em largura total, fácil de ler na trilha"),
}

/** Um relógio do Painel: o sensor e o estilo próprio dele. */
data class Mostrador(
    val sensor: SensorPainel,
    val estilo: EstiloMostrador = EstiloMostrador.ESPORTIVO,
)

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
    /** Sensores fixados no modo expandido (1 a [MAX_SENSORES]), na ordem em que aparecem. */
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

    /** Arrastar e soltar a ordem das células do HUD. */
    fun moverSensor(de: Int, para: Int): ConfigHud = copy(sensores = OrdemPainel.moverItem(sensores.distinct(), de, para))

    companion object {
        const val MAX_SENSORES = 4
        val MINIMIZADO_FIXOS = listOf(SensorPainel.RPM, SensorPainel.TURBO)
        val PADRAO_EXPANDIDO = listOf(SensorPainel.RPM, SensorPainel.TURBO, SensorPainel.TEMP_MOTOR, SensorPainel.RAIL)
    }
}

/**
 * Personalização da aba Painel (v1.5): ordem de todos os blocos e os removidos, os botões do
 * bloco Navegação, a lista dinâmica de mostradores (1 a [MAX_MOSTRADORES], cada um com o próprio
 * estilo), a lista rápida ordenada (comandos de PID; vazia = todos os suportados) e o HUD.
 */
data class ConfigPainel(
    /** Todos os blocos, na ordem (os removidos também guardam o lugar). */
    val ordem: List<SecaoPainel> = ORDEM_PADRAO,
    /** Blocos que o usuário tirou da tela ("Blocos removidos"). */
    val ocultas: Set<SecaoPainel> = emptySet(),
    val botoesNavegacao: Set<BotaoNavegacao> = BotaoNavegacao.entries.toSet(),
    val mostradores: List<Mostrador> = MOSTRADORES_PADRAO,
    /** Comandos da lista rápida, na ordem escolhida (arrastar). */
    val listaRapida: List<String> = emptyList(),
    val hud: ConfigHud = ConfigHud(),
) {
    /** Blocos que aparecem no Painel, na ordem. */
    val secoesVisiveis: List<SecaoPainel> get() = OrdemPainel.normalizar(ordem).filter { it !in ocultas }

    /** "Blocos removidos (+ Adicionar de volta)", na ordem em que estavam. */
    val secoesRemovidas: List<SecaoPainel> get() = OrdemPainel.normalizar(ordem).filter { it in ocultas }

    fun visivel(secao: SecaoPainel): Boolean = secao !in ocultas

    fun botaoVisivel(botao: BotaoNavegacao): Boolean = botao in botoesNavegacao

    val podeAdicionarMostrador: Boolean get() = mostradores.size < MAX_MOSTRADORES

    val podeRemoverMostrador: Boolean get() = mostradores.size > MIN_MOSTRADORES

    // ---------------------------------------------------------------- blocos

    /** Arrasta o bloco da posição [de] para [para] na lista dos visíveis; os removidos vão para o fim. */
    fun mover(de: Int, para: Int): ConfigPainel {
        val visiveis = OrdemPainel.moverItem(secoesVisiveis, de, para)
        return copy(ordem = visiveis + secoesRemovidas)
    }

    /** Tira o bloco da tela (ele vai para "Blocos removidos"). */
    fun ocultar(secao: SecaoPainel): ConfigPainel = copy(ocultas = ocultas + secao)

    /** "+ Adicionar de volta": o bloco volta logo depois do último visível. */
    fun restaurar(secao: SecaoPainel): ConfigPainel {
        if (secao !in ocultas) return this
        val visiveis = secoesVisiveis + secao
        val removidas = secoesRemovidas - secao
        return copy(ordem = visiveis + removidas, ocultas = ocultas - secao)
    }

    /** Mostra/esconde um botão do bloco Navegação. */
    fun alternarBotaoNavegacao(botao: BotaoNavegacao): ConfigPainel =
        copy(botoesNavegacao = if (botao in botoesNavegacao) botoesNavegacao - botao else botoesNavegacao + botao)

    // ---------------------------------------------------------------- mostradores

    /** "+ Adicionar mostrador": o primeiro sensor ainda fora do painel, no estilo do último relógio. */
    fun adicionarMostrador(): ConfigPainel {
        if (!podeAdicionarMostrador) return this
        val usados = mostradores.map { it.sensor }.toSet()
        val sensor = (MOSTRADORES_PADRAO.map { it.sensor } + SensorPainel.entries).firstOrNull { it !in usados } ?: return this
        val estilo = mostradores.lastOrNull()?.estilo ?: EstiloMostrador.ESPORTIVO
        return copy(mostradores = mostradores + Mostrador(sensor, estilo))
    }

    /** Remove o relógio [posicao] (sempre sobra pelo menos um). */
    fun removerMostrador(posicao: Int): ConfigPainel {
        if (!podeRemoverMostrador || posicao !in mostradores.indices) return this
        return copy(mostradores = mostradores.filterIndexed { i, _ -> i != posicao })
    }

    fun moverMostrador(de: Int, para: Int): ConfigPainel = copy(mostradores = OrdemPainel.moverItem(mostradores, de, para))

    /**
     * Coloca [sensor] no relógio [posicao]. Se ele já estava em outro, os dois trocam de sensor (cada
     * um mantém o próprio estilo): o painel nunca mostra o mesmo sensor duas vezes.
     */
    fun definirMostrador(posicao: Int, sensor: SensorPainel): ConfigPainel {
        if (posicao !in mostradores.indices) return this
        val atuais = mostradores.toMutableList()
        val anterior = atuais.indexOfFirst { it.sensor == sensor }
        if (anterior >= 0) atuais[anterior] = atuais[anterior].copy(sensor = atuais[posicao].sensor)
        atuais[posicao] = atuais[posicao].copy(sensor = sensor)
        return copy(mostradores = atuais)
    }

    /** Estilo próprio de um relógio (dá para misturar: RPM Esportivo + Turbo Fita + Motor Digital). */
    fun definirEstilo(posicao: Int, estilo: EstiloMostrador): ConfigPainel {
        if (posicao !in mostradores.indices) return this
        return copy(mostradores = mostradores.mapIndexed { i, m -> if (i == posicao) m.copy(estilo = estilo) else m })
    }

    /** "Aplicar estilo a todos". */
    fun aplicarEstiloATodos(estilo: EstiloMostrador): ConfigPainel = copy(mostradores = mostradores.map { it.copy(estilo = estilo) })

    // ---------------------------------------------------------------- lista rápida

    /** Liga/desliga um PID na lista rápida (entra no fim). */
    fun alternarListaRapida(comando: String): ConfigPainel {
        val chave = comando.uppercase()
        return copy(listaRapida = if (chave in listaRapida) listaRapida - chave else listaRapida + chave)
    }

    fun moverListaRapida(de: Int, para: Int): ConfigPainel = copy(listaRapida = OrdemPainel.moverItem(listaRapida, de, para))

    /** Filtra a grade de sensores pela lista rápida, na ordem escolhida pelo usuário; vazia = todos. */
    fun <T> filtrarListaRapida(itens: List<T>, comando: (T) -> String): List<T> {
        if (listaRapida.isEmpty()) return itens
        val porComando = itens.associateBy { comando(it).uppercase() }
        return listaRapida.mapNotNull { porComando[it] }
    }

    companion object {
        const val MIN_MOSTRADORES = 1
        const val MAX_MOSTRADORES = 12

        val ORDEM_PADRAO = listOf(
            SecaoPainel.MUSICA,
            SecaoPainel.NAVEGACAO,
            SecaoPainel.MARCADORES,
            SecaoPainel.GRAFICO,
            SecaoPainel.COMPUTADOR,
            SecaoPainel.CRONOMETRO,
            SecaoPainel.LISTA_SENSORES,
            SecaoPainel.INFO_ECU,
        )
        val MOSTRADORES_PADRAO = listOf(SensorPainel.RPM, SensorPainel.VELOCIDADE, SensorPainel.TURBO, SensorPainel.TEMP_MOTOR)
            .map { Mostrador(it) }
    }
}

/** Regras puras de ordem e normalização do Painel. A (de)serialização fica em [CodecPainel]. */
object OrdemPainel {

    /** Sem repetidos e com todas as seções (as que faltarem entram no fim, na ordem padrão). */
    fun normalizar(ordem: List<SecaoPainel>): List<SecaoPainel> = (ordem + ConfigPainel.ORDEM_PADRAO).distinct()

    /** Move um item de [de] para [para]; índices inválidos ou iguais devolvem a lista como está. */
    fun <T> moverItem(lista: List<T>, de: Int, para: Int): List<T> {
        if (de !in lista.indices || para !in lista.indices || de == para) return lista
        val nova = lista.toMutableList()
        nova.add(para, nova.removeAt(de))
        return nova
    }

    /** 1 a 12 relógios, sem sensor repetido; vazio volta aos 4 padrão. */
    fun normalizarMostradores(mostradores: List<Mostrador>): List<Mostrador> =
        mostradores.distinctBy { it.sensor }.take(ConfigPainel.MAX_MOSTRADORES).ifEmpty { ConfigPainel.MOSTRADORES_PADRAO }

    /** Sem repetidos, mantendo a ordem. */
    fun normalizarListaRapida(lista: List<String>): List<String> =
        lista.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
}
