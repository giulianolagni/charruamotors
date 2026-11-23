package com.charruamotors.obd.log

import com.charruamotors.domain.calculo.ComputadorBordo
import com.charruamotors.domain.calculo.ParametrosMotor
import com.charruamotors.domain.calculo.ResumoViagem
import com.charruamotors.domain.calculo.TelemetriaCalculada
import com.charruamotors.obd.DtcLido
import com.charruamotors.obd.ValoresSensores
import com.charruamotors.obd.pid.Pids
import java.text.Normalizer
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Mínimo e máximo de uma grandeza ao longo da sessão (null = nunca lida). */
class Extremos {
    var minimo: Double? = null
        private set
    var maximo: Double? = null
        private set

    fun registrar(valor: Double?) {
        if (valor == null || !valor.isFinite()) return
        minimo = minimo?.coerceAtMost(valor) ?: valor
        maximo = maximo?.coerceAtLeast(valor) ?: valor
    }
}

/**
 * Resumo estatístico da viagem, alimentado por toda amostra da telemetria: RPM, velocidade,
 * MAP/turbo, temperatura do motor, pressão do Common Rail e tensão da bateria. Nos modos 21/22 os
 * espelhos usam o mesmo comando canônico (`010C`...), então entram aqui sem tradução.
 */
class EstatisticasViagem(
    /** Combustível e calibração do veículo para o consumo via MAF; null = sem resumo de consumo. */
    private val motor: ParametrosMotor? = null,
) {
    var amostras: Int = 0
        private set

    private val bordo = ComputadorBordo()

    /** Distância e litros integrados da viagem (v1.7); a média só existe com distância e consumo suficientes. */
    val consumo: ResumoViagem get() = bordo.resumo
    val rpm = Extremos()
    val velocidade = Extremos()
    val map = Extremos()
    val temperaturaMotor = Extremos()
    val rail = Extremos()
    val tensao = Extremos()
    val temperaturaAtf = Extremos()

    fun registrar(dados: ValoresSensores, instanteMs: Long? = null) {
        amostras++
        if (motor != null && instanteMs != null) {
            bordo.registrar(TelemetriaCalculada.consumoInstantaneo(dados, motor), dados[Pids.VELOCIDADE], instanteMs)
        }
        rpm.registrar(dados[Pids.RPM])
        velocidade.registrar(dados[Pids.VELOCIDADE])
        map.registrar(dados[Pids.PRESSAO_COLETOR])
        temperaturaMotor.registrar(dados[Pids.TEMPERATURA_MOTOR])
        rail.registrar(dados[Pids.PRESSAO_RAIL])
        tensao.registrar(dados[Pids.TENSAO_BATERIA])
        temperaturaAtf.registrar(COMANDOS_ATF.firstNotNullOfOrNull { c -> dados[c]?.takeIf { it.isFinite() } })
    }
}

/** Chaves do ATF (câmbio `7E1` ou espelho no motor `7E0`), na ordem de preferência. */
private val COMANDOS_ATF = listOf("TOYOTA_ATF_CAMBIO", "TOYOTA_ATF", "TOYOTA_ATF_ECM")

/** Dados fixos do cabeçalho do relatório, conhecidos no início da sessão. */
data class CabecalhoSessao(
    /** Ex.: `Toyota SW4 2015`; null = nenhum veículo vinculado. */
    val veiculo: String?,
    /** Trecho do nome dos arquivos (ex.: `Toyota SW4`); null = `SemVeiculo`. */
    val veiculoArquivo: String?,
    val placa: String?,
    /** Como a sessão começou (ex.: `Manual (botão Conectar)`). */
    val modoInicio: String,
    /** Scanner usado (nome e endereço). */
    val scanner: String?,
    val inicioMs: Long,
    /** Sessão gravada com o scanner simulado (Modo Simulado): marca o nome dos arquivos e o cabeçalho. */
    val simulado: Boolean = false,
    /** Combustível/calibração do veículo, para o consumo médio do resumo; null = sem veículo. */
    val motor: ParametrosMotor? = null,
)

/** Códigos de falha lidos na sessão; cada lista null = modo não lido / não suportado pela ECU. */
data class DtcsSessao(
    val confirmados: List<DtcLido>?,
    val pendentes: List<DtcLido>?,
    val permanentes: List<DtcLido>?,
)

/** Tudo o que o relatório `.txt` mostra antes do log bruto. */
data class ResumoSessao(
    val cabecalho: CabecalhoSessao,
    val fimMs: Long,
    val conectou: Boolean,
    val motivoFim: String,
    val protocolo: String?,
    val camada: String?,
    val protocoloForcado: String?,
    val tensaoInicial: Double?,
    val dtcs: DtcsSessao?,
    val estatisticas: EstatisticasViagem,
    /** Nome do CSV de telemetria desta sessão; null = não gerado. */
    val arquivoCsv: String?,
    val amostrasCsv: Int,
    /** Por que o CSV não saiu (ou saiu incompleto), se for o caso. */
    val avisoCsv: String?,
)

/**
 * Relatório legível da sessão (`CharruaOBD_[SIMULADO_]YYYYMMDD_HHMMSS_<Veiculo>.txt`): cabeçalho, códigos de
 * falha, resumo estatístico da viagem e, depois, o log bruto completo (escrito pelo
 * [GravadorLogsSessao]). Números no formato brasileiro (`12,6 V`, `3.420 rpm`).
 */
object RelatorioSessaoTxt {

    const val TITULO_LOG_BRUTO = "LOG BRUTO DE COMUNICAÇÃO E EVENTOS"
    private const val LINHA = "=================================================================="
    private const val PRESSAO_ATMOSFERICA_KPA = 101.3

    private val localeBr: Locale = Locale.forLanguageTag("pt-BR")
    private val formatoNome = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    private val formatoDataHora = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")

    /**
     * `CharruaOBD_20261122_143005_Toyota_SW4` (sem extensão); no Modo Simulado,
     * `CharruaOBD_SIMULADO_20261122_143005_Toyota_SW4`.
     */
    fun nomeBase(inicioMs: Long, veiculo: String?, zona: ZoneId = ZoneId.systemDefault(), simulado: Boolean = false): String {
        val marca = if (simulado) "${OrganizadorLogs.MARCA_SIMULADO}_" else ""
        return "CharruaOBD_$marca${formatoNome.format(Instant.ofEpochMilli(inicioMs).atZone(zona))}_${trechoArquivo(veiculo)}"
    }

    /** Sem acentos, espaços viram `_`, só letras/dígitos/`_`/`-`; vazio = `SemVeiculo`. */
    fun trechoArquivo(texto: String?): String {
        val semAcento = Normalizer.normalize(texto.orEmpty(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        val limpo = semAcento.replace(Regex("[^A-Za-z0-9-]+"), "_").trim('_').take(40).trimEnd('_')
        return limpo.ifEmpty { "SemVeiculo" }
    }

    fun escreverResumo(r: ResumoSessao, saida: Appendable, zona: ZoneId = ZoneId.systemDefault()) {
        val c = r.cabecalho
        fun campo(rotulo: String, valor: String) {
            saida.append("  ").append(rotulo.padEnd(26, '.')).append(": ").append(valor).append('\n')
        }
        saida.append(LINHA).append('\n')
        saida.append("  CHARRUA OBD PRO — RELATÓRIO DA SESSÃO OBD2\n")
        saida.append(LINHA).append('\n')
        campo("Modo", if (c.simulado) "SIMULADO" else "REAL")
        campo("Veículo", c.veiculo ?: "não vinculado")
        campo("Placa", c.placa ?: "—")
        campo("Modo de início", c.modoInicio)
        campo("Scanner", c.scanner ?: "—")
        campo("Início", dataHora(c.inicioMs, zona))
        campo("Fim", dataHora(r.fimMs, zona))
        campo("Duração", duracao(r.fimMs - c.inicioMs))
        campo("Resultado", if (r.conectou) "Conectado" else "Falha no handshake (não conectou)")
        campo("Encerramento", r.motivoFim)
        campo("Protocolo", r.protocolo ?: "—")
        campo("Camada", r.camada ?: "—")
        r.protocoloForcado?.let { campo("Protocolo forçado", it) }
        campo("Tensão inicial", r.tensaoInicial?.let { "${numero(it, 1)} V" } ?: "—")
        campo(
            "Telemetria (CSV)",
            when {
                r.arquivoCsv != null -> "${r.arquivoCsv} (${numero(r.amostrasCsv.toDouble(), 0)} amostras)"
                else -> "não gerado"
            },
        )
        r.avisoCsv?.let { campo("Aviso do CSV", it) }

        saida.append('\n').append("CÓDIGOS DE FALHA (DTCs)\n")
        val d = r.dtcs
        if (d == null) {
            saida.append("  Não lidos nesta sessão").append(if (r.conectou) " (a ECU não respondeu à leitura).\n" else ".\n")
        } else {
            listaDtcs("Confirmados (modo 03)", d.confirmados, saida)
            listaDtcs("Pendentes (modo 07)", d.pendentes, saida)
            listaDtcs("Permanentes (modo 0A)", d.permanentes, saida)
        }

        saida.append('\n').append("RESUMO DA VIAGEM\n")
        val e = r.estatisticas
        if (e.amostras == 0) {
            saida.append("  Nenhuma leitura de telemetria nesta sessão.\n")
        } else {
            campo("Amostras de telemetria", numero(e.amostras.toDouble(), 0))
            campo("RPM máximo", e.rpm.maximo?.let { "${numero(it, 0)} rpm" } ?: "—")
            campo("Velocidade máxima", e.velocidade.maximo?.let { "${numero(it, 0)} km/h" } ?: "—")
            campo("Pico de MAP/Turbo", e.map.maximo?.let(::descreverMap) ?: "—")
            campo("Temperatura máx. do motor", e.temperaturaMotor.maximo?.let { "${numero(it, 0)} °C" } ?: "—")
            campo("Pressão máx. Common Rail", e.rail.maximo?.let { "${numero(it, 0)} kPa (${numero(it / 100, 0)} bar)" } ?: "—")
            campo("Temp. máx. do câmbio (ATF)", e.temperaturaAtf.maximo?.let { "${numero(it, 0)} °C" } ?: "—")
            val tMin = e.tensao.minimo
            val tMax = e.tensao.maximo
            campo("Tensão mín. / máx.", if (tMin != null && tMax != null) "${numero(tMin, 1)} V / ${numero(tMax, 1)} V" else "—")
            val viagem = e.consumo
            campo("Distância percorrida", if (viagem.distanciaKm > 0.0) "${numero(viagem.distanciaKm, 1)} km" else "—")
            campo("Consumo médio (km/L)", viagem.mediaKmPorLitro?.let { "${numero(it, 1)} km/L" } ?: "—")
            campo("Combustível estimado (L)", if (viagem.litros > 0.0) "${numero(viagem.litros, 2)} L (via MAF)" else "—")
        }
        saida.append('\n').append(LINHA).append('\n')
        saida.append(TITULO_LOG_BRUTO).append('\n')
        saida.append(LINHA).append('\n')
    }

    private fun listaDtcs(rotulo: String, lista: List<DtcLido>?, saida: Appendable) {
        when {
            lista == null -> saida.append("  $rotulo: não lido / não suportado\n")
            lista.isEmpty() -> saida.append("  $rotulo: nenhum\n")
            else -> {
                saida.append("  $rotulo: ${lista.size}\n")
                lista.forEach { saida.append("    • ${it.codigo} — ${it.descricao}\n") }
            }
        }
    }

    /** MAP absoluto e, se acima da atmosfera, a pressão positiva aproximada do turbo. */
    private fun descreverMap(kpa: Double): String {
        val turbo = (kpa - PRESSAO_ATMOSFERICA_KPA) / 100
        return if (turbo > 0.05) "${numero(kpa, 0)} kPa (≈ +${numero(turbo, 2)} bar de turbo)" else "${numero(kpa, 0)} kPa"
    }

    fun dataHora(ms: Long, zona: ZoneId): String = formatoDataHora.format(Instant.ofEpochMilli(ms).atZone(zona))

    /** `1 h 02 min 05 s`, `42 min 39 s`, `35 s`. */
    fun duracao(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val seg = s % 60
        return when {
            h > 0 -> "%d h %02d min %02d s".format(Locale.ROOT, h, m, seg)
            m > 0 -> "%d min %02d s".format(Locale.ROOT, m, seg)
            else -> "$seg s"
        }
    }

    fun numero(valor: Double, casas: Int): String =
        NumberFormat.getNumberInstance(localeBr).apply {
            minimumFractionDigits = casas
            maximumFractionDigits = casas
        }.format(valor)
}
