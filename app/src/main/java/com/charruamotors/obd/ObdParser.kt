package com.charruamotors.obd

import com.charruamotors.obd.pid.DefinicaoPid

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
    private val regexNumeroAt = Regex("^(-?\\d{1,6}(?:[.,]\\d+)?)[A-Z%]*$")

    /** 17 caracteres; o padrão ISO 3779 exclui I, O e Q (confundíveis com 1 e 0). */
    private val regexVin = Regex("^[A-HJ-NPR-Z0-9]{17}$")
    private const val TAMANHO_VIN = 17

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

    // ---------------------------------------------------------------- PID genérico (catálogo)

    /**
     * Interpreta a resposta de qualquer PID do catálogo: extrai os [DefinicaoPid.bytes] bytes após
     * `41 <pid>` (ou o número em texto, nos comandos AT), aplica a fórmula e valida a faixa.
     * PIDs estendidos (modos 21/22) seguem [parsePidEstendido].
     */
    fun parsePid(
        bruto: String,
        definicao: DefinicaoPid,
        comando: String? = definicao.estendida?.comando ?: definicao.comando,
    ): RespostaObd<Double> {
        if (definicao.ehEstendido) return parsePidEstendido(bruto, definicao, comando)
        if (definicao.ehComandoAt) {
            val linhas = linhasLimpas(bruto, comando)
            for (linha in linhas) {
                val numero = regexNumeroAt.matchEntire(compactar(linha))?.groupValues?.get(1) ?: continue
                val x = numero.replace(',', '.').toDoubleOrNull() ?: continue
                return validarFaixa(definicao.converter(x = x), definicao, bruto)
            }
            return classificarErro(linhas, bruto)
        }
        return when (val r = bytesDaResposta(bruto, "41${definicao.pidHex}", definicao.bytes, comando)) {
            is RespostaObd.Ok -> validarFaixa(definicao.converter(r.valor), definicao, bruto)
            else -> r.mapear { 0.0 }
        }
    }

    // ---------------------------------------------------------------- PIDs de montadora (modos 21 / 22)

    /**
     * Resposta de um PID estendido: `61 <id> A B...` (serviço 21) ou `62 <DID> A B...` (UDS 22),
     * em quadro único ou multi-frame ISO-TP (`0:`, `1:`...). Resposta negativa `7F <serviço> <NRC>`
     * (ex.: `7F 22 31` requestOutOfRange) = [RespostaObd.SemDados]; o NRC `78` (responsePending)
     * é só um aviso de espera e a resposta positiva que vier depois vale.
     */
    fun parsePidEstendido(
        bruto: String,
        definicao: DefinicaoPid,
        comando: String? = definicao.estendida?.comando,
    ): RespostaObd<Double> {
        val requisicao = requireNotNull(definicao.estendida) { "${definicao.comando} não é um PID estendido" }
        val r = bytesDaResposta(bruto, requisicao.prefixoResposta, definicao.bytes, comando)
        if (r is RespostaObd.Ok) return validarFaixa(definicao.converter(r.valor), definicao, bruto)
        val negativas = linhasLimpas(bruto, comando).map(::compactar).filter { it.startsWith(requisicao.prefixoNegativa) }
        if (negativas.any { !it.startsWith(requisicao.prefixoNegativa + NRC_RESPOSTA_PENDENTE) }) return RespostaObd.SemDados
        return r.mapear { 0.0 }
    }

    /**
     * `ATDPN`: número do protocolo ativo (`A6` = automático, achou o 6). Devolve o dígito
     * hexadecimal (6 = CAN 11 bits 500 kbps, 8 = CAN 11 bits 250 kbps) ou null se ilegível.
     */
    fun parseProtocolo(bruto: String, comando: String? = "ATDPN"): Int? =
        linhasLimpas(bruto, comando)
            .map(::compactar)
            .firstNotNullOfOrNull { Regex("^A?([0-9A-C])$").matchEntire(it)?.groupValues?.get(1)?.toInt(16) }

    /** Protocolos CAN de 11 bits, os únicos em que o app usa cabeçalhos físicos (`ATSH 7E0`). */
    val PROTOCOLOS_CAN_11_BITS: Set<Int> = setOf(6, 8)

    private const val NRC_RESPOSTA_PENDENTE = "78"

    /**
     * `ATDP`: descrição do protocolo ativo (ex.: `AUTO, ISO 15765-4 (CAN 11/500)`), para o card da
     * ECU e o registro de campo; null se o adaptador não respondeu algo legível.
     */
    fun parseDescricaoProtocolo(bruto: String, comando: String? = "ATDP"): String? =
        linhasLimpas(bruto, comando).firstOrNull { linha ->
            erroElm(linha) == null && linha.any { it.isLetterOrDigit() } && linha != "OK"
        }

    // ---------------------------------------------------------------- respostas negativas (KWP2000 / UDS)

    /**
     * NRC da resposta negativa `7F <serviço> <NRC>` ao [servico] (ex.: `7F 01 11` ao `0100` → `"11"`).
     * Ignora o `78` (responsePending), que é só um aviso de espera; null se não houver negativa.
     */
    fun respostaNegativa(bruto: String, servico: String, comando: String? = null): String? {
        val prefixo = "7F" + servico.uppercase()
        return linhasLimpas(bruto, comando)
            .map(::compactar)
            .filter { it.startsWith(prefixo) && it.length >= prefixo.length + 2 }
            .map { it.substring(prefixo.length, prefixo.length + 2) }
            .firstOrNull { it != NRC_RESPOSTA_PENDENTE }
    }

    /** Nome padronizado (ISO 14229) do NRC, para o registro: `11` → `serviceNotSupported`. */
    fun descricaoNrc(nrc: String): String = NRCS[nrc.uppercase()] ?: "NRC $nrc"

    private val NRCS = mapOf(
        "10" to "generalReject",
        "11" to "serviceNotSupported",
        "12" to "subFunctionNotSupported",
        "13" to "incorrectMessageLength",
        "21" to "busyRepeatRequest",
        "22" to "conditionsNotCorrect",
        "31" to "requestOutOfRange",
        "33" to "securityAccessDenied",
        "78" to "responsePending",
    )

    /**
     * A ECU respondeu positivamente ao [comando] (`serviço + 0x40`)? No serviço 21 o identificador
     * também precisa bater (`2101` → `61 01 ...`).
     */
    fun respostaPositiva(bruto: String, comando: String): Boolean {
        val c = compactar(comando).uppercase()
        val servico = c.take(2).toIntOrNull(16) ?: return false
        val prefixo = "%02X".format(servico + 0x40) + if (c.startsWith("21")) c.drop(2).take(2) else ""
        return linhasLimpas(bruto, comando).any { compactar(it).startsWith(prefixo) }
    }

    // ---------------------------------------------------------------- PIDs suportados (bitmasks)

    /**
     * `0100`, `0120`, `0140`...: bitmask de 32 bits dos PIDs suportados em `base+1 .. base+0x20`.
     * Devolve os números dos PIDs suportados, unindo as respostas de todas as ECUs que responderem.
     * Se o último PID da faixa (`base+0x20`) estiver no conjunto, a ECU suporta a próxima faixa.
     */
    fun parsePidsSuportados(
        bruto: String,
        base: Int = 0x00,
        comando: String? = "01%02X".format(base),
    ): RespostaObd<Set<Int>> {
        val linhas = linhasLimpas(bruto, comando)
        val regex = Regex("41%02X([0-9A-F]*)".format(base))
        var achou = false
        val suportados = sortedSetOf<Int>()
        for (linha in linhas) {
            val dados = regex.find(compactar(linha))?.groupValues?.get(1) ?: continue
            val bytes = hexParaBytes(dados)
            if (bytes.size < 4) continue
            achou = true
            suportados += decodificarBitmask(base, bytes.take(4))
        }
        if (achou) return RespostaObd.Ok(suportados)
        if (linhas.any { regex.containsMatchIn(compactar(it)) }) return RespostaObd.Invalido(bruto)
        return classificarErro(linhas, bruto)
    }

    /** Bit 7 do 1º byte = PID `base+1` ... bit 0 do 4º byte = PID `base+0x20` (SAE J1979). */
    fun decodificarBitmask(base: Int, bytes: List<Int>): Set<Int> {
        require(bytes.size == 4) { "O bitmask de PIDs tem 4 bytes" }
        val mascara = bytes.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        return (0 until 32).filter { bit -> (mascara shr (31 - bit)) and 1L == 1L }
            .map { bit -> base + bit + 1 }
            .toSet()
    }

    // ---------------------------------------------------------------- Freeze Frame (modo 02)

    /**
     * `02 <pid> <quadro>`: valor do PID congelado no momento da falha. Resposta `42 <pid> <quadro> A B...`.
     * A [definicao] é a mesma do modo 01 (mesma fórmula e faixa).
     */
    fun parseFreezeFrame(
        bruto: String,
        definicao: DefinicaoPid,
        quadro: Int = 0,
        comando: String? = comandoFreezeFrame(definicao, quadro),
    ): RespostaObd<Double> {
        require(!definicao.ehComandoAt) { "Comandos AT não existem no Freeze Frame" }
        val prefixo = "42${definicao.pidHex}%02X".format(quadro)
        return when (val r = bytesDaResposta(bruto, prefixo, definicao.bytes, comando)) {
            is RespostaObd.Ok -> validarFaixa(definicao.converter(r.valor), definicao, bruto)
            else -> r.mapear { 0.0 }
        }
    }

    /** `020200`: DTC que disparou o Freeze Frame. `Ok(null)` = nenhum quadro armazenado (`00 00`). */
    fun parseDtcFreezeFrame(
        bruto: String,
        quadro: Int = 0,
        comando: String? = "0202%02X".format(quadro),
    ): RespostaObd<String?> =
        bytesDaResposta(bruto, "4202%02X".format(quadro), 2, comando).mapear { (b1, b2) ->
            if (b1 == 0 && b2 == 0) null else decodificarDtc(b1, b2)
        }

    /** Comando do modo 02 para o PID da [definicao] (`010C` → `020C00`). */
    fun comandoFreezeFrame(definicao: DefinicaoPid, quadro: Int = 0): String =
        "02${definicao.pidHex}%02X".format(quadro)

    // ---------------------------------------------------------------- VIN (modo 09, PID 02)

    /**
     * `0902`: VIN (chassi) com 17 caracteres ASCII.
     * - CAN (multi-frame): `49 02 01` + 17 bytes, distribuídos nas linhas `0:`, `1:`, `2:`;
     * - ISO 9141 / KWP: 5 linhas `49 02 <seq> b1 b2 b3 b4`, a primeira preenchida com `00`.
     */
    fun parseVin(bruto: String, comando: String? = "0902"): RespostaObd<String> {
        val linhas = linhasLimpas(bruto, comando)
        val partes = linhas.mapNotNull { linha ->
            val compacta = compactar(linha)
            val i = compacta.indexOf("4902")
            if (i < 0) null else hexParaBytes(compacta.substring(i + 4))
        }.filter { it.isNotEmpty() }
        if (partes.isEmpty()) return classificarErro(linhas, bruto)

        // O 1º byte de cada parte é a sequência (ou a quantidade de itens, no CAN): ordena e descarta.
        val texto = partes.sortedBy { it.first() }
            .flatMap { it.drop(1) }
            .filter { it in 0x21..0x7E }
            .map { it.toChar().uppercaseChar() }
            .filter { it.isLetterOrDigit() }
            .joinToString("")
        val vin = texto.take(TAMANHO_VIN)
        return if (regexVin.matches(vin)) RespostaObd.Ok(vin) else RespostaObd.Invalido(bruto)
    }

    // ---------------------------------------------------------------- DTCs (modos 03 e 04)

    /**
     * `03`: códigos de falha armazenados (confirmados). Resposta `43 ...`.
     * Em CAN o primeiro byte após `43` é a quantidade de códigos (total de bytes ímpar);
     * nos demais protocolos vêm apenas pares de bytes, preenchidos com `00 00`.
     */
    fun parseDtcs(bruto: String, comando: String? = "03"): RespostaObd<List<String>> =
        parseDtcsDoModo(bruto, "43", comando)

    /**
     * `07`: códigos pendentes — falhas detectadas no ciclo de condução atual/anterior que ainda
     * não foram confirmadas (a luz de injeção pode ainda não ter acendido). Resposta `47 ...`.
     */
    fun parseDtcsPendentes(bruto: String, comando: String? = "07"): RespostaObd<List<String>> =
        parseDtcsDoModo(bruto, "47", comando)

    /**
     * `0A`: códigos permanentes — não são apagados pelo modo 04; só a própria ECU os remove
     * depois de verificar que a falha não se repete. Resposta `4A ...`.
     */
    fun parseDtcsPermanentes(bruto: String, comando: String? = "0A"): RespostaObd<List<String>> =
        parseDtcsDoModo(bruto, "4A", comando)

    /** Formato comum dos modos 03/07/0A; [prefixoResposta] = `0x40 + modo`. */
    private fun parseDtcsDoModo(bruto: String, prefixoResposta: String, comando: String?): RespostaObd<List<String>> {
        val linhas = linhasLimpas(bruto, comando)
        val codigos = linkedSetOf<String>()
        var achouResposta = false
        for (linha in linhas) {
            val compacta = compactar(linha)
            if (!compacta.startsWith(prefixoResposta)) continue
            val bytes = hexParaBytes(compacta.substring(2))
            achouResposta = true
            val pares = if (bytes.size % 2 == 1) bytes.drop(1) else bytes
            pares.chunked(2).filter { it.size == 2 }.forEach { (b1, b2) ->
                if (b1 != 0 || b2 != 0) codigos += decodificarDtc(b1, b2)
            }
        }
        return if (achouResposta) RespostaObd.Ok(codigos.toList()) else classificarErro(linhas, bruto)
    }

    /**
     * `18 00 FF 00` (KWP2000 *readDiagnosticTroubleCodesByStatus*, Toyota M-OBD): resposta
     * `58 <quantidade> [DTC_alto DTC_baixo status]...`, com o DTC no mesmo formato SAE do modo 03.
     * Negativa `7F 18 xx` = [RespostaObd.SemDados] (serviço não suportado por esta ECU).
     */
    fun parseDtcsKwp(bruto: String, comando: String? = "1800FF00"): RespostaObd<List<String>> {
        val linhas = linhasLimpas(bruto, comando)
        val codigos = linkedSetOf<String>()
        var achouResposta = false
        for (linha in linhas) {
            val compacta = compactar(linha)
            if (!compacta.startsWith("58")) continue
            achouResposta = true
            hexParaBytes(compacta.substring(2)).drop(1).chunked(3).filter { it.size == 3 }.forEach { (b1, b2, _) ->
                if (b1 != 0 || b2 != 0) codigos += decodificarDtc(b1, b2)
            }
        }
        if (achouResposta) return RespostaObd.Ok(codigos.toList())
        if (respostaNegativa(bruto, "18", comando) != null) return RespostaObd.SemDados
        return classificarErro(linhas, bruto)
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
    private fun bytesDoPid(bruto: String, pid: String, quantidade: Int, comando: String?): RespostaObd<List<Int>> =
        bytesDaResposta(bruto, "41$pid", quantidade, comando)

    /** Procura `<prefixo> <bytes...>` (ex.: `410C`, `420C00`) em qualquer linha e devolve [quantidade] bytes. */
    private fun bytesDaResposta(bruto: String, prefixo: String, quantidade: Int, comando: String?): RespostaObd<List<Int>> {
        val linhas = linhasLimpas(bruto, comando)
        val regex = Regex("$prefixo([0-9A-F]*)")
        for (linha in linhas) {
            val dados = regex.find(compactar(linha))?.groupValues?.get(1) ?: continue
            val bytes = hexParaBytes(dados)
            if (bytes.size >= quantidade) return RespostaObd.Ok(bytes.take(quantidade))
        }
        // Havia "41 <pid>" mas truncado: resposta inválida, não "sem dados".
        if (linhas.any { regex.containsMatchIn(compactar(it)) }) return RespostaObd.Invalido(bruto)
        return classificarErro(linhas, bruto)
    }

    private fun validarFaixa(valor: Double, definicao: DefinicaoPid, bruto: String): RespostaObd<Double> =
        if (definicao.dentroDaFaixa(valor)) RespostaObd.Ok(valor) else RespostaObd.Invalido(bruto)

    private fun classificarErro(linhas: List<String>, bruto: String): RespostaObd<Nothing> {
        val texto = linhas.joinToString("\n")
        return when {
            ERROS_SEM_CONEXAO.any { it in texto } || regexBusInitErro.containsMatchIn(texto) ||
                linhas.any { regexErroInterno.matches(compactar(it)) } -> RespostaObd.SemConexao
            "NO DATA" in texto || "NODATA" in compactar(texto) -> RespostaObd.SemDados
            linhas.any { l -> compactar(l).let { it.isNotEmpty() && it.all { c -> c == '?' } } } ->
                RespostaObd.ComandoInvalido
            // STOPPED, BUFFER FULL, BUS BUSY, DATA ERROR, lixo: falha passageira, sem valor confiável.
            else -> RespostaObd.Invalido(bruto)
        }
    }

    // ---------------------------------------------------------------- mensagens do próprio ELM327

    /**
     * O ELM327 abortou o comando porque recebeu um caractere enquanto ainda processava o anterior
     * (`STOPPED`): o comando não foi executado e pode ser repetido.
     */
    fun interrompido(bruto: String): Boolean = linhasLimpas(bruto, null).any { it == "STOPPED" }

    /**
     * O adaptador reiniciou sozinho (`LV RESET` por queda de tensão na partida do motor, ou o
     * banner `ELM327 v...` no meio da sessão): perdeu `ATE0`/`ATL0`/`ATS0`/`ATH0` e o cabeçalho.
     */
    fun adaptadorReiniciou(bruto: String): Boolean =
        linhasLimpas(bruto, null).any { it.startsWith("LV RESET") || it.startsWith("ELM327") }

    /**
     * Texto da resposta sem bytes nulos e demais caracteres de controle (`\r`, `\n` viram espaço),
     * em maiúsculas e com espaços colapsados — para identificar o adaptador a partir de `ATZ`/`ATI`.
     */
    fun limparResposta(bruto: String): String =
        bruto.map { if (it < ' ' || it.code == 0x7F) ' ' else it }
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .trim()
            .uppercase()

    /**
     * Primeira mensagem de erro do ELM327 na resposta (`NO DATA`, `CAN ERROR`, `BUS INIT: ...ERROR`,
     * `?`, `STOPPED`...), para mensagens ao usuário e o registro de comunicação; null se não houver.
     */
    fun erroElm(bruto: String): String? {
        val linhas = bruto.replace('>', '\n').split('\r', '\n').map { l -> l.filter { it in ' '..'~' }.uppercase().trim() }
        return linhas.firstNotNullOfOrNull { linha ->
            when {
                linha.isEmpty() -> null
                linha.all { it == '?' } -> "?"
                regexBusInitErro.containsMatchIn(linha) -> "BUS INIT: ERROR"
                regexErroInterno.matches(compactar(linha)) -> compactar(linha)
                else -> MENSAGENS_ELM.firstOrNull { it in linha }
            }
        }
    }

    /** Erros que indicam que o adaptador não fala com a ECU (barramento, protocolo, ignição desligada). */
    private val ERROS_SEM_CONEXAO = listOf(
        "UNABLE TO CONNECT", "CAN ERROR", "BUS ERROR", "FB ERROR", "LV RESET", "ACT ALERT", "LP ALERT",
    )

    /** Todas as mensagens de erro/estado documentadas do ELM327 (datasheet v2.x). */
    private val MENSAGENS_ELM = ERROS_SEM_CONEXAO + listOf(
        "NO DATA", "STOPPED", "BUFFER FULL", "BUS BUSY", "<DATA ERROR", "DATA ERROR", "<RX ERROR", "RX ERROR",
    )

    /** `ERR94` e afins: erro interno do ELM327 (o 94 é um erro fatal de CAN que reinicia o chip). */
    private val regexErroInterno = Regex("^ERR\\d{2}$")

    /** `SEARCHING...` e `BUS INIT: ...OK` são só progresso da busca de protocolo (linha K faz 5 baud). */
    private val regexProgresso = Regex("^(SEARCHING\\.*|BUS ?INIT:[ .]*OK)\\s*")

    /**
     * Quebra a resposta em linhas "limpas": maiúsculas, só ASCII imprimível, sem o prompt `>`, sem o
     * eco do [comando], sem `SEARCHING...`/`BUS INIT: ...OK`. Respostas multi-frame CAN (`0:`, `1:`)
     * são unidas. Um quadro marcado com `<DATA ERROR`/`<RX ERROR` (checksum ou recepção ruim) vira só
     * a marca: os bytes dele não são confiáveis.
     */
    internal fun linhasLimpas(bruto: String, comando: String?): List<String> {
        val eco = comando?.let(::compactar)?.uppercase()
        var linhas = bruto
            .replace('>', '\n')
            .split('\r', '\n')
            .map { l -> regexProgresso.replace(l.filter { it in ' '..'~' }.uppercase().trim(), "") }
            .map { l -> if ('<' in l) l.substringAfter('<').trim() else l }
            .filter { it.isNotEmpty() }
            .filter { eco == null || compactar(it) != eco }

        if (linhas.any { regexLinhaMultiFrame.containsMatchIn(compactar(it)) }) {
            if (linhas.any { erroElm(it) != null }) return linhas.filter { erroElm(it) != null }
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
