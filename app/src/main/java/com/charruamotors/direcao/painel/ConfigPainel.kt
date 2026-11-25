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

/**
 * Atalhos do bloco Navegação que o usuário pode exibir, esconder e reordenar um a um (v1.7). Waze,
 * Google Maps e Spotify abrem o app externo; Posto/Oficina buscam pelo Waze; o HUD é do próprio app.
 */
enum class BotaoNavegacao(val titulo: String) {
    WAZE("Abrir Waze"),
    MAPS("Google Maps"),
    SPOTIFY("Spotify"),
    POSTO("Posto"),
    OFICINA("Oficina"),
    HUD("Modo HUD"),
}

/** O que o "+ Painel" da tela Todos os Sensores fez. */
enum class ResultadoFixar {
    MOSTRADOR_ADICIONADO,
    MOSTRADOR_REMOVIDO,
    LISTA_ADICIONADO,
    LISTA_REMOVIDO,

    /** O Painel nunca fica sem nenhum mostrador. */
    ULTIMO_MOSTRADOR,
}

/** Estilo de desenho de cada mostrador do Painel (escolhido relógio a relógio). */
enum class EstiloMostrador(val titulo: String, val descricao: String) {
    ESPORTIVO("Relógio Esportivo", "Ponteiro em arco com escala e zona vermelha"),
    DIGITAL("Digital Grande", "Números grandes com anel de progresso"),
    FITA("Barra / Fita", "Barra linear em largura total, fácil de ler na trilha"),
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

/**
 * Tamanho do HUD Esportivo (v2.2.8): largura em dp; a altura segue a proporção fixa do Canvas.
 * Médio é o padrão; Pequeno é o tamanho da v2.2.7.
 */
enum class TamanhoHudEsportivo(val titulo: String, val larguraDp: Int) {
    PEQUENO("P", 230),
    MEDIO("M", 285),
    GRANDE("G", 330),
}

/** Estilo de texto/gráficos das células do HUD. */
enum class EstiloHud(val titulo: String) {
    NUMEROS("Números"),
    NUMEROS_GRANDES("Números grandes"),
    BARRAS("Números com barra"),
}

/**
 * Visual do HUD flutuante (v2.2.7): o painel de células de sempre ou o conta-giros esportivo
 * (RPM + turbo acoplados, marcha, velocidade e pedal), desenhado em Canvas.
 */
enum class PresetHud(val titulo: String) {
    PADRAO("Padrão (Atual)"),
    ESPORTIVO("Esportivo (Conta-giros)"),
    ;

    /** O outro preset (atalho de dois toques no HUD). */
    val alternado: PresetHud get() = if (this == PADRAO) ESPORTIVO else PADRAO
}

/** Preferências do HUD flutuante (escolhidas na tela de ativação). */
data class ConfigHud(
    val tamanho: TamanhoHud = TamanhoHud.EXPANDIDO,
    /** Sensores fixados no modo expandido (1 a [MAX_SENSORES]), na ordem em que aparecem. */
    val sensores: List<SensorPainel> = PADRAO_EXPANDIDO,
    val estilo: EstiloHud = EstiloHud.NUMEROS,
    /** Visual do HUD (v2.2.7); sensores, estilo e o tamanho Minimizado/Expandido só valem no [PresetHud.PADRAO]. */
    val preset: PresetHud = PresetHud.PADRAO,
    /** Tamanho P/M/G do visual [PresetHud.ESPORTIVO] (v2.2.8). */
    val tamanhoEsportivo: TamanhoHudEsportivo = TamanhoHudEsportivo.MEDIO,
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
 * bloco Navegação, a lista dinâmica de mostradores (um por sensor (sem o antigo limite de 4), cada um com o próprio
 * estilo), a lista rápida ordenada (comandos de PID; vazia = todos os suportados) e o HUD.
 */
data class ConfigPainel(
    /** Todos os blocos, na ordem (os removidos também guardam o lugar). */
    val ordem: List<SecaoPainel> = ORDEM_PADRAO,
    /** Blocos que o usuário tirou da tela ("Blocos removidos"). */
    val ocultas: Set<SecaoPainel> = emptySet(),
    val botoesNavegacao: Set<BotaoNavegacao> = BotaoNavegacao.entries.toSet(),
    /** Ordem de todos os atalhos da Navegação (os escondidos também guardam o lugar). */
    val ordemBotoes: List<BotaoNavegacao> = BotaoNavegacao.entries,
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

    /** Atalhos que aparecem na Navegação, na ordem escolhida. */
    val botoesVisiveis: List<BotaoNavegacao> get() = OrdemPainel.normalizarBotoes(ordemBotoes).filter { it in botoesNavegacao }

    /** Arrasta o atalho da posição [de] para [para] entre os visíveis; os escondidos vão para o fim. */
    fun moverBotao(de: Int, para: Int): ConfigPainel {
        val visiveis = OrdemPainel.moverItem(botoesVisiveis, de, para)
        return copy(ordemBotoes = visiveis + OrdemPainel.normalizarBotoes(ordemBotoes).filter { it !in botoesNavegacao })
    }

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

    /** Layout rápido: troca os relógios por [sensores] (sem repetir), no estilo do primeiro relógio atual. */
    fun aplicarLayout(sensores: List<SensorPainel>): ConfigPainel {
        val estilo = mostradores.firstOrNull()?.estilo ?: EstiloMostrador.ESPORTIVO
        return copy(mostradores = OrdemPainel.normalizarMostradores(sensores.distinct().map { Mostrador(it, estilo) }))
    }

    /** "Aplicar estilo a todos". */
    fun aplicarEstiloATodos(estilo: EstiloMostrador): ConfigPainel = copy(mostradores = mostradores.map { it.copy(estilo = estilo) })

    // ---------------------------------------------------------------- "+ Painel" (Todos os Sensores)

    /** O sensor do PID [comando] está no Painel (num mostrador ou na lista rápida)? */
    fun fixadoNoPainel(comando: String): Boolean {
        val sensor = SensorPainel.doComando(comando)
        return if (sensor != null) mostradores.any { it.sensor == sensor } else comando.uppercase() in listaRapida
    }

    /**
     * Fixa ou tira do Painel o sensor do PID [comando] (v2.3.1): os que têm mostrador próprio
     * ([SensorPainel]) viram um relógio; os demais entram na lista rápida. O bloco removido volta.
     */
    fun alternarNoPainel(comando: String): Pair<ConfigPainel, ResultadoFixar> {
        val sensor = SensorPainel.doComando(comando)
        if (sensor == null) {
            val novo = alternarListaRapida(comando)
            return if (comando.uppercase() in novo.listaRapida) {
                novo.restaurar(SecaoPainel.LISTA_SENSORES) to ResultadoFixar.LISTA_ADICIONADO
            } else {
                novo to ResultadoFixar.LISTA_REMOVIDO
            }
        }
        val posicao = mostradores.indexOfFirst { it.sensor == sensor }
        if (posicao >= 0) {
            if (!podeRemoverMostrador) return this to ResultadoFixar.ULTIMO_MOSTRADOR
            return removerMostrador(posicao) to ResultadoFixar.MOSTRADOR_REMOVIDO
        }
        val estilo = mostradores.lastOrNull()?.estilo ?: EstiloMostrador.ESPORTIVO
        return copy(mostradores = mostradores + Mostrador(sensor, estilo)).restaurar(SecaoPainel.MARCADORES) to
            ResultadoFixar.MOSTRADOR_ADICIONADO
    }

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

        /** Sem teto prático (v1.7): cada sensor entra uma vez, então o limite é o total de sensores. */
        val MAX_MOSTRADORES: Int = SensorPainel.entries.size

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

        /** Layout rápido "Padrão": RPM, Velocidade, Turbo/MAP e Temp. Água. */
        val LAYOUT_PADRAO = MOSTRADORES_PADRAO.map { it.sensor }

        /** Layout rápido "Turbo Flex (Cruze)": motor turbo a gasolina/etanol com câmbio automático de 6 marchas. */
        val LAYOUT_TURBO_FLEX = listOf(
            SensorPainel.RPM, SensorPainel.VELOCIDADE, SensorPainel.TURBO, SensorPainel.RELACAO_AF,
            SensorPainel.ETANOL, SensorPainel.SONDA_LAMBDA, SensorPainel.RAIL, SensorPainel.TEMP_MOTOR,
            SensorPainel.TEMP_CAMBIO, SensorPainel.MARCHA_ATUAL,
        )
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

    /** Sem repetidos e com todos os atalhos (os que faltarem entram no fim, na ordem padrão). */
    fun normalizarBotoes(ordem: List<BotaoNavegacao>): List<BotaoNavegacao> = (ordem + BotaoNavegacao.entries).distinct()

    /** Quantos relógios couberem (um por sensor, sem repetir); vazio volta aos 4 padrão. */
    fun normalizarMostradores(mostradores: List<Mostrador>): List<Mostrador> =
        mostradores.distinctBy { it.sensor }.take(ConfigPainel.MAX_MOSTRADORES).ifEmpty { ConfigPainel.MOSTRADORES_PADRAO }

    /** Sem repetidos, mantendo a ordem. */
    fun normalizarListaRapida(lista: List<String>): List<String> =
        lista.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
}
