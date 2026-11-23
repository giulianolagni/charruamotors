package com.charruamotors.obd.pid

/**
 * Decodificadores nomeados para PIDs de montadora cujo valor não sai de uma fórmula aritmética
 * (`+ - * /`): máscaras de bits e inteiros de 16 bits com sinal. O catálogo os referencia pelo campo
 * `decodificador`. Funções puras (bytes de dados → valor); `NaN` = leitura inválida.
 */
object Decodificadores {

    /** Valor de [ALAVANCA_A750F] para cada posição (compatível com os rótulos P R N D 3 2 L). */
    const val ALAVANCA_P = 0.0
    const val ALAVANCA_R = 1.0
    const val ALAVANCA_D = 3.0

    /**
     * Alavanca do câmbio A750F, resposta do `7E1` ao `21D9` (`61 D9 b0 b1 b2 b3 ...`): `b0 == 0x10` = P;
     * bit 2 de `b2` = R; bit 2 de `b3` = D. Qualquer outro estado (N etc.) não foi mapeado nas capturas
     * e vira `NaN`, em vez de um rótulo chutado.
     */
    private fun alavancaA750f(b: List<Int>): Double = when {
        b[0] and 0xFF == 0x10 -> ALAVANCA_P
        b[2] and 0x04 != 0 -> ALAVANCA_R
        b[3] and 0x04 != 0 -> ALAVANCA_D
        else -> Double.NaN
    }

    /** Correção do bico injetor [n] (1..4) do `7E0 · 21AD`: `signed short` big-endian ÷ 512 (mm³/st). */
    private fun bico(b: List<Int>, n: Int): Double {
        val i = (n - 1) * 2
        val bruto = (((b[i] and 0xFF) shl 8) or (b[i + 1] and 0xFF)).toShort()
        return bruto / 512.0
    }

    private class Decodificador(val bytes: Int, val converter: (List<Int>) -> Double)

    private val TODOS: Map<String, Decodificador> = mapOf(
        "ALAVANCA_A750F" to Decodificador(4, ::alavancaA750f),
        "BICO_1" to Decodificador(2) { bico(it, 1) },
        "BICO_2" to Decodificador(4) { bico(it, 2) },
        "BICO_3" to Decodificador(6) { bico(it, 3) },
        "BICO_4" to Decodificador(8) { bico(it, 4) },
    )

    fun existe(nome: String): Boolean = nome.uppercase() in TODOS

    /** Bytes de dados que o decodificador [nome] consome. */
    fun bytesNecessarios(nome: String): Int = TODOS.getValue(nome.uppercase()).bytes

    fun decodificar(nome: String, dados: List<Int>): Double {
        val d = TODOS.getValue(nome.uppercase())
        require(dados.size >= d.bytes) { "Decodificador $nome precisa de ${d.bytes} byte(s), recebeu ${dados.size}" }
        return d.converter(dados)
    }
}
