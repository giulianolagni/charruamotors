package com.charruamotors.obd.log

import com.charruamotors.sistema.unidades.UnidadeConsumo
import com.charruamotors.domain.calculo.CambioA750f
import com.charruamotors.domain.calculo.ComputadorBordo
import com.charruamotors.domain.calculo.EstequiometriaFlex
import com.charruamotors.domain.calculo.ParametrosMotor
import com.charruamotors.domain.calculo.ResumoViagem
import com.charruamotors.domain.calculo.TelemetriaCalculada
import com.charruamotors.domain.faixas.FaixaEsperada
import com.charruamotors.domain.faixas.FaixasVeiculo
import com.charruamotors.domain.faixas.SituacaoFaixa
import com.charruamotors.domain.perfis.ImpressaoEcu
import com.charruamotors.domain.perfis.OrigemPerfil
import com.charruamotors.domain.perfis.VinDecodificado
import com.charruamotors.obd.DtcLido
import com.charruamotors.obd.ValoresSensores
import com.charruamotors.obd.pid.DefinicaoPid
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

/** Mínimo, máximo e média de um sensor ao longo da sessão (v2.2.5): só valores finitos entram. */
class ResumoSensor {
    var amostras: Int = 0
        private set
    var minimo: Double = Double.NaN
        private set
    var maximo: Double = Double.NaN
        private set
    private var soma = 0.0
    private val _estados = LinkedHashSet<Int>()

    val media: Double get() = if (amostras == 0) Double.NaN else soma / amostras

    /**
     * Valores inteiros distintos lidos, na ordem em que apareceram (v2.2.6): o resumo dos sensores
     * discretos (alavanca, marcha). Para de crescer em [MAX_ESTADOS] (sensores contínuos não enchem a memória).
     */
    val estados: List<Int> get() = _estados.toList()

    fun registrar(valor: Double) {
        if (!valor.isFinite()) return
        amostras++
        soma += valor
        if (minimo.isNaN() || valor < minimo) minimo = valor
        if (maximo.isNaN() || valor > maximo) maximo = valor
        val inteiro = Math.round(valor)
        if (_estados.size < MAX_ESTADOS && kotlin.math.abs(valor - inteiro) < 0.01) _estados.add(inteiro.toInt())
    }

    companion object {
        const val MAX_ESTADOS = 32
    }
}

/**
 * Acompanhamento de uma faixa esperada ao longo da sessão: extremos lidos (só quando a condição da
 * faixa vale, ex.: marcha lenta) e quantas amostras ficaram abaixo/acima.
 */
class AcompanhamentoFaixa(val faixa: FaixaEsperada) {
    val extremos = Extremos()
    var amostras: Int = 0
        private set
    var abaixo: Int = 0
        private set
    var acima: Int = 0
        private set

    fun registrar(valor: Double) {
        amostras++
        extremos.registrar(valor)
        when (faixa.situacao(valor)) {
            SituacaoFaixa.ABAIXO -> abaixo++
            SituacaoFaixa.ACIMA -> acima++
            SituacaoFaixa.DENTRO -> Unit
        }
    }

    /** `OK`, `acima em 12 % das leituras`, `abaixo em 3 % · acima em 1 %`, `sem leitura`. */
    val situacao: String
        get() {
            if (amostras == 0) return "sem leitura"
            if (abaixo == 0 && acima == 0) return "OK"
            fun pct(n: Int) = "${RelatorioSessaoTxt.numero(n * 100.0 / amostras, 0)} %"
            return listOfNotNull(
                if (abaixo > 0) "abaixo em ${pct(abaixo)}" else null,
                if (acima > 0) "acima em ${pct(acima)}" else null,
            ).joinToString(" · ") + " das leituras"
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
    /** Faixas mínimas/máximas esperadas do veículo (v2.1); null = sem resumo de faixas. */
    faixas: FaixasVeiculo? = null,
) {
    /** Faixas em acompanhamento; troca em [trocarFaixas] quando a sessão identifica um perfil melhor (v2.2.2). */
    var faixas: FaixasVeiculo? = faixas
        private set

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

    /** Pressão barométrica lida (`2133` Toyota ou `0133` padrão), em kPa (v2.2.6, boost real). */
    val barometro = Extremos()

    /** Barômetro mais recente quando o MAP bateu o pico (null = nenhum lido até ali). */
    var barometroNoPicoMap: Double? = null
        private set
    private var ultimoBarometro: Double? = null

    /** Barômetro para o boost do resumo: o do pico do MAP, senão o último lido; null = usar 101 kPa. */
    val barometroBoost: Double? get() = barometroNoPicoMap ?: ultimoBarometro

    /** Teor de etanol (`0152`) e relação A/F real calculada (v2.1, Modo Flex). */
    val etanol = Extremos()
    val relacaoAf = Extremos()

    /** Mín/máx/média de **todo** sensor lido na sessão, por comando (ordem de primeira leitura). */
    val sensores: MutableMap<String, ResumoSensor> = LinkedHashMap()

    /** Amostras por marcha (valor de `TOYOTA_MARCHA` arredondado), para o resumo de marchas. */
    val marchas: MutableMap<Int, Int> = java.util.TreeMap()

    /** Uma por faixa esperada do veículo, na ordem do preset. */
    var acompanhamentoFaixas: List<AcompanhamentoFaixa> = faixas?.faixas.orEmpty().map(::AcompanhamentoFaixa)
        private set

    /** Passa a acompanhar as faixas de [novas] (a contagem das faixas recomeça; os extremos da viagem seguem). */
    fun trocarFaixas(novas: FaixasVeiculo?) {
        faixas = novas
        acompanhamentoFaixas = novas?.faixas.orEmpty().map(::AcompanhamentoFaixa)
    }

    fun registrar(dados: ValoresSensores, instanteMs: Long? = null) {
        amostras++
        for ((comando, valor) in dados.valores) sensores.getOrPut(comando) { ResumoSensor() }.registrar(valor)
        dados[CambioA750f.CHAVE_MARCHA]?.takeIf { it.isFinite() }?.let { marchas.merge(it.toInt(), 1, Int::plus) }
        if (motor != null && instanteMs != null) {
            bordo.registrar(TelemetriaCalculada.consumoInstantaneo(dados, motor), dados[Pids.VELOCIDADE], instanteMs)
        }
        rpm.registrar(dados[Pids.RPM])
        velocidade.registrar(dados[Pids.VELOCIDADE])
        COMANDOS_BAROMETRO.firstNotNullOfOrNull { c -> dados[c]?.takeIf { it.isFinite() && it in FAIXA_BAROMETRO_KPA } }?.let {
            ultimoBarometro = it
            barometro.registrar(it)
        }
        val mapLido = dados[Pids.PRESSAO_COLETOR]?.takeIf { it.isFinite() }
        if (mapLido != null && (map.maximo == null || mapLido > map.maximo!!)) barometroNoPicoMap = ultimoBarometro
        map.registrar(mapLido)
        temperaturaMotor.registrar(dados[Pids.TEMPERATURA_MOTOR])
        rail.registrar(dados[Pids.PRESSAO_RAIL])
        tensao.registrar(dados[Pids.TENSAO_BATERIA])
        temperaturaAtf.registrar(Pids.COMANDOS_ATF.firstNotNullOfOrNull { c -> dados[c]?.takeIf { it.isFinite() } })
        etanol.registrar(dados[Pids.PERCENTUAL_ETANOL])
        relacaoAf.registrar(dados[Pids.RELACAO_AF_REAL])
        val f = faixas ?: return
        acompanhamentoFaixas.forEach { a -> f.leituras(a.faixa, dados).forEach { (_, v) -> a.registrar(v) } }
    }

    companion object {
        /** Barômetro Toyota (`7E0 · 2133`) antes do OBD2 padrão (`0133`). */
        val COMANDOS_BAROMETRO = listOf("TOYOTA_BARO", "0133")

        /** Leituras fora disto (0, 255) são lixo e não entram no boost. */
        val FAIXA_BAROMETRO_KPA = 50.0..115.0
    }
}

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
    /** Faixas mínimas/máximas esperadas do veículo, resumidas no fim da viagem; null = sem veículo. */
    val faixas: FaixasVeiculo? = null,
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
    /** VIN lido pelo `0902` (null = não lido / não suportado). */
    val vin: String? = null,
    /** Montadora pelo WMI e ano-modelo pelo 10º caractere. */
    val vinDecodificado: VinDecodificado? = null,
    /** Sugestão de perfil do catálogo quando o veículo cadastrado está num perfil genérico. */
    val sugestaoPerfil: String? = null,
    /** Impressão digital da ECU (`0151`, `0904`, TCM); null = a sondagem não rodou nesta sessão. */
    val impressao: ImpressaoEcu? = null,
    /** Como o perfil em uso foi escolhido (Manual / VDS do VIN / Sondagem ECU). */
    val origemPerfil: OrigemPerfil = OrigemPerfil.MANUAL,
    /** Perfil aplicado automaticamente (título e motivo); null = o do cadastro. */
    val perfilAutomatico: String? = null,
    /** Definições dos sensores da sessão (nome, unidade, casas) para a tabela de mín/máx/média. */
    val colunas: List<DefinicaoPid> = emptyList(),
    /** Alertas de proteção (água, ATF, alternador) disparados na sessão, na ordem. */
    val alertas: List<String> = emptyList(),
    /** O log bruto detalhado foi gravado nesta sessão (opção das Configurações, desligada por padrão). */
    val logBruto: Boolean = false,
)

/**
 * Relatório legível da sessão (`CharruaOBD_[SIMULADO_]YYYYMMDD_HHMMSS_<Veiculo>.txt`): cabeçalho, códigos de
 * falha, resumo estatístico da viagem e, depois, o log bruto completo (escrito pelo
 * [GravadorLogsSessao]). Números no formato brasileiro (`12,6 V`, `3.420 rpm`).
 */
object RelatorioSessaoTxt {

    const val TITULO_LOG_BRUTO = "LOG BRUTO DE COMUNICAÇÃO E EVENTOS"
    const val TITULO_EVENTOS = "EVENTOS DA SESSÃO (log bruto desligado)"
    const val TITULO_SENSORES = "SENSORES DA SESSÃO (mínimo / máximo / média)"
    private const val LINHA = "=================================================================="
    /** Fallback do boost quando a sessão não leu barômetro nenhum. */
    const val PRESSAO_ATMOSFERICA_KPA = 101.0

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
        if (r.conectou) {
            campo("VIN (0902)", r.vin ?: "não lido / não suportado")
            r.vinDecodificado?.let { d ->
                campo("Montadora (VIN)", d.montadora?.let { "$it (WMI ${d.wmi}${d.origem?.let { o -> ", $o" }.orEmpty()})" } ?: "desconhecida (WMI ${d.wmi})")
                campo("Ano-modelo (VIN)", d.anoModelo?.toString() ?: "—")
            }
        }
        if (r.conectou) {
            val i = r.impressao
            val semResposta = "sem resposta (NO DATA)"
            campo("Combustível (0151)", if (i == null) "não sondado" else i.descricaoCombustivel ?: semResposta)
            campo("CALID (0904)", if (i == null || i.calidNaoSondado) "não sondado" else i.calid ?: semResposta)
            campo(
                "TCM detectado",
                when {
                    i?.tcmDetectado == true -> "sim (${i.tcmCabecalho})"
                    i?.tcmDetectado == false -> "não (sem resposta em 7E1/7E2)"
                    else -> "não sondado"
                },
            )
        }
        campo("Origem do perfil", r.origemPerfil.rotulo + (r.perfilAutomatico?.let { " — $it" }.orEmpty()))
        (r.estatisticas.faixas ?: c.faixas)?.perfil?.let { campo("Perfil de faixas", it) }
        r.sugestaoPerfil?.let { campo("Sugestão de perfil", it) }
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
            campo("Pico de MAP/Turbo", e.map.maximo?.let { descreverMap(it, e.barometroBoost) } ?: "—")
            campo("Temperatura máx. do motor", e.temperaturaMotor.maximo?.let { "${numero(it, 0)} °C" } ?: "—")
            campo("Pressão máx. Common Rail", e.rail.maximo?.let { "${numero(it, 0)} kPa (${numero(it / 100, 0)} bar)" } ?: "—")
            campo("Temp. máx. do câmbio (ATF)", e.temperaturaAtf.maximo?.let { "${numero(it, 0)} °C" } ?: "—")
            val tMin = e.tensao.minimo
            val tMax = e.tensao.maximo
            campo("Tensão mín. / máx.", if (tMin != null && tMax != null) "${numero(tMin, 1)} V / ${numero(tMax, 1)} V" else "—")
            val viagem = e.consumo
            campo("Distância percorrida", if (viagem.distanciaKm > 0.0) "${numero(viagem.distanciaKm, 1)} km" else "—")
            // v2.3.0: as duas unidades de consumo (km/L e L/100km), qualquer que seja a escolha do app.
            campo("Consumo médio", viagem.mediaKmPorLitro?.let { "${numero(it, 1)} km/L · ${numero(UnidadeConsumo.L_100KM.deKmPorLitro(it), 1)} L/100km" } ?: "—")
            campo("Combustível estimado (L)", if (viagem.litros > 0.0) "${numero(viagem.litros, 2)} L (via MAF)" else "—")
            e.etanol.maximo?.let { eMax ->
                val eMin = e.etanol.minimo ?: eMax
                val classe = EstequiometriaFlex.classificar(eMax).titulo
                campo("Teor de etanol (0152)", "${numero(eMin, 0)} – ${numero(eMax, 0)} % ($classe)")
            }
            val afMin = e.relacaoAf.minimo
            val afMax = e.relacaoAf.maximo
            if (afMin != null && afMax != null) campo("Relação A/F real", "${numero(afMin, 1)} – ${numero(afMax, 1)} :1")
        }
        if (e.acompanhamentoFaixas.isNotEmpty()) {
            saida.append('\n').append("FAIXAS ESPERADAS (${e.faixas?.perfil})\n")
            e.acompanhamentoFaixas.forEach { a ->
                val p = a.faixa.parametro
                val lido = a.extremos.minimo?.let { mn ->
                    val mx = a.extremos.maximo ?: mn
                    "lido ${numero(mn, p.casas)} – ${numero(mx, p.casas)} ${p.unidade}"
                } ?: "não lido"
                saida.append("  • ${p.titulo} (${a.faixa.texto}): $lido · ${a.situacao}\n")
            }
        }
        escreverAlertas(r.alertas, saida)
        escreverMarchas(e, saida)
        escreverSensores(e, r.colunas, saida)
        saida.append('\n').append(LINHA).append('\n')
        saida.append(if (r.logBruto) TITULO_LOG_BRUTO else TITULO_EVENTOS).append('\n')
        saida.append(LINHA).append('\n')
    }

    private fun escreverAlertas(alertas: List<String>, saida: Appendable) {
        saida.append('\n').append("ALERTAS DA SESSÃO\n")
        if (alertas.isEmpty()) {
            saida.append("  Nenhum alerta de proteção.\n")
        } else {
            saida.append("  ${alertas.size} alerta(s):\n")
            alertas.forEach { saida.append("    • $it\n") }
        }
    }

    /**
     * Resumo de marchas: amostras (e % do total com marcha) por posição; só quando a sessão tem marcha.
     * v2.2.6: as marchas da reduzida (`1ª L`..`5ª L`) vêm separadas, depois das da 4H.
     */
    private fun escreverMarchas(e: EstatisticasViagem, saida: Appendable) {
        if (e.marchas.isEmpty()) return
        val total = e.marchas.values.sum()
        val rotulos = DefinicaoPid.MARCHA_ESTIMADA_A750F.rotulos
        fun linha(valor: Int, n: Int) {
            saida.append("  ${(rotulos[valor] ?: valor.toString()).padEnd(8)}: ${numero(n.toDouble(), 0)} amostras (${numero(n * 100.0 / total, 1)} %)\n")
        }
        val (reduzida, normal) = e.marchas.entries.partition { CambioA750f.ehReduzida(it.key) }
        saida.append('\n').append("MARCHAS\n")
        normal.forEach { (valor, n) -> linha(valor, n) }
        if (reduzida.isNotEmpty()) {
            saida.append("  Reduzida (4L):\n")
            reduzida.forEach { (valor, n) -> linha(valor, n) }
        }
    }

    /**
     * Tabela com **todo** sensor lido: mínimo, máximo e média (formato brasileiro). A ordem é a das
     * colunas do CSV; o que não tiver definição (chave solta) vem depois, pelo próprio comando.
     */
    private fun escreverSensores(e: EstatisticasViagem, colunas: List<DefinicaoPid>, saida: Appendable) {
        saida.append('\n').append(TITULO_SENSORES).append('\n')
        if (e.sensores.isEmpty()) {
            saida.append("  Nenhum sensor lido nesta sessão.\n")
            return
        }
        val porComando = colunas.associateBy { it.comando }
        val ordem = colunas.map { it.comando }.distinct().filter { it in e.sensores } + e.sensores.keys.filter { it !in porComando }
        for (comando in ordem) {
            val s = e.sensores[comando]?.takeIf { it.amostras > 0 } ?: continue
            val def = porComando[comando]
            val casas = def?.casasDecimais ?: 1
            val unidade = def?.unidade?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
            val nome = def?.nome ?: comando
            if (def != null && def.rotulos.isNotEmpty()) {
                // Estado/categoria (alavanca, marcha): média e mín/máx não fazem sentido (o código de D não
                // fica "entre" P e R); v2.2.6 lista os estados distintos observados, na ordem em que apareceram.
                val estados = s.estados.joinToString(", ") { def.rotulos[it] ?: it.toString() }.ifEmpty { "—" }
                saida.append("  • $nome [$comando]: estados observados: $estados (${numero(s.amostras.toDouble(), 0)} leituras)\n")
                continue
            }
            saida.append(
                "  • $nome [$comando]: mín ${numero(s.minimo, casas)} · máx ${numero(s.maximo, casas)} · " +
                    "média ${numero(s.media, casas)}$unidade (${numero(s.amostras.toDouble(), 0)} leituras)\n",
            )
        }
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

    /**
     * MAP absoluto e, se acima da atmosfera, a pressão positiva do turbo (boost, bar). v2.2.6: desconta
     * o [barometro] real lido (`2133`/`0133`, importante na serra); sem ele, [PRESSAO_ATMOSFERICA_KPA].
     */
    fun descreverMap(kpa: Double, barometro: Double? = null): String {
        val turbo = boostBar(kpa, barometro)
        if (turbo <= 0.05) return "${numero(kpa, 0)} kPa"
        val referencia = barometro?.let { ", barômetro ${numero(it, 0)} kPa" }.orEmpty()
        return "${numero(kpa, 0)} kPa (≈ +${numero(turbo, 2)} bar de turbo$referencia)"
    }

    /** Boost (bar) = (MAP − barômetro) / 100; sem barômetro lido, 101 kPa. */
    fun boostBar(mapKpa: Double, barometro: Double? = null): Double =
        (mapKpa - (barometro ?: PRESSAO_ATMOSFERICA_KPA)) / 100

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
