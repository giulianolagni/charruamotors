package com.charruamotors.obd

import java.util.Locale
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Fase do Teste de Bateria e Alternador que o [ObdClientFake] simula no `ATRV`. */
enum class FaseBateriaSimulada {
    /** Ignição ligada, motor desligado: ~12,5 V, com a carga superficial se dissipando. */
    REPOUSO,

    /** Roteiro da partida: repouso → vale de ~10,3 V (com um `LV RESET`) → compressões → motor pega (~14,2 V). */
    PARTIDA,

    /** Motor em marcha lenta: alternador carregando a ~14,2 V. */
    MARCHA_LENTA,

    /** ~2.500 RPM com faróis, ventilação e desembaçador ligados: ~14,1 V. */
    CARGA,
}

/**
 * Curva de tensão do `ATRV` para o Teste de Bateria no modo simulado. O tempo de cada fase conta a
 * partir de [definir] pelo [relogioMs] (injetável nos testes). Sem fase, o alternador do carro
 * em trânsito (~14 V), como na telemetria.
 */
internal class SimuladorBateria(
    private val random: Random,
    private val relogioMs: () -> Long,
    /** Uma resposta `LV RESET` no vale da partida, para demonstrar a resiliência do teste. */
    private val lvResetNaPartida: Boolean = true,
) {
    @Volatile private var fase: FaseBateriaSimulada? = null
    @Volatile private var inicioFaseMs = 0L
    @Volatile private var lvResetEmitido = false

    fun definir(nova: FaseBateriaSimulada?) {
        fase = nova
        inicioFaseMs = relogioMs()
        lvResetEmitido = false
    }

    /** Resposta crua ao `ATRV` (`12.5V\r\r`), ou `LV RESET` uma vez no vale da partida. */
    fun responderAtrv(): String {
        val t = (relogioMs() - inicioFaseMs).toDouble()
        val volts = when (fase) {
            null -> 14.0 + ruido(0.3)
            FaseBateriaSimulada.REPOUSO -> 12.5 + 0.12 * exp(-t / 700.0) + ruido(0.03)
            FaseBateriaSimulada.PARTIDA -> curvaPartida(t) ?: return "LV RESET\r\r"
            FaseBateriaSimulada.MARCHA_LENTA -> 14.2 + ruido(0.06)
            FaseBateriaSimulada.CARGA -> 14.12 + ruido(0.08)
        }
        return String.format(Locale.US, "%.1fV\r\r", volts)
    }

    /** Volts em [t] ms desde o início da partida; null = o adaptador reiniciou nesta amostra. */
    private fun curvaPartida(t: Double): Double? = when {
        t < INICIO_PARTIDA_MS -> 12.5 + ruido(0.03) // o motorista ainda vai virar a chave
        t < FIM_PICO_MS -> 12.5 - (12.5 - VALE) * (t - INICIO_PARTIDA_MS) / (FIM_PICO_MS - INICIO_PARTIDA_MS)
        t < FIM_VALE_MS -> if (lvResetNaPartida && !lvResetEmitido) {
            lvResetEmitido = true
            null
        } else {
            VALE + ruido(0.04)
        }
        // Motor de arranque girando: cada compressão derruba a tensão (10,5–11,0 V).
        t < FIM_ARRANQUE_MS -> 10.75 + 0.25 * sin(2 * PI * (t - FIM_VALE_MS) / 180.0) + ruido(0.04)
        // Motor pegou: o alternador assume.
        t < MOTOR_PEGOU_MS -> 10.9 + (14.2 - 10.9) * (t - FIM_ARRANQUE_MS) / (MOTOR_PEGOU_MS - FIM_ARRANQUE_MS)
        else -> 14.2 + ruido(0.06)
    }

    private fun ruido(amplitude: Double) = random.nextDouble(-amplitude, amplitude)

    companion object {
        const val VALE = 10.3
        const val INICIO_PARTIDA_MS = 2_000.0
        const val FIM_PICO_MS = 2_150.0
        const val FIM_VALE_MS = 2_300.0
        const val FIM_ARRANQUE_MS = 3_500.0
        const val MOTOR_PEGOU_MS = 4_100.0
    }
}

/**
 * ECU simulada para o Terminal ELM327 e a Varredura de PIDs no modo simulado: um ELM327 v1.5 numa
 * Hilux SW4 D-4D com motor (`7E0`), câmbio automático (`7E1`), ABS/VSC (`7B0`) e painel/4WD
 * (`7C0`) que respondem ao serviço 21 (KWP2000 *readDataByLocalIdentifier*), sem módulo no `7E2`.
 * Respostas no formato real da sessão (`ATS0` sem espaços, `ATH0` sem cabeçalhos, multi-frame CAN
 * `0:`/`1:` acima de 7 bytes). Como num ELM327 de verdade, a resposta do ABS (`7B8`) e do painel
 * (`7C8`) só passa com o filtro de recepção ajustado (`ATCRA 7B8`); sem ele, `NO DATA`.
 */
internal class EcuSimulada(private val random: Random) {

    /** Cabeçalho configurado com `ATSH` (o funcional `7DF` depois de um reset). */
    @Volatile var cabecalho: String = CABECALHO_FUNCIONAL

    /** Filtro de recepção do `ATCRA` (null = padrão, respostas `7E8`–`7EF`). */
    @Volatile var filtroRecepcao: String? = null

    /**
     * Resposta crua a [compacto] (comando em maiúsculas sem espaços). [leitura] dá os valores
     * atuais da simulação (RPM, velocidade...) e [codigos] os DTCs armazenados.
     */
    fun responder(compacto: String, leitura: () -> Map<String, Double>, codigos: () -> List<String>): String {
        if (compacto.startsWith("AT")) return responderAt(compacto) + FIM
        if (compacto.isEmpty() || compacto.any { it !in '0'..'9' && it !in 'A'..'F' }) return "?$FIM"
        // Sufixo de resposta única (010C1): o pedido é o mesmo.
        val pedido = if (compacto.length % 2 == 1 && compacto.endsWith("1")) compacto.dropLast(1) else compacto
        if (pedido.length % 2 == 1) return "?$FIM"
        return when (val dados = responderEcu(pedido, leitura, codigos)) {
            null -> "NO DATA$FIM"
            else -> formatarCan(dados) + FIM
        }
    }

    private fun responderAt(c: String): String = when {
        c == "ATZ" || c == "ATWS" || c == "ATD" -> {
            cabecalho = CABECALHO_FUNCIONAL
            filtroRecepcao = null
            if (c == "ATD") "OK" else "\r\rELM327 v1.5"
        }
        c == "ATI" -> "ELM327 v1.5"
        c == "AT@1" -> "OBDII to RS232 Interpreter"
        c == "ATDP" -> "AUTO, ISO 15765-4 (CAN 11/500)"
        c == "ATDPN" -> "A6"
        c.startsWith("ATCRA") -> {
            val filtro = c.removePrefix("ATCRA")
            when {
                filtro.isEmpty() -> {
                    filtroRecepcao = null
                    "OK"
                }
                filtro.length == 3 && filtro.all { it in '0'..'9' || it in 'A'..'F' } -> {
                    filtroRecepcao = filtro
                    "OK"
                }
                else -> "?"
            }
        }
        c.startsWith("ATSH") -> {
            val novo = c.removePrefix("ATSH")
            if ((novo.length == 3 || novo.length == 6) && novo.all { it in '0'..'9' || it in 'A'..'F' }) {
                cabecalho = novo
                "OK"
            } else {
                "?"
            }
        }
        else -> "OK"
    }

    /** Bytes da resposta positiva, `7F ...` na negativa ou null (`NO DATA`: ninguém no barramento respondeu). */
    private fun responderEcu(pedido: String, leitura: () -> Map<String, Double>, codigos: () -> List<String>): List<Int>? {
        val servico = pedido.take(2).toInt(16)
        val motor = cabecalho == CABECALHO_FUNCIONAL || cabecalho == MOTOR
        val chassi = cabecalho in MODULOS_CHASSI
        return when {
            cabecalho == MODULO_AUSENTE -> null
            chassi && filtroRecepcao != respostaDe(cabecalho) -> null // respondeu em 7B8/7C8, mas o ELM327 filtrou
            chassi && servico == 0x21 -> servico21(pedido, leitura())
            chassi -> listOf(0x7F, servico, NRC_SERVICO_NAO_SUPORTADO)
            servico == 0x01 && motor -> modo01(pedido, leitura())
            servico == 0x03 && motor -> listOf(0x43, codigos().size) + codigos().flatMap(::bytesDtc)
            servico == 0x13 && cabecalho == MOTOR -> listOf(0x53, codigos().size) + codigos().flatMap(::bytesDtc)
            servico == 0x21 && (cabecalho == MOTOR || cabecalho == CAMBIO) -> servico21(pedido, leitura())
            servico == 0x21 -> null // pedido funcional: as centrais Toyota só atendem o endereço físico
            cabecalho == MOTOR || cabecalho == CAMBIO || cabecalho == CABECALHO_FUNCIONAL ->
                listOf(0x7F, servico, NRC_SERVICO_NAO_SUPORTADO)
            else -> null
        }
    }

    private fun modo01(pedido: String, valores: Map<String, Double>): List<Int>? {
        if (pedido.length != 4) return listOf(0x7F, 0x01, NRC_FORMATO_INVALIDO)
        val pid = pedido.substring(2).toInt(16)
        val dados: List<Int> = when (pid) {
            0x00 -> listOf(0xBE, 0x3F, 0xB8, 0x13)
            0x04 -> listOf(((valores["0104"] ?: 20.0) * 255 / 100).roundToInt())
            0x05 -> listOf(((valores["0105"] ?: 85.0) + 40).roundToInt())
            0x0B -> listOf((valores["010B"] ?: 35.0).roundToInt())
            0x0C -> ((valores["010C"] ?: 800.0) * 4).roundToInt().let { listOf(it shr 8, it and 0xFF) }
            0x0D -> listOf((valores["010D"] ?: 0.0).roundToInt())
            0x0F -> listOf(((valores["010F"] ?: 30.0) + 40).roundToInt())
            else -> return null
        }
        return listOf(0x41, pid) + dados.map { it.coerceIn(0, 0xFF) }
    }

    /** Blocos de identificadores locais da central (os demais: `7F 21 31` requestOutOfRange). */
    private fun servico21(pedido: String, valores: Map<String, Double>): List<Int> {
        if (pedido.length != 4) return listOf(0x7F, 0x21, NRC_FORMATO_INVALIDO)
        val id = pedido.substring(2).toInt(16)
        val dados = when (cabecalho) {
            MOTOR -> blocoMotor(id, valores)
            CAMBIO -> blocoCambio(id, valores)
            ABS_VSC -> blocoAbs(id, valores)
            else -> blocoPainel(id)
        }
        return dados?.let { listOf(0x61, id) + it } ?: listOf(0x7F, 0x21, NRC_FORA_DA_FAIXA)
    }

    private fun blocoMotor(id: Int, valores: Map<String, Double>): List<Int>? = when (id) {
        // Correção dos bicos 1–4 (signed short ÷ 512, mm³/st): +0,45 / +0,44 / +0,66 / 0,00 (capturas reais).
        0xAD -> listOf(0x00, 0xE6, 0x00, 0xE1, 0x01, 0x52, 0x00, 0x00, 0x00, 0x00, 0x00)
        // Tensão da ECU (A*256+B)/1000 V: 14,18 V; barômetro em kPa: 92 (0x5C).
        0x42 -> listOf(0x37, 0x63)
        0x33 -> listOf(0x5C)
        0x01 -> {
            val rpm = ((valores["010C"] ?: 780.0) / 2).roundToInt().coerceIn(0, 0xFF)
            val temp = ((valores["0105"] ?: 85.0) + 40).roundToInt().coerceIn(0, 0xFF)
            listOf(0x00, temp, rpm, 0x3C, 0x80, 0x12, 0x00, 0x8B, 0x64, 0x1F, 0x00, 0x00) + variacao(4)
        }
        0x0C -> ((valores["010C"] ?: 780.0) * 4).roundToInt().let { listOf(it shr 8, it and 0xFF) }
        0x05 -> listOf(((valores["0105"] ?: 85.0) + 40).roundToInt().coerceIn(0, 0xFF))
        // Pedal do acelerador (APP D/E, % = A*100/255): o E lê ~16 pontos acima do D, como no D-4D.
        0x49 -> listOf(percentual(pedal(valores)))
        0x4A -> listOf(percentual((pedal(valores) + DESVIO_APP_E).coerceAtMost(100.0)))
        in IDS_MOTOR -> blocoFixo(id, 2 + id % 9)
        else -> null
    }

    private fun blocoCambio(id: Int, valores: Map<String, Double>): List<Int>? = when (id) {
        // Marcha engatada (A = 1..5; 0xFF parado) seguida de bytes de estado.
        0x30 -> listOf(marchaSimulada(valores["010D"] ?: 0.0), 0x00, 0x1C)
        // 21D9 (11 bytes): alavanca em D (bit 2 de b3) e ATF em b4:b5 (°C = (raw-10880)/240; ~26 °C = 0x42E0).
        0xD9 -> listOf(0x00, 0x00, 0x00, 0x04, 0x42, 0xE0 + random.nextInt(0, 0x20), 0x00, 0x00, 0x00, 0x00, 0x00)
        in IDS_CAMBIO -> blocoFixo(id, 3 + id % 5)
        else -> null
    }

    /** ABS/VSC: velocidades das 4 rodas (km/h), estado do VSC (A = 0 normal) e blocos de calibração. */
    private fun blocoAbs(id: Int, valores: Map<String, Double>): List<Int>? = when (id) {
        0x01 -> {
            val v = (valores["010D"] ?: 0.0).roundToInt().coerceIn(0, 0xFF)
            listOf(v, v, (v + random.nextInt(0, 2)).coerceAtMost(0xFF), v, 0x00, 0x00, 0x80, 0x00)
        }
        0x03 -> listOf(VSC_NORMAL, 0x00, 0x00, 0x02)
        in IDS_ABS -> blocoFixo(id, 3 + id % 6)
        else -> null
    }

    /** Painel/4WD: modo de tração (A = 0, 4x2 H2) e blocos da carroceria. */
    private fun blocoPainel(id: Int): List<Int>? = when (id) {
        0x01 -> listOf(TRACAO_H2, 0x00, 0x00, 0x00)
        in IDS_PAINEL -> blocoFixo(id, 2 + id % 7)
        else -> null
    }

    private fun pedal(valores: Map<String, Double>) = valores["0149"] ?: PEDAL_REPOUSO

    private fun percentual(p: Double) = (p * 255 / 100).roundToInt().coerceIn(0, 0xFF)

    /** Bytes estáveis por identificador (mesma resposta a cada leitura) com o último oscilando. */
    private fun blocoFixo(id: Int, tamanho: Int): List<Int> {
        val semente = Random(id * 31 + cabecalho.hashCode())
        return List(tamanho - 1) { semente.nextInt(0, 0x100) } + variacao(1)
    }

    private fun variacao(n: Int) = List(n) { random.nextInt(0, 0x100) }

    private fun bytesDtc(codigo: String): List<Int> {
        val sistema = "PCBU".indexOf(codigo.first()).coerceAtLeast(0)
        val valor = (sistema shl 14) or codigo.drop(1).toInt(16)
        return listOf(valor shr 8, valor and 0xFF)
    }

    /** Até 7 bytes num quadro único; acima disso, o multi-frame do ELM327 com `ATH0` (`00E`, `0:`, `1:`...). */
    private fun formatarCan(bytes: List<Int>): String {
        fun hex(lista: List<Int>) = lista.joinToString("") { "%02X".format(it) }
        if (bytes.size <= 7) return hex(bytes)
        val linhas = mutableListOf("%03X".format(bytes.size), "0:" + hex(bytes.take(6)))
        bytes.drop(6).chunked(7).forEachIndexed { i, bloco -> linhas += "${"%X".format((i + 1) % 16)}:" + hex(bloco) }
        return linhas.joinToString("\r")
    }

    companion object {
        const val CABECALHO_FUNCIONAL = "7DF"
        const val MOTOR = "7E0"
        const val CAMBIO = "7E1"
        const val MODULO_AUSENTE = "7E2"
        const val ABS_VSC = "7B0"
        const val PAINEL_4WD = "7C0"
        private val MODULOS_CHASSI = setOf(ABS_VSC, PAINEL_4WD)
        private const val FIM = "\r\r"
        private const val NRC_SERVICO_NAO_SUPORTADO = 0x11
        private const val NRC_FORMATO_INVALIDO = 0x13
        private const val NRC_FORA_DA_FAIXA = 0x31

        /** Identificadores do serviço 21 que o motor simulado responde (além de 01, 05, 0C, 33, 42 e AD). */
        val IDS_MOTOR: Set<Int> = setOf(0x02, 0x03, 0x04, 0x06, 0x07, 0x08, 0x10, 0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x30, 0x31, 0x40, 0x51)

        /** Identificadores do serviço 21 que o câmbio simulado responde (além do 82 ATF, 30 marcha e D9 alavanca). */
        val IDS_CAMBIO: Set<Int> = setOf(0x01, 0x02, 0x83, 0x91)

        /** Identificadores do serviço 21 do ABS/VSC simulado (além do 01 rodas e 03 VSC). */
        val IDS_ABS: Set<Int> = setOf(0x02, 0x05, 0x10, 0x21)

        /** Identificadores do serviço 21 do painel/4WD simulado (além do 01 tração). */
        val IDS_PAINEL: Set<Int> = setOf(0x21, 0x22, 0x40)

        const val ALAVANCA_D = 3
        const val VSC_NORMAL = 0
        const val TRACAO_H2 = 0
        const val DESVIO_APP_E = 16.0
        private const val PEDAL_REPOUSO = 0.0

        /** Endereço de resposta de um módulo (`7B0` → `7B8`). */
        private fun respostaDe(cabecalho: String) = "%03X".format(cabecalho.toInt(16) + 8)

        /** Marcha do A750F (5 velocidades) pela velocidade; 0xFF parado. */
        fun marchaSimulada(velocidadeKmh: Double): Int = when {
            velocidadeKmh < 1 -> MARCHA_PARADO
            velocidadeKmh < 18 -> 1
            velocidadeKmh < 32 -> 2
            velocidadeKmh < 48 -> 3
            velocidadeKmh < 66 -> 4
            else -> 5
        }

        /** `2130` devolve 0xFF com o veículo parado (0 km/h). */
        const val MARCHA_PARADO = 0xFF
    }
}
