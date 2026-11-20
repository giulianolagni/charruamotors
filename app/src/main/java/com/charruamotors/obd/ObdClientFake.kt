package com.charruamotors.obd

import com.charruamotors.domain.model.EstadoConexaoObd
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
 * Simula um carro em movimento: a velocidade oscila com aceleração/frenagem suaves, o RPM
 * acompanha a velocidade (marchas), o motor esquenta até ~90 °C, a carga segue a aceleração
 * e a tensão da bateria varia em torno de 14 V (alternador). Mantém códigos de falha de teste.
 *
 * @param latenciaMs atraso simulado de conectar/ler DTCs (use 0 nos testes).
 */
class ObdClientFake(
    private val random: Random = Random.Default,
    private val latenciaMs: Long = 1200,
    codigosIniciais: List<String> = listOf("P0300", "P0171", "P0420"),
) : ObdClient {

    private val _estado = MutableStateFlow<EstadoConexaoObd>(EstadoConexaoObd.Desconectado)
    override val estado: StateFlow<EstadoConexaoObd> = _estado.asStateFlow()

    private var codigos: List<String> = codigosIniciais

    /** Permite demonstrar o tratamento de erro de conexão na apresentação. */
    @Volatile
    var simularFalhaNaConexao: Boolean = false

    // Estado interno da simulação (evolui a cada leitura)
    private var velocidade = 0.0
    private var velocidadeAlvo = 40.0
    private var temperatura = 35.0

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
        _estado.value = EstadoConexaoObd.Conectado
        return Result.success(Unit)
    }

    override fun lerDadosEmTempoReal(intervaloMs: Long): Flow<DadosObd> = flow {
        while (_estado.value == EstadoConexaoObd.Conectado) {
            emit(proximaLeitura())
            delay(intervaloMs)
        }
    }

    override suspend fun lerCodigosFalha(): Result<List<DtcLido>> {
        if (_estado.value != EstadoConexaoObd.Conectado) return Result.failure(naoConectado())
        delay(latenciaMs)
        return Result.success(codigos.map { DtcLido(it, DescricaoDtc.descricaoDe(it)) })
    }

    override suspend fun limparCodigosFalha(): Result<Unit> {
        if (_estado.value != EstadoConexaoObd.Conectado) return Result.failure(naoConectado())
        delay(latenciaMs)
        codigos = emptyList()
        return Result.success(Unit)
    }

    override suspend fun desconectar() {
        _estado.value = EstadoConexaoObd.Desconectado
    }

    private fun naoConectado() = ObdException("Scanner não conectado")

    /** Avança a simulação um passo e devolve os valores "lidos" da ECU. */
    private fun proximaLeitura(): DadosObd {
        // De vez em quando escolhe uma nova velocidade-alvo (trânsito: para, acelera, freia).
        if (random.nextInt(100) < 12) velocidadeAlvo = random.nextInt(0, 111).toDouble()
        val aceleracao = (velocidadeAlvo - velocidade).coerceIn(-12.0, 8.0)
        velocidade = (velocidade + aceleracao + random.nextDouble(-1.0, 1.0)).coerceIn(0.0, 180.0)

        temperatura = (temperatura + random.nextDouble(1.0, 3.0)).coerceAtMost(90.0 + random.nextDouble(-1.0, 1.0))

        val rpm = if (velocidade < 1.0) {
            800.0 + random.nextDouble(-40.0, 40.0) // marcha lenta
        } else {
            (900.0 + velocidade * 28.0 + random.nextDouble(-60.0, 60.0)).coerceAtMost(6500.0)
        }
        val carga = (18.0 + aceleracao.coerceAtLeast(0.0) * 6.0 + velocidade * 0.25 + random.nextDouble(-2.0, 2.0))
            .coerceIn(0.0, 100.0)
        val tensao = 14.0 + random.nextDouble(-0.3, 0.3)

        return DadosObd(
            rpm = rpm.roundToInt(),
            velocidade = velocidade.roundToInt(),
            temperaturaMotor = temperatura.roundToInt(),
            cargaMotor = (carga * 10).roundToInt() / 10.0,
            tensaoBateria = (tensao * 10).roundToInt() / 10.0,
        )
    }
}
