package com.charruamotors.obd

/** Resultado da interpretação de uma resposta bruta do ELM327. */
sealed class RespostaObd<out T> {
    /** Resposta válida, já convertida para a unidade do domínio. */
    data class Ok<T>(val valor: T) : RespostaObd<T>()

    /** `NO DATA`: a ECU não respondeu (PID não suportado ou sem códigos). */
    data object SemDados : RespostaObd<Nothing>()

    /** `?`: o adaptador não entendeu o comando enviado. */
    data object ComandoInvalido : RespostaObd<Nothing>()

    /** `UNABLE TO CONNECT`, `CAN ERROR`, `BUS ERROR`...: adaptador sem comunicação com a ECU. */
    data object SemConexao : RespostaObd<Nothing>()

    /** Resposta que não pôde ser interpretada (lixo, truncada, valor fora da faixa). */
    data class Invalido(val bruto: String) : RespostaObd<Nothing>()
}

/**
 * Interpreta as respostas de texto do adaptador ELM327. Objeto puro (sem Android),
 * portanto testável com testes unitários.
 *
 * Formato típico: `41 0C 1A F8` = modo 01 (resposta = 0x40 + modo), PID `0C`, bytes A e B.
 * O parser tolera eco do comando, espaços (ou ausência deles, `ATS0`), prompt `>`,
 * `SEARCHING...`, caracteres de controle, minúsculas e respostas de várias ECUs.
 */
object ObdParser {

    private val regexLinhaMultiFrame = Regex("^[0-9A-F]:")
    private val regexTamanhoCan = Regex("^[0-9A-F]{3}$")
    private val regexTensao = Regex("^(\\d{1,2}(?:[.,]\\d+)?)V?$")
    private val regexBusInitErro = Regex("BUS ?INIT:.*ERROR")

    // ---------------------------------------------------------------- PIDs do modo 01

    /** `010C`: RPM = (256·A + B) / 4. */
    fun parseRpm(bruto: String, comando: String? = "010C"): RespostaObd<Int> =
        bytesDoPid(bruto, "0C", 2, comando).mapear { (a, b) -> (a * 256 + b) / 4 }

    /** `010D`: velocidade em km/h = A. */
    fun parseVelocidade(bruto: String, comando: String? = "010D"): RespostaObd<Int> =
        bytesDoPid(bruto, "0D", 1, comando).mapear { it[0] }

    /** `0105`: temperatura do líquido de arrefecimento em °C = A − 40. */
    fun parseTemperaturaMotor(bruto: String, comando: String? = "0105"): RespostaObd<Int> =
        bytesDoPid(bruto, "05", 1, comando).mapear { it[0] - 40 }

    /** `0104`: carga calculada do motor em % = A · 100 / 255. */
    fun parseCargaMotor(bruto: String, comando: String? = "0104"): RespostaObd<Double> =
        bytesDoPid(bruto, "04", 1, comando).mapear { it[0] * 100.0 / 255.0 }

    /** `ATRV`: tensão da bateria em volts, ex.: `12.6V`. */
    fun parseTensao(bruto: String, comando: String? = "ATRV"): RespostaObd<Double> {
        val linhas = linhasLimpas(bruto, comando)
        for (linha in linhas) {
            val numero = regexTensao.matchEntire(compactar(linha))?.groupValues?.get(1) ?: continue
            val volts = numero.replace(',', '.').toDoubleOrNull() ?: continue
            if (volts <= 0.0 || volts > 40.0) return RespostaObd.Invalido(bruto)
            return RespostaObd.Ok(volts)
        }
        return classificarErro(linhas, bruto)
    }

    /** Verifica a resposta de `0100` (PIDs suportados), usada para validar o link com a ECU. */
    fun parsePidsSuportados(bruto: String, comando: String? = "0100"): RespostaObd<Unit> =
        bytesDoPid(bruto, "00", 4, comando).mapear { }

    // ---------------------------------------------------------------- DTCs (modos 03 e 04)

    /**
     * `03`: códigos de falha armazenados. Resposta `43 ...`.
     * Em CAN o primeiro byte após `43` é a quantidade de códigos (total de bytes ímpar);
     * nos demais protocolos vêm apenas pares de bytes, preenchidos com `00 00`.
     */
    fun parseDtcs(bruto: String, comando: String? = "03"): RespostaObd<List<String>> {
        val linhas = linhasLimpas(bruto, comando)
        val codigos = linkedSetOf<String>()
        var achouResposta = false
        for (linha in linhas) {
            val compacta = compactar(linha)
            if (!compacta.startsWith("43")) continue
            val bytes = hexParaBytes(compacta.substring(2))
            achouResposta = true
            val pares = if (bytes.size % 2 == 1) bytes.drop(1) else bytes
            pares.chunked(2).filter { it.size == 2 }.forEach { (b1, b2) ->
                if (b1 != 0 || b2 != 0) codigos += decodificarDtc(b1, b2)
            }
        }
        return if (achouResposta) RespostaObd.Ok(codigos.toList()) else classificarErro(linhas, bruto)
    }

    /** `04`: confirmação da limpeza dos códigos. Resposta positiva = `44`. */
    fun parseLimpezaDtcs(bruto: String, comando: String? = "04"): RespostaObd<Unit> {
        val linhas = linhasLimpas(bruto, comando)
        if (linhas.any { compactar(it).startsWith("44") }) return RespostaObd.Ok(Unit)
        return classificarErro(linhas, bruto)
    }

    /** Converte 2 bytes no formato SAE J2012: 2 bits = sistema (P/C/B/U), 2 bits = dígito, 12 bits = 3 hex. */
    fun decodificarDtc(b1: Int, b2: Int): String {
        val letra = "PCBU"[(b1 shr 6) and 0x3]
        val d1 = (b1 shr 4) and 0x3
        val d2 = b1 and 0xF
        val d3 = (b2 shr 4) and 0xF
        val d4 = b2 and 0xF
        return "$letra$d1${d2.toString(16)}${d3.toString(16)}${d4.toString(16)}".uppercase()
    }

    // ---------------------------------------------------------------- internos

    /** Procura `41 <pid> <bytes...>` em qualquer linha e devolve os [quantidade] bytes de dados. */
    private fun bytesDoPid(bruto: String, pid: String, quantidade: Int, comando: String?): RespostaObd<List<Int>> {
        val linhas = linhasLimpas(bruto, comando)
        val regex = Regex("41$pid([0-9A-F]*)")
        for (linha in linhas) {
            val dados = regex.find(compactar(linha))?.groupValues?.get(1) ?: continue
            val bytes = hexParaBytes(dados)
            if (bytes.size >= quantidade) return RespostaObd.Ok(bytes.take(quantidade))
        }
        // Havia "41 <pid>" mas truncado: resposta inválida, não "sem dados".
        if (linhas.any { regex.containsMatchIn(compactar(it)) }) return RespostaObd.Invalido(bruto)
        return classificarErro(linhas, bruto)
    }

    private fun classificarErro(linhas: List<String>, bruto: String): RespostaObd<Nothing> {
        val texto = linhas.joinToString("\n")
        return when {
            "UNABLE TO CONNECT" in texto || "CAN ERROR" in texto || "BUS ERROR" in texto ||
                regexBusInitErro.containsMatchIn(texto) -> RespostaObd.SemConexao
            "NO DATA" in texto || "NODATA" in compactar(texto) -> RespostaObd.SemDados
            linhas.any { l -> compactar(l).let { it.isNotEmpty() && it.all { c -> c == '?' } } } ->
                RespostaObd.ComandoInvalido
            else -> RespostaObd.Invalido(bruto)
        }
    }

    /**
     * Quebra a resposta em linhas "limpas": maiúsculas, sem caracteres de controle, sem o prompt `>`,
     * sem o eco do [comando], sem `SEARCHING...`. Respostas multi-frame CAN (`0:`, `1:`) são unidas.
     */
    internal fun linhasLimpas(bruto: String, comando: String?): List<String> {
        val eco = comando?.let(::compactar)?.uppercase()
        var linhas = bruto
            .replace('>', '\n')
            .split('\r', '\n')
            .map { l -> l.filter { it >= ' ' && it != '\u007F' }.uppercase().trim() }
            .filter { it.isNotEmpty() }
            .filter { eco == null || compactar(it) != eco }
            .filter { !it.startsWith("SEARCHING") && !it.startsWith("BUS INIT: OK") }

        if (linhas.any { regexLinhaMultiFrame.containsMatchIn(compactar(it)) }) {
            val dados = linhas
                .filter { !regexTamanhoCan.matches(compactar(it)) }
                .map { regexLinhaMultiFrame.replace(compactar(it), "") }
            linhas = listOf(dados.joinToString(""))
        }
        return linhas
    }

    private fun compactar(texto: String) = texto.filter { !it.isWhitespace() }

    /** Converte "1AF8" em [0x1A, 0xF8]; ignora um nibble sobrando e para no primeiro caractere não hexadecimal. */
    private fun hexParaBytes(hex: String): List<Int> {
        val validos = hex.takeWhile { it in '0'..'9' || it in 'A'..'F' }
        return validos.chunked(2).filter { it.length == 2 }.map { it.toInt(16) }
    }

    private inline fun <T, R> RespostaObd<T>.mapear(transformar: (T) -> R): RespostaObd<R> = when (this) {
        is RespostaObd.Ok -> RespostaObd.Ok(transformar(valor))
        RespostaObd.SemDados -> RespostaObd.SemDados
        RespostaObd.ComandoInvalido -> RespostaObd.ComandoInvalido
        RespostaObd.SemConexao -> RespostaObd.SemConexao
        is RespostaObd.Invalido -> this
    }
}
