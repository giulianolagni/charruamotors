package com.charruamotors.obd

import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.obd.pid.Pids
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Scanner OBD2 simulado, para desenvolver e apresentar o app no emulador sem hardware.
 *
 * Simula um carro em movimento cuja ECU suporta todos os PIDs de [COMANDOS_SIMULADOS] (os do
 * catálogo padrão): a velocidade oscila com aceleração/frenagem suaves, o RPM acompanha a
 * velocidade (marchas), o motor e o óleo esquentam até a temperatura de trabalho, carga, MAP,
 * MAF, borboleta e consumo seguem a aceleração, e a tensão varia em torno de 14 V (alternador).
 * Mantém códigos de falha armazenados, pendentes e permanentes, Freeze Frame e VIN de teste.
 *
 * @param latenciaMs atraso simulado de conectar/ler DTCs (use 0 nos testes).
 */
class ObdClientFake(
    private val random: Random = Random.Default,
    private val latenciaMs: Long = 1200,
    codigosIniciais: List<String> = listOf("P0300", "P0171", "P0420"),
    codigosPendentesIniciais: List<String> = listOf("P0133"),
    private val codigosPermanentes: List<String> = listOf("P0420"),
    private val vin: String = "9BWZZZ377VT004251",
) : ObdClient {

    private val _estado = MutableStateFlow<EstadoConexaoObd>(EstadoConexaoObd.Desconectado)
    override val estado: StateFlow<EstadoConexaoObd> = _estado.asStateFlow()

    private val _pidsSuportados = MutableStateFlow<Set<String>>(emptySet())
    override val pidsSuportados: StateFlow<Set<String>> = _pidsSuportados.asStateFlow()

    private var codigos: List<String> = codigosIniciais
    private var codigosPendentes: List<String> = codigosPendentesIniciais

    /** Quadro congelado quando a primeira falha armazenada foi registrada (apagado pelo modo 04). */
    private var freezeFrame: FreezeFrame = freezeFrameDe(codigosIniciais.firstOrNull())

    /** Permite demonstrar o tratamento de erro de conexão na apresentação. */
    @Volatile
    var simularFalhaNaConexao: Boolean = false

    // Estado interno da simulação (evolui a cada leitura)
    private var velocidade = 0.0
    private var velocidadeAlvo = 40.0
    private var temperatura = 35.0
    private var temperaturaOleo = 30.0
    private var nivelCombustivel = 62.0
    private var segundosMotorLigado = 0
    private var kmComLuzAcesa = KM_COM_LUZ_ACESA_INICIAL
    private var kmDesdeLimpeza = KM_DESDE_LIMPEZA_INICIAL

    override suspend fun listarDispositivos(): List<DispositivoObd> = listOf(
        DispositivoObd("OBDII Simulado (ELM327)", "00:00:00:00:00:01"),
        DispositivoObd("Scanner Reserva (simulado)", "00:00:00:00:00:02"),
    )

    override suspend fun conectar(dispositivo: DispositivoObd): Result<Unit> {
        _estado.value = EstadoConexaoObd.Conectando
        delay(latenciaMs)
        if (simularFalhaNaConexao) {
            val msg = "Não foi possível conectar a ${dispositivo.nome} (falha simulada)"
            _estado.value = EstadoConexaoObd.Erro(msg)
            return Result.failure(ObdException(msg))
        }
        velocidade = 0.0
        velocidadeAlvo = 40.0
        temperatura = 35.0
        temperaturaOleo = 30.0
        segundosMotorLigado = 0
        _pidsSuportados.value = COMANDOS_SIMULADOS
        _estado.value = EstadoConexaoObd.Conectado
        return Result.success(Unit)
    }

    override fun lerDadosEmTempoReal(intervaloMs: Long, comandos: Collection<String>?): Flow<DadosObd> = flow {
        val pedidos = comandos?.toSet()
        while (_estado.value == EstadoConexaoObd.Conectado) {
            val leitura = proximaLeitura()
            emit(DadosObd(if (pedidos == null) leitura else leitura.filterKeys { it in pedidos }))
            delay(intervaloMs)
        }
    }

    override suspend fun lerCodigosFalha(): Result<List<DtcLido>> = lerDtcs { codigos }

    override suspend fun lerCodigosPendentes(): Result<List<DtcLido>> = lerDtcs { codigosPendentes }

    override suspend fun lerCodigosPermanentes(): Result<List<DtcLido>> = lerDtcs { codigosPermanentes }

    override suspend fun lerFreezeFrame(): Result<FreezeFrame> {
        if (_estado.value != EstadoConexaoObd.Conectado) return Result.failure(naoConectado())
        delay(latenciaMs)
        return Result.success(freezeFrame)
    }

    override suspend fun lerVin(): Result<String> {
        if (_estado.value != EstadoConexaoObd.Conectado) return Result.failure(naoConectado())
        delay(latenciaMs)
        return Result.success(vin)
    }

    /** Como numa ECU real: apaga armazenados, pendentes e o Freeze Frame; os permanentes ficam. */
    override suspend fun limparCodigosFalha(): Result<Unit> {
        if (_estado.value != EstadoConexaoObd.Conectado) return Result.failure(naoConectado())
        delay(latenciaMs)
        codigos = emptyList()
        codigosPendentes = emptyList()
        freezeFrame = FreezeFrame()
        kmComLuzAcesa = 0.0
        kmDesdeLimpeza = 0.0
        return Result.success(Unit)
    }

    override suspend fun desconectar() {
        _pidsSuportados.value = emptySet()
        _estado.value = EstadoConexaoObd.Desconectado
    }

    private suspend fun lerDtcs(fonte: () -> List<String>): Result<List<DtcLido>> {
        if (_estado.value != EstadoConexaoObd.Conectado) return Result.failure(naoConectado())
        delay(latenciaMs)
        return Result.success(fonte().map { DtcLido(it, DescricaoDtc.descricaoDe(it)) })
    }

    private fun naoConectado() = ObdException("Scanner não conectado")

    /** Avança a simulação um passo (~1 s de condução) e devolve todos os PIDs "lidos" da ECU. */
    private fun proximaLeitura(): Map<String, Double> {
        // De vez em quando escolhe uma nova velocidade-alvo (trânsito: para, acelera, freia).
        if (random.nextInt(100) < 12) velocidadeAlvo = random.nextInt(0, 111).toDouble()
        val aceleracao = (velocidadeAlvo - velocidade).coerceIn(-12.0, 8.0)
        velocidade = (velocidade + aceleracao + random.nextDouble(-1.0, 1.0)).coerceIn(0.0, 180.0)

        temperatura = (temperatura + random.nextDouble(1.0, 3.0)).coerceAtMost(90.0 + random.nextDouble(-1.0, 1.0))
        // O óleo esquenta mais devagar que o líquido de arrefecimento e estabiliza um pouco acima dele.
        temperaturaOleo += (temperatura + 6.0 - temperaturaOleo) * 0.06

        val rpm = if (velocidade < 1.0) {
            800.0 + random.nextDouble(-40.0, 40.0) // marcha lenta
        } else {
            (900.0 + velocidade * 28.0 + random.nextDouble(-60.0, 60.0)).coerceAtMost(6500.0)
        }
        val acelerando = aceleracao.coerceAtLeast(0.0)
        val carga = (18.0 + acelerando * 6.0 + velocidade * 0.25 + random.nextDouble(-2.0, 2.0))
            .coerceIn(0.0, 100.0)
        val tensao = 14.0 + random.nextDouble(-0.3, 0.3)

        val pedal = (acelerando * 9.0 + velocidade * 0.15 + random.nextDouble(0.0, 1.0)).coerceIn(0.0, 100.0)
        val borboleta = (BORBOLETA_FECHADA + pedal * 0.8).coerceIn(0.0, 100.0)
        val map = (26.0 + carga * 0.68 + random.nextDouble(-1.0, 1.0)).coerceIn(20.0, PRESSAO_BAROMETRICA)
        val maf = (1.5 + rpm * carga * 0.00018 + random.nextDouble(-0.2, 0.2)).coerceAtLeast(0.5)
        val consumoLh = maf * 3600.0 / (RELACAO_ESTEQUIOMETRICA * DENSIDADE_GASOLINA_G_L)
        val avanco = (10.0 + rpm / 250.0 - carga * 0.08 + random.nextDouble(-1.0, 1.0)).coerceIn(-5.0, 40.0)
        val enriquecendo = carga > 80.0 // plena carga: a ECU enriquece a mistura (lambda < 1)
        val lambda = if (enriquecendo) 0.88 else 1.0 + random.nextDouble(-0.02, 0.02)
        // Sonda lambda de banda estreita oscila entre pobre (~0,1 V) e rica (~0,8 V) em malha fechada.
        val sonda = if (enriquecendo || random.nextBoolean()) random.nextDouble(0.70, 0.88) else random.nextDouble(0.08, 0.25)

        segundosMotorLigado += 1
        val kmNoPasso = velocidade / 3600.0
        kmDesdeLimpeza += kmNoPasso
        if (codigos.isNotEmpty()) kmComLuzAcesa += kmNoPasso
        nivelCombustivel = (nivelCombustivel - consumoLh / 3600.0 / TANQUE_LITROS * 100.0).coerceAtLeast(0.0)

        return linkedMapOf(
            Pids.CARGA_MOTOR to carga.arredondar(1),
            Pids.TEMPERATURA_MOTOR to temperatura.roundToInt().toDouble(),
            "0106" to random.nextDouble(-4.0, 4.0).arredondar(1), // STFT: correção rápida em torno de 0
            "0107" to (2.3 + random.nextDouble(-0.2, 0.2)).arredondar(1), // LTFT: adaptação estável
            "010A" to (PRESSAO_LINHA_COMBUSTIVEL + random.nextDouble(-9.0, 9.0)).roundToInt().toDouble(),
            "010B" to map.roundToInt().toDouble(),
            Pids.RPM to rpm.roundToInt().toDouble(),
            Pids.VELOCIDADE to velocidade.roundToInt().toDouble(),
            "010E" to avanco.arredondar(1),
            "010F" to (28.0 + (temperatura - 35.0) * 0.12 + random.nextDouble(-0.5, 0.5)).roundToInt().toDouble(),
            "0110" to maf.arredondar(2),
            "0111" to borboleta.arredondar(1),
            "0114" to sonda.arredondar(3),
            "011F" to segundosMotorLigado.toDouble(),
            "0121" to kmComLuzAcesa.roundToInt().toDouble(),
            "0123" to (4000.0 + carga * 90.0 + random.nextDouble(-150.0, 150.0)).roundToInt().toDouble(),
            "012F" to (nivelCombustivel + random.nextDouble(-0.4, 0.4)).coerceIn(0.0, 100.0).arredondar(1),
            "0131" to kmDesdeLimpeza.roundToInt().toDouble(),
            "0133" to PRESSAO_BAROMETRICA,
            "0142" to (tensao - 0.2).arredondar(2),
            "0144" to lambda.arredondar(3),
            "0145" to (borboleta - BORBOLETA_FECHADA).coerceAtLeast(0.0).arredondar(1),
            "0146" to TEMPERATURA_AMBIENTE,
            "0149" to pedal.arredondar(1),
            "015C" to temperaturaOleo.roundToInt().toDouble(),
            "015E" to consumoLh.arredondar(1),
            Pids.TENSAO_BATERIA to tensao.arredondar(1),
        )
    }

    private fun Double.arredondar(casas: Int): Double {
        var fator = 1.0
        repeat(casas) { fator *= 10 }
        return (this * fator).roundToInt() / fator
    }

    companion object {
        /** PIDs que a "ECU" simulada suporta: todos os do catálogo padrão (`pids_padrao.json`). */
        val COMANDOS_SIMULADOS: Set<String> = setOf(
            "0104", "0105", "0106", "0107", "010A", "010B", "010C", "010D", "010E", "010F",
            "0110", "0111", "0114", "011F", "0121", "0123", "012F", "0131", "0133", "0142",
            "0144", "0145", "0146", "0149", "015C", "015E", "ATRV",
        )

        private const val BORBOLETA_FECHADA = 8.0 // % de abertura da borboleta em marcha lenta
        private const val PRESSAO_BAROMETRICA = 95.0 // kPa (~600 m de altitude)
        private const val PRESSAO_LINHA_COMBUSTIVEL = 380.0 // kPa, injeção indireta
        private const val TEMPERATURA_AMBIENTE = 24.0
        private const val RELACAO_ESTEQUIOMETRICA = 14.7
        private const val DENSIDADE_GASOLINA_G_L = 745.0
        private const val TANQUE_LITROS = 50.0
        private const val KM_COM_LUZ_ACESA_INICIAL = 37.0
        private const val KM_DESDE_LIMPEZA_INICIAL = 1250.0

        /** Condições de quando a falha "aconteceu": em aceleração, motor quente e carga alta. */
        private fun freezeFrameDe(dtc: String?): FreezeFrame =
            if (dtc == null) {
                FreezeFrame()
            } else {
                FreezeFrame(
                    dtc = dtc,
                    valores = mapOf(
                        Pids.RPM to 2350.0,
                        Pids.VELOCIDADE to 62.0,
                        Pids.TEMPERATURA_MOTOR to 97.0,
                        Pids.CARGA_MOTOR to 78.4,
                    ),
                )
            }
    }
}
