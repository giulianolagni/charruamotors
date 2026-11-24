package com.charruamotors.obd.pid

/**
 * Decodificadores nomeados para PIDs de montadora cujo valor não sai de uma fórmula aritmética
 * (`+ - * /`): máscaras de bits e inteiros de 16 bits com sinal. O catálogo os referencia pelo campo
 * `decodificador`. Funções puras (bytes de dados → valor); `NaN` = leitura inválida.
 */
object Decodificadores {

    /** Valor de [ALAVANCA_A750F] para cada posição (compatível com os rótulos do mostrador Alavanca). */
    const val ALAVANCA_P = 0.0
    const val ALAVANCA_R = 1.0
    const val ALAVANCA_N = 2.0
    const val ALAVANCA_D = 3.0

    /** Posições `3` (`C = 00, D = 01`), `M` (outra), `L` (`C = 03, D = 00`), `4` (`00 06`) e `2` (`02 00`) do A750F. */
    const val ALAVANCA_3 = 4.0
    const val ALAVANCA_M = 5.0
    const val ALAVANCA_L = 6.0
    const val ALAVANCA_4 = 8.0
    const val ALAVANCA_2 = 9.0

    /** `P` ou `N`: o `21D9` não distingue as duas (`C = 00, D = 00`); o mostrador exibe `P/N`. */
    const val ALAVANCA_P_N = 7.0

    /**
     * Alavanca do câmbio A750F pela resposta do `7E1` ao `21D9` (`0:61D9 A B C D ...`), lida nos
     * bytes C e D (índices 2 e 3), como no log real da SW4 2013: `00 00` = P/N; `04 00` = R;
     * `00 04` = D; `00 06` = 4; `00 01` = 3; `02 00` = 2; `03 00` = L. Qualquer outro par diferente
     * de zero é uma posição ainda não mapeada e vira [ALAVANCA_M], em vez de ficar sem valor.
     */
    private fun alavancaA750f(b: List<Int>): Double {
        val c = b[2] and 0xFF
        val d = b[3] and 0xFF
        return when {
            c == 0x00 && d == 0x00 -> ALAVANCA_P_N
            c == 0x04 && d == 0x00 -> ALAVANCA_R
            c == 0x00 && d == 0x04 -> ALAVANCA_D
            c == 0x00 && d == 0x06 -> ALAVANCA_4
            c == 0x00 && d == 0x01 -> ALAVANCA_3
            c == 0x02 && d == 0x00 -> ALAVANCA_2
            c == 0x03 && d == 0x00 -> ALAVANCA_L
            else -> ALAVANCA_M
        }
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
