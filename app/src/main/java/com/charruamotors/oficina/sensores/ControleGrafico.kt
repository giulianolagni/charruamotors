package com.charruamotors.oficina.sensores

/**
 * Estado dos controles dos Gráficos em Tempo Real: janela, Pausar/Retomar (a tela congela numa cópia
 * das amostras enquanto a sessão segue gravando) e Zerar. Puro (JVM).
 */
class ControleGrafico {
    var janela: JanelaTempo = JanelaTempo.PADRAO

    private var congeladas: List<Amostra>? = null

    /** Corte do "Zerar": amostras anteriores não aparecem. */
    private var desdeMs: Long = 0

    val pausado: Boolean get() = congeladas != null

    fun alternarPausa(aoVivo: List<Amostra>) {
        congeladas = if (congeladas == null) aoVivo else null
    }

    /** Limpa a tela e os picos (o mínimo/máximo da sessão não muda) e volta ao vivo. */
    fun zerar(aoVivo: List<Amostra>) {
        desdeMs = aoVivo.lastOrNull()?.let { it.tMs + 1 } ?: 0
        congeladas = null
    }

    fun quadro(aoVivo: List<Amostra>, comandos: List<String>): QuadroGrafico {
        val amostras = congeladas ?: aoVivo
        return MontadorGrafico.montar(amostras, comandos, amostras.lastOrNull()?.tMs ?: 0, janela, desdeMs)
    }
}
