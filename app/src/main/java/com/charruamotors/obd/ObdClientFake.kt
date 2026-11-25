package com.charruamotors.obd

import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.domain.perfis.ImpressaoEcu
import com.charruamotors.obd.aprendizado.FonteSimulacao
import com.charruamotors.obd.aprendizado.ModeloAprendido
import com.charruamotors.obd.aprendizado.ReprodutorTrechos
import com.charruamotors.obd.log.EntradaRegistro
import com.charruamotors.obd.log.RegistroComunicacao
import com.charruamotors.obd.pid.CatalogoEstendido
import com.charruamotors.obd.pid.Pids
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
 * A simulação avança pelo tempo de cada leitura (1 s no polling simples; na telemetria, o
 * intervalo pedido, com piso de [INTERVALO_MINIMO_SIMULADO_MS] — o ritmo de um ELM327 rápido).
 *
 * Também simula uma arrancada 0–100 km/h ([simularArrancada]), PIDs de montadora de
 * [SIMULADOS_ESTENDIDOS] (os demais do perfil "não respondem", como numa ECU real), códigos de
 * falha armazenados, pendentes e permanentes, Freeze Frame e VIN de teste.
 *
 * Ferramentas de Oficina: comandos manuais respondidos por uma [EcuSimulada] (Hilux SW4 com motor
 * `7E0` e câmbio `7E1` no serviço 21) e a curva de tensão do Teste de Bateria ([simularBateria]:
 * repouso ~12,5 V, vale de partida ~10,3 V, alternador ~14,2 V).
 *
 * v2.1: com um veículo Chevrolet/GM a simulação vira o **Cruze 1.4 Turbo Flex 2018 (LE2)**
 * ([PerfilSimulacao.CRUZE_14T_FLEX], [SimuladorCruze]): turbo, injeção direta, etanol (`0152`),
 * sonda de banda larga (`0124`), câmbio de 6 marchas com ATF por UDS 22 1940 e uma ECU que
 * responde ao Teste de Atuadores (`10 03`, `3E 00`, `2F`; bobinas pedem Seed/Key `7F 2F 33`).
 *
 * @param latenciaMs atraso simulado de conectar/ler DTCs (use 0 nos testes).
 * @param latenciaComandoMs atraso simulado de cada comando manual (~um ELM327 rápido; 0 nos testes).
 * @param relogioMs relógio da curva de bateria e da latência (injetável para testes com tempo virtual).
 */
class ObdClientFake(
    private val random: Random = Random.Default,
    private val latenciaMs: Long = 1200,
    codigosIniciais: List<String> = listOf("P0300", "P0171", "P0420"),
    codigosPendentesIniciais: List<String> = listOf("P0133"),
    private val codigosPermanentes: List<String> = listOf("P0420"),
    /** VIN fixo; null = o do carro simulado (Cruze `8AG…J…` 2018, Toyota `8AJ…D…` 2013, demais o de teste VW). */
    private val vin: String? = null,
    private val catalogoEstendido: CatalogoEstendido = CatalogoEstendido.VAZIO,
    private val latenciaComandoMs: Long = 35,
    /**
     * Cenário "ECU sem impressão": `0151`, `0904` e o TCM respondem `NO DATA` (ECU antiga/básica), para
     * demonstrar e testar que a identificação nunca derruba a sessão. Também alterável em [ecuSemImpressao].
     */
    ecuSemImpressao: Boolean = false,
    private val relogioMs: () -> Long = { System.nanoTime() / 1_000_000 },
) : ObdClient {

    private val _estado = MutableStateFlow<EstadoConexaoObd>(EstadoConexaoObd.Desconectado)
    override val estado: StateFlow<EstadoConexaoObd> = _estado.asStateFlow()

    private val _pidsSuportados = MutableStateFlow<Set<String>>(emptySet())
    override val pidsSuportados: StateFlow<Set<String>> = _pidsSuportados.asStateFlow()

    private val _infoConexao = MutableStateFlow(InfoConexao())
    override val infoConexao: StateFlow<InfoConexao> = _infoConexao.asStateFlow()

    /** Registro ilustrativo (o que um ELM327 v1.5 em CAN responderia), para demonstrar o log de campo. */
    private val registro = RegistroComunicacao()

    override fun registroComunicacao(): List<EntradaRegistro> = registro.entradas()

    override fun observarRegistro(ouvinte: ((EntradaRegistro) -> Unit)?) {
        registro.ouvinte = ouvinte
    }

    /**
     * Demonstração/testes: a ECU para de responder como se a ignição fosse desligada — o mesmo
     * `Erro` que o cliente real publica depois de vários ciclos sem resposta, encerrando a telemetria.
     */
    fun simularIgnicaoDesligada() {
        if (_estado.value != EstadoConexaoObd.Conectado) return
        registro.evento("Conexão perdida: $MENSAGEM_ECU_MUDA")
        _pidsSuportados.value = emptySet()
        _estado.value = EstadoConexaoObd.Erro(MENSAGEM_ECU_MUDA)
    }

    private val _sessaoManual = MutableStateFlow(false)
    override val sessaoManualAtiva: StateFlow<Boolean> = _sessaoManual.asStateFlow()

    private val ecu = EcuSimulada(random)
    private val cruze = SimuladorCruze(random)

    /** Carro simulado nesta conexão (pela marca do veículo); [PerfilSimulacao.PADRAO] até conectar. */
    @Volatile var perfilSimulacao: PerfilSimulacao = PerfilSimulacao.PADRAO
        private set

    /**
     * Força o carro simulado (null = pela marca do veículo na conexão). Só vale na próxima conexão.
     */
    @Volatile var perfilForcado: PerfilSimulacao? = null

    // ---------------------------------------------------------------- aprendizado de logs reais (v2.2.4)

    /** Modelo aprendido do veículo da próxima conexão (null = simulador sintético). */
    @Volatile private var modeloPendente: ModeloAprendido? = null

    /** Reprodução dos trechos reais nesta conexão; null = sintético. */
    private var reprodutor: ReprodutorTrechos? = null

    /** Comandos 01xx/ATRV que a reprodução responde como PIDs padrão (os de montadora entram pelo perfil). */
    private var comandosAprendidos: Set<String> = emptySet()

    /** Chaves aprendidas (inclusive as de montadora) que o perfil pode ativar. */
    private var chavesAprendidas: Set<String> = emptySet()

    private val _fonteSimulacao = MutableStateFlow<FonteSimulacao>(FonteSimulacao.Sintetica)

    /** De onde vêm os dados desta conexão: logs reais do veículo ou o simulador sintético. */
    val fonteSimulacao: StateFlow<FonteSimulacao> = _fonteSimulacao.asStateFlow()

    /**
     * Define o modelo aprendido do veículo selecionado (sempre só dele) para a **próxima** conexão;
     * null, ou um modelo sem trecho utilizável, mantém o simulador sintético.
     */
    fun definirModeloAprendido(modelo: ModeloAprendido?) {
        modeloPendente = modelo
    }

    /** Teste de Atuadores: ignição ligada com o motor desligado (o `010C` manual devolve 0 rpm). */
    @Volatile private var motorDesligadoSimulado = false
    private val bateria = SimuladorBateria(random, relogioMs)
    private var cabecalhoEntradaManual = EcuSimulada.CABECALHO_FUNCIONAL

    /** Último instantâneo da simulação, para as respostas do modo 01/21 no terminal. */
    @Volatile private var ultimaLeitura: Map<String, Double> = emptyMap()

    private var codigos: List<String> = codigosIniciais
    private var codigosPendentes: List<String> = codigosPendentesIniciais

    /** Quadro congelado quando a primeira falha armazenada foi registrada (apagado pelo modo 04). */
    private var freezeFrame: FreezeFrame = freezeFrameDe(codigosIniciais.firstOrNull())

    /** A ECU simulada responde `NO DATA` ao `0151`, ao `0904` e ao TCM (vale já na próxima sondagem). */
    @Volatile
    var ecuSemImpressao: Boolean = ecuSemImpressao

    /** Permite demonstrar o tratamento de erro de conexão na apresentação. */
    @Volatile
    var simularFalhaNaConexao: Boolean = false

    /** Marca do veículo desta conexão (null = demonstração sem veículo). */
    @Volatile private var marcaConexao: String? = null

    /** VIN respondido nesta conexão (ver [vinSimulado]). */
    @Volatile private var vinConexao: String = VIN_PADRAO

    /** PIDs de montadora ativados nesta conexão. */
    private var estendidosAtivos: Set<String> = emptySet()

    // Estado interno da simulação (evolui a cada leitura)
    private var velocidade = 0.0
    private var velocidadeAlvo = 40.0
    private var temperatura = 35.0
    private var temperaturaOleo = 30.0
    private var nivelCombustivel = 62.0
    private var segundosMotorLigado = 0.0
    private var kmComLuzAcesa = KM_COM_LUZ_ACESA_INICIAL
    private var kmDesdeLimpeza = KM_DESDE_LIMPEZA_INICIAL

    /** Roteiro da arrancada: para o carro, espera parado e acelera com tudo até passar de 100 km/h. */
    private enum class FaseArrancada { NENHUMA, PARANDO, PARADO, ACELERANDO }

    @Volatile private var faseArrancada = FaseArrancada.NENHUMA
    private var segundosNaFase = 0.0

    /** A arrancada simulada está em andamento? */
    val arrancadaEmAndamento: Boolean get() = faseArrancada != FaseArrancada.NENHUMA

    override suspend fun listarDispositivos(): List<DispositivoObd> = listOf(
        DispositivoObd("OBDII Simulado (ELM327)", "00:00:00:00:00:01"),
        DispositivoObd("Scanner Reserva (simulado)", "00:00:00:00:00:02"),
    )

    override suspend fun conectar(dispositivo: DispositivoObd, marcaVeiculo: String?): Result<Unit> {
        _estado.value = EstadoConexaoObd.Conectando
        delay(latenciaMs)
        registro.limpar()
        registro.evento("Modo simulado: respostas ilustrativas de um ELM327 v1.5 em CAN 11/500")
        registro.comando("ATZ", "\r\rELM327 v1.5\r\r", 820)
        if (simularFalhaNaConexao) {
            registro.comando("0100", "SEARCHING...\rUNABLE TO CONNECT\r\r", 6_400)
            val msg = "Não foi possível conectar a ${dispositivo.nome} (falha simulada)"
            _estado.value = EstadoConexaoObd.Erro(msg)
            return Result.failure(ObdException(msg))
        }
        perfilSimulacao = perfilForcado ?: PerfilSimulacao.paraMarca(marcaVeiculo)
        ecu.perfil = perfilSimulacao
        vinConexao = vin ?: vinSimulado(perfilSimulacao, marcaVeiculo)
        marcaConexao = marcaVeiculo
        cruze.reiniciar()
        prepararReproducao()
        velocidade = 0.0
        velocidadeAlvo = 40.0
        temperatura = 35.0
        temperaturaOleo = 30.0
        segundosMotorLigado = 0.0
        faseArrancada = FaseArrancada.NENHUMA
        estendidosAtivos = emptySet()
        for (at in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")) registro.comando(at, "OK\r\r", 25)
        registro.comando("0100", "SEARCHING...\r4100BE3FB813\r\r", 1_350)
        registro.comando("ATDP", "AUTO, ISO 15765-4 (CAN 11/500)\r\r", 30)
        registro.comando("ATRV", "14.2V\r\r", 40)
        val comandos = comandosDoPerfil()
        if (perfilSimulacao == PerfilSimulacao.CRUZE_14T_FLEX) {
            registro.evento("Modo simulado: ${perfilSimulacao.titulo} (ATSP6 · motor 7E0 · TCM 7E1)")
        }
        _infoConexao.value = InfoConexao(
            protocolo = "AUTO, ISO 15765-4 (CAN 11/500)",
            sensoresResponderam = comandos.count { !it.startsWith("AT") },
        )
        _pidsSuportados.value = comandos
        _estado.value = EstadoConexaoObd.Conectado
        return Result.success(Unit)
    }

    /** Liga a reprodução dos logs reais se o modelo pendente tiver trecho com RPM e sensores padrão; senão, sintético. */
    private fun prepararReproducao() {
        val modelo = modeloPendente?.takeIf { it.utilizavel }
        val r = modelo?.let { ReprodutorTrechos(it.trechos) }?.takeUnless { it.vazio }
        val padrao = r?.comandos.orEmpty().filterTo(linkedSetOf()) { REGEX_PADRAO.matches(it) }
        if (modelo == null || r == null || Pids.RPM !in padrao) {
            reprodutor = null
            comandosAprendidos = emptySet()
            chavesAprendidas = emptySet()
            _fonteSimulacao.value = FonteSimulacao.Sintetica
            return
        }
        r.reiniciar()
        reprodutor = r
        comandosAprendidos = padrao
        chavesAprendidas = r.comandos
        _fonteSimulacao.value = FonteSimulacao.LogsReais(modelo.quantidadeLogs)
        registro.evento("Modo simulado: reproduzindo ${modelo.quantidadeLogs} log(s) real(is) do veículo")
    }

    override fun lerDadosEmTempoReal(intervaloMs: Long, comandos: Collection<String>?): Flow<DadosObd> = flow {
        val pedidos = comandos?.toSet()
        while (_estado.value == EstadoConexaoObd.Conectado) {
            if (_sessaoManual.value) {
                _sessaoManual.first { !it }
                continue
            }
            val leitura = proximaLeitura(PASSO_POLLING_SIMPLES_S)
            emit(DadosObd(if (pedidos == null) leitura else leitura.filterKeys { it in pedidos }))
            delay(intervaloMs)
        }
    }

    /** Mesmo escalonamento do cliente real: a grade se atualiza aos poucos, os mostradores sempre. */
    override fun lerTelemetria(
        intervaloMinimoMs: Long,
        consumoPorMaf: Boolean,
        lentosPorVez: Int,
        ciclosPorLento: Int,
    ): Flow<DadosObd> = flow {
        val escalonador = EscalonadorPolling(lentosPorVez, ciclosPorLento, Pids.candidatosFilaRapida(consumoPorMaf))
        val passoMs = intervaloMinimoMs.coerceAtLeast(INTERVALO_MINIMO_SIMULADO_MS)
        while (_estado.value == EstadoConexaoObd.Conectado) {
            if (_sessaoManual.value) {
                _sessaoManual.first { !it }
                continue
            }
            escalonador.atualizarSuportados(_pidsSuportados.value)
            val chaves = escalonador.proximoCiclo()
            val leitura = proximaLeitura(passoMs / 1000.0)
            emit(DadosObd(escalonador.consolidar(chaves, leitura.filterKeys { it in chaves })))
            delay(passoMs)
        }
    }

    override suspend fun ativarPerfilFabricante(marca: String?): ResultadoPerfil {
        val perfil = catalogoEstendido.perfilPara(marca) ?: return ResultadoPerfil.SemPerfil
        if (_estado.value != EstadoConexaoObd.Conectado) {
            return ResultadoPerfil.Indisponivel(perfil.nome, "scanner não conectado")
        }
        delay(latenciaMs)
        // Com logs reais, só entram os PIDs de montadora que o carro de fato respondeu nos CSVs.
        val ativos = perfil.pids.filter { if (reprodutor != null) it.comando in chavesAprendidas else it.comando in SIMULADOS_ESTENDIDOS }
        if (ativos.isEmpty()) return ResultadoPerfil.Indisponivel(perfil.nome, "a ECU não respondeu aos PIDs do perfil")
        estendidosAtivos = ativos.mapTo(linkedSetOf()) { it.comando }
        _pidsSuportados.value = _pidsSuportados.value + estendidosAtivos
        return ResultadoPerfil.Ativo(perfil.nome, ativos, perfil.observacao)
    }

    /**
     * Demonstração do cronômetro 0–100 km/h: o "motorista" para o carro, espera
     * ~[SEGUNDOS_PARADO_ARRANCADA] s e acelera com tudo (0–100 em ~12 s, como um 1.6 aspirado)
     * até passar de [VELOCIDADE_FIM_ARRANCADA] km/h; depois volta ao trânsito normal.
     */
    fun simularArrancada() {
        faseArrancada = FaseArrancada.PARANDO
        segundosNaFase = 0.0
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
        return Result.success(vinConexao)
    }

    /**
     * Impressão digital simulada, coerente com o carro (SW4 diesel, Cruze flex turbo, VW TSI ou
     * genérico aspirado, pelo VIN) e passando pelos mesmos parsers do cliente real; com
     * [ecuSemImpressao], `NO DATA` em tudo.
     */
    override suspend fun sondarEcu(sondarTcm: Boolean): ImpressaoEcu {
        if (_estado.value != EstadoConexaoObd.Conectado) return ImpressaoEcu.VAZIA
        val carro = CarroImpressao.de(vinConexao)
        val mudo = ecuSemImpressao
        suspend fun trocar(comando: String, resposta: String): String {
            delay(latenciaComandoMs)
            registro.comando(comando, resposta, latenciaComandoMs)
            return resposta
        }
        val combustivel = (ObdParser.parseCombustivelEcu(
            trocar("0151", if (mudo) NO_DATA else "41 51 %02X\r\r".format(carro.codigoCombustivel)),
        ) as? RespostaObd.Ok)?.valor
        val calid = (ObdParser.parseCalid(
            trocar("0904", if (mudo) NO_DATA else respostaCalid(carro.calid)),
        ) as? RespostaObd.Ok)?.valor
        var tcm: Boolean? = null
        var tcmCabecalho: String? = null
        if (sondarTcm) {
            registro.evento("Sondagem do TCM: ATSH 7E1 / 7E2 (cabeçalho do motor restaurado ao fim)")
            tcm = false
            for (cabecalho in listOf("7E1", "7E2")) {
                val bruto = trocar("0100 ($cabecalho)", if (!mudo && carro.temTcm && cabecalho == "7E1") "41 00 98 18 80 01\r\r" else NO_DATA)
                if (ObdParser.parsePidsSuportados(bruto, 0x00, "0100 ($cabecalho)") is RespostaObd.Ok) {
                    tcm = true
                    tcmCabecalho = cabecalho
                    break
                }
            }
        }
        return ImpressaoEcu(combustivel, calid, tcm, tcmCabecalho)
    }

    /** `0904` em CAN multi-frame (ISO-TP): `49 04 01` + o CALID de 16 bytes, no formato do ELM327 (`0:`, `1:`...). */
    private fun respostaCalid(calid: String): String {
        val bytes = listOf(0x49, 0x04, 0x01) + calid.padEnd(16, '\u0000').take(16).map { it.code }
        val quadros = buildList {
            add(bytes.take(6))
            addAll(bytes.drop(6).chunked(7))
        }
        return "%03X\r".format(bytes.size) + quadros.withIndex().joinToString("\r") { (i, q) ->
            "$i: " + q.joinToString(" ") { "%02X".format(it) }
        } + "\r\r"
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

    // ---------------------------------------------------------------- Ferramentas de Oficina

    /**
     * Fase do Teste de Bateria que o `ATRV` manual deve seguir (null = carro em trânsito, ~14 V).
     * [FaseBateriaSimulada.PARTIDA] começa o roteiro da partida a partir de agora.
     */
    fun simularBateria(fase: FaseBateriaSimulada?) = bateria.definir(fase)

    override suspend fun iniciarSessaoManual(): Result<Unit> {
        if (_estado.value != EstadoConexaoObd.Conectado) return Result.failure(naoConectado())
        if (!_sessaoManual.value) {
            cabecalhoEntradaManual = ecu.cabecalho
            _sessaoManual.value = true
            registro.evento("Sessão manual iniciada (Ferramentas de Oficina): polling pausado")
        }
        return Result.success(Unit)
    }

    override suspend fun encerrarSessaoManual() {
        if (!_sessaoManual.value) return
        ecu.cabecalho = cabecalhoEntradaManual
        ecu.filtroRecepcao = null
        ecu.reiniciarSessaoDiagnostico()
        motorDesligadoSimulado = false
        bateria.definir(null)
        _sessaoManual.value = false
        registro.evento("Sessão manual encerrada: polling retomado")
    }

    override suspend fun enviarComandoManual(comando: String, timeoutMs: Long, registrar: Boolean): Result<RespostaBruta> {
        val normalizado = ObdClient.normalizarComandoManual(comando)
            ?: return Result.failure(ObdException("Comando vazio, longo demais ou com caracteres inválidos"))
        if (_estado.value != EstadoConexaoObd.Conectado) return Result.failure(naoConectado())
        val inicio = relogioMs()
        val compacto = normalizado.filter { !it.isWhitespace() }
        val resposta = if (compacto == "ATRV") {
            bateria.responderAtrv()
        } else {
            ecu.responder(compacto, ::leituraParaComandoManual) { codigos }
        }
        if (latenciaComandoMs > 0) {
            // NO DATA custa o timeout do adaptador (ATST0C ≈ 49 ms) além da troca.
            val espera = if (resposta.startsWith("NO DATA")) ESPERA_NO_DATA_MS else 0L
            delay(latenciaComandoMs + random.nextLong(0, JITTER_COMANDO_MS) + espera)
        }
        val latencia = relogioMs() - inicio
        if (registrar) registro.comando(normalizado, resposta, latencia)
        return Result.success(RespostaBruta(normalizado, resposta, latencia))
    }

    /**
     * Teste de Atuadores no modo simulado: [ligado] = false simula a ignição ligada com o motor
     * parado (RPM e velocidade 0 nas respostas manuais), liberando os itens críticos.
     */
    fun simularMotor(ligado: Boolean) {
        motorDesligadoSimulado = !ligado
    }

    /** Teor de etanol do tanque do Cruze simulado (0–100 %), para demonstrar a detecção do Auto Flex. */
    fun simularEtanol(percentual: Double) {
        cruze.percentualEtanol = percentual.coerceIn(0.0, 100.0)
    }

    /** Teor de etanol atual do tanque do Cruze simulado (%). */
    val etanolSimulado: Double get() = cruze.percentualEtanol

    private fun leituraParaComandoManual(): Map<String, Double> =
        if (motorDesligadoSimulado) ultimaLeitura + mapOf(Pids.RPM to 0.0, Pids.VELOCIDADE to 0.0) else ultimaLeitura

    private fun comandosDoPerfil(): Set<String> = when {
        reprodutor != null -> comandosAprendidos
        perfilSimulacao == PerfilSimulacao.CRUZE_14T_FLEX -> SimuladorCruze.COMANDOS
        // Aspirado de injeção indireta (marca informada, fora Toyota/GM/VW): sem o rail absoluto (0123) da GDI/diesel.
        // Sem marca (conexão de demonstração/testes antigos) segue o catálogo completo.
        marcaConexao != null && CarroImpressao.de(vinConexao) == CarroImpressao.GENERICO_ASPIRADO -> COMANDOS_SIMULADOS - "0123"
        else -> COMANDOS_SIMULADOS
    }

    override suspend fun desconectar() {
        _sessaoManual.value = false
        _pidsSuportados.value = emptySet()
        _infoConexao.value = InfoConexao()
        estendidosAtivos = emptySet()
        faseArrancada = FaseArrancada.NENHUMA
        _estado.value = EstadoConexaoObd.Desconectado
    }

    private suspend fun lerDtcs(fonte: () -> List<String>): Result<List<DtcLido>> {
        if (_estado.value != EstadoConexaoObd.Conectado) return Result.failure(naoConectado())
        delay(latenciaMs)
        return Result.success(fonte().map { DtcLido(it, DescricaoDtc.descricaoDe(it)) })
    }

    private fun naoConectado() = ObdException("Scanner não conectado")

    /** Nova velocidade após [dt] segundos; devolve a aceleração em km/h por segundo. */
    private fun avancarVelocidade(dt: Double): Double {
        val anterior = velocidade
        when (faseArrancada) {
            FaseArrancada.NENHUMA -> {
                // De vez em quando escolhe uma nova velocidade-alvo (trânsito: para, acelera, freia).
                if (random.nextDouble() < CHANCE_NOVO_ALVO_POR_S * dt) velocidadeAlvo = random.nextInt(0, 111).toDouble()
                val delta = (velocidadeAlvo - velocidade).coerceIn(-12.0 * dt, 8.0 * dt)
                velocidade = (velocidade + delta + random.nextDouble(-1.0, 1.0) * dt).coerceIn(0.0, 180.0)
            }
            FaseArrancada.PARANDO -> {
                velocidade = (velocidade - 12.0 * dt).coerceAtLeast(0.0)
                if (velocidade == 0.0) mudarFase(FaseArrancada.PARADO)
            }
            FaseArrancada.PARADO -> {
                velocidade = 0.0
                segundosNaFase += dt
                if (segundosNaFase >= SEGUNDOS_PARADO_ARRANCADA) mudarFase(FaseArrancada.ACELERANDO)
            }
            FaseArrancada.ACELERANDO -> {
                // Aceleração cai com a velocidade (arrasto + marchas mais longas): ~11 → 6 km/h/s.
                val aceleracao = (11.0 - velocidade * 0.05).coerceAtLeast(3.0) + random.nextDouble(-0.3, 0.3)
                velocidade += aceleracao * dt
                if (velocidade >= VELOCIDADE_FIM_ARRANCADA) {
                    velocidadeAlvo = 60.0
                    mudarFase(FaseArrancada.NENHUMA)
                }
            }
        }
        return (velocidade - anterior) / dt
    }

    private fun mudarFase(fase: FaseArrancada) {
        faseArrancada = fase
        segundosNaFase = 0.0
    }

    /** Avança a simulação [dt] segundos de condução e devolve todos os PIDs "lidos" da ECU. */
    private fun proximaLeitura(dt: Double): Map<String, Double> {
        reprodutor?.let { return leituraAprendida(it, dt) }
        val aceleracao = avancarVelocidade(dt)

        // Temperatura de trabalho: ~90 °C no genérico; ~97 °C no Cruze (termostato GM ~95–105 °C).
        val tetoTemperatura = if (perfilSimulacao == PerfilSimulacao.CRUZE_14T_FLEX) 97.0 else 90.0
        temperatura = (temperatura + random.nextDouble(1.0, 3.0) * dt).coerceAtMost(tetoTemperatura + random.nextDouble(-1.0, 1.0))
        // O óleo esquenta mais devagar que o líquido de arrefecimento e estabiliza um pouco acima dele.
        temperaturaOleo += (temperatura + 6.0 - temperaturaOleo) * (0.06 * dt).coerceAtMost(1.0)

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

        segundosMotorLigado += dt
        val kmNoPasso = velocidade / 3600.0 * dt
        kmDesdeLimpeza += kmNoPasso
        if (codigos.isNotEmpty()) kmComLuzAcesa += kmNoPasso
        nivelCombustivel = (nivelCombustivel - consumoLh / 3600.0 * dt / TANQUE_LITROS * 100.0).coerceAtLeast(0.0)

        if (perfilSimulacao == PerfilSimulacao.CRUZE_14T_FLEX) {
            val lc = cruze.leitura(
                dt, velocidade, aceleracao, temperatura, temperaturaOleo, segundosMotorLigado,
                (nivelCombustivel + random.nextDouble(-0.4, 0.4)).coerceIn(0.0, 100.0), kmComLuzAcesa, kmDesdeLimpeza,
            )
            val consumoCruze = cruze.litrosPorHora(lc[Pids.MAF] ?: 0.0)
            nivelCombustivel = (nivelCombustivel - (consumoCruze - consumoLh) / 3600.0 * dt / TANQUE_LITROS * 100.0).coerceAtLeast(0.0)
            val filtrada = lc.filterKeys { it in SimuladorCruze.COMANDOS || it in estendidosAtivos }
            ultimaLeitura = filtrada
            return filtrada
        }

        val leitura = linkedMapOf(
            Pids.CARGA_MOTOR to carga.arredondar(1),
            Pids.TEMPERATURA_MOTOR to temperatura.roundToInt().toDouble(),
            Pids.STFT to random.nextDouble(-4.0, 4.0).arredondar(1), // correção rápida em torno de 0
            Pids.LTFT to (2.3 + random.nextDouble(-0.2, 0.2)).arredondar(1), // adaptação estável
            "010A" to (PRESSAO_LINHA_COMBUSTIVEL + random.nextDouble(-9.0, 9.0)).roundToInt().toDouble(),
            Pids.PRESSAO_COLETOR to map.roundToInt().toDouble(),
            Pids.RPM to rpm.roundToInt().toDouble(),
            Pids.VELOCIDADE to velocidade.roundToInt().toDouble(),
            "010E" to avanco.arredondar(1),
            Pids.TEMPERATURA_AR_ADMISSAO to (28.0 + (temperatura - 35.0) * 0.12 + random.nextDouble(-0.5, 0.5)).roundToInt().toDouble(),
            Pids.MAF to maf.arredondar(2),
            Pids.BORBOLETA to borboleta.arredondar(1),
            "0114" to sonda.arredondar(3),
            "011F" to segundosMotorLigado.roundToInt().toDouble(),
            "0121" to kmComLuzAcesa.roundToInt().toDouble(),
            "0123" to (4000.0 + carga * 90.0 + random.nextDouble(-150.0, 150.0)).roundToInt().toDouble(),
            // Sonda de banda larga: segue o λ comandado com o erro da malha fechada (v2.1).
            Pids.LAMBDA_BANDA_LARGA to (lambda + random.nextDouble(-0.015, 0.015)).arredondar(3),
            "012F" to (nivelCombustivel + random.nextDouble(-0.4, 0.4)).coerceIn(0.0, 100.0).arredondar(1),
            "0130" to CICLOS_AQUECIMENTO_SIMULADOS,
            "0131" to kmDesdeLimpeza.roundToInt().toDouble(),
            "0133" to PRESSAO_BAROMETRICA,
            Pids.LAMBDA_BANDA_LARGA_CORRENTE to (lambda + random.nextDouble(-0.015, 0.015)).arredondar(3),
            // Catalisador: acompanha o aquecimento do motor e a carga (v2.2.9).
            "013C" to (temperatura * 3.5 + carga * 2.5 + random.nextDouble(-4.0, 4.0)).roundToInt().toDouble(),
            "0142" to (tensao - 0.2).arredondar(2),
            Pids.LAMBDA_COMANDADO to lambda.arredondar(3),
            "0145" to (borboleta - BORBOLETA_FECHADA).coerceAtLeast(0.0).arredondar(1),
            "0146" to TEMPERATURA_AMBIENTE,
            "0149" to pedal.arredondar(1),
            "014A" to (pedal / 2 + DESVIO_PEDAL_E).arredondar(1),
            "014D" to 0.0,
            "014E" to (MINUTOS_DESDE_LIMPEZA_INICIAIS + segundosMotorLigado / 60).roundToInt().toDouble(),
            Pids.PERCENTUAL_ETANOL to PERCENTUAL_ETANOL_GASOLINA_C,
            "015C" to temperaturaOleo.roundToInt().toDouble(),
            Pids.CONSUMO_ECU to consumoLh.arredondar(1),
            Pids.TENSAO_BATERIA to tensao.arredondar(1),
        )
        if (estendidosAtivos.isNotEmpty()) {
            leitura += leituraEstendida(map, pedal, aceleracao).filterKeys { it in estendidosAtivos }
        }
        ultimaLeitura = leitura
        return leitura
    }

    /** Próximo instante dos trechos reais, só com os PIDs que a "ECU" anunciou (padrão + perfil ativado). */
    private fun leituraAprendida(r: ReprodutorTrechos, dt: Double): Map<String, Double> {
        segundosMotorLigado += dt
        val suportados = _pidsSuportados.value
        val leitura = r.proxima(dt).filterKeys { it in suportados }
        leitura[Pids.VELOCIDADE]?.let { velocidade = it }
        ultimaLeitura = leitura
        return leitura
    }

    /**
     * Valores dos PIDs de montadora simulados: ATF segue o óleo; injetores com desvios fixos; pedal
     * APP D/E (o E ~16 pontos acima); marcha pela velocidade (0xFF = parado) com a alavanca em D.
     */
    private fun leituraEstendida(map: Double, pedal: Double, aceleracao: Double): Map<String, Double> = mapOf(
        "TOYOTA_PEDAL_D" to pedal.arredondar(1),
        "TOYOTA_PEDAL_E" to (pedal + EcuSimulada.DESVIO_APP_E).coerceAtMost(100.0).arredondar(1),
        "TOYOTA_MARCHA" to EcuSimulada.marchaSimulada(velocidade).toDouble(),
        "TOYOTA_ALAVANCA" to EcuSimulada.ALAVANCA_D.toDouble(),
        "TOYOTA_ATF_CAMBIO" to (temperaturaOleo - 4.0 + random.nextDouble(-0.5, 0.5)).roundToInt().toDouble(),
        "TOYOTA_TENSAO_ECU" to (14.18 + random.nextDouble(-0.1, 0.1)).arredondar(2),
        "TOYOTA_BARO" to PRESSAO_BAROMETRICA - 3.0, // 92 kPa, como na captura real da SW4
        "TOYOTA_INJ1" to (0.45 + random.nextDouble(-0.02, 0.02)).arredondar(2),
        "TOYOTA_INJ2" to (0.44 + random.nextDouble(-0.02, 0.02)).arredondar(2),
        "TOYOTA_INJ3" to (0.66 + random.nextDouble(-0.02, 0.02)).arredondar(2),
        "TOYOTA_INJ4" to (random.nextDouble(-0.02, 0.02)).arredondar(2),
        "VW_TEMP_OLEO" to temperaturaOleo.roundToInt().toDouble(),
        "VW_TURBO_REAL" to map.roundToInt().toDouble(),
        "VW_TURBO_ALVO" to (map + random.nextDouble(0.0, 3.0)).roundToInt().toDouble(),
    )

    private fun Double.arredondar(casas: Int): Double {
        var fator = 1.0
        repeat(casas) { fator *= 10 }
        return (this * fator).roundToInt() / fator
    }

    companion object {
        /** VIN de teste clássico (VW, WMI `9BW`). */
        const val VIN_PADRAO = "9BWZZZ377VT004251"

        /** Cruze 2018 (fabricado na Argentina, WMI `8AG`; `J` = 2018). */
        const val VIN_CRUZE = "8AGBN69S0JR104567"

        /** Hilux SW4 2013 (Argentina, WMI `8AJ`; `D` = 2013). */
        const val VIN_TOYOTA = "8AJFZ29G4D6012345"

        /** VW Polo 1.0 TSI 2019 (WMI `9BW` + VDS `AG5BZ9`; `K` = 2019), casa com `^9BWA[GHJ]`. */
        const val VIN_VW_TSI = "9BWAG5BZ9KP012345"

        private const val NO_DATA = "NO DATA\r\r"

        /** PID padrão (`01xx`) ou a tensão (`ATRV`): o que a ECU anuncia sem perfil de montadora. */
        private val REGEX_PADRAO = Regex("^(01[0-9A-F]{2}|ATRV)$")

        /** VIN coerente com o carro simulado, para a detecção por VIN (`0902`) funcionar no Modo Simulado. */
        fun vinSimulado(perfil: PerfilSimulacao, marca: String?): String = when {
            perfil == PerfilSimulacao.CRUZE_14T_FLEX -> VIN_CRUZE
            marca?.contains("toyota", ignoreCase = true) == true -> VIN_TOYOTA
            marca?.let { Regex("\\b(volkswagen|vw|volks)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) } == true -> VIN_VW_TSI
            else -> VIN_PADRAO
        }

        /** PIDs que a "ECU" simulada suporta: todos os do catálogo padrão (`pids_padrao.json`). */
        val COMANDOS_SIMULADOS: Set<String> = setOf(
            "0104", "0105", "0106", "0107", "010A", "010B", "010C", "010D", "010E", "010F",
            "0110", "0111", "0114", "011F", "0121", "0123", "0124", "012F", "0130", "0131", "0133", "0134", "013C",
            "0142", "0144", "0145", "0146", "0149", "014A", "014D", "014E", "0152", "015C", "015E", "ATRV",
        )

        /** Contadores do checkup (v2.2.9): ciclos de aquecimento e minutos de motor desde a limpeza dos códigos. */
        const val CICLOS_AQUECIMENTO_SIMULADOS = 42.0
        const val MINUTOS_DESDE_LIMPEZA_INICIAIS = 780.0

        /** Pedal E (`014A`): metade do curso do D somada a este desvio, como nos sensores duplos reais. */
        const val DESVIO_PEDAL_E = 7.8

        /**
         * PIDs de montadora que a ECU simulada responde. `VW_TEMP_DSG` fica de fora de propósito:
         * demonstra que só entram no painel os PIDs a que a ECU responde (um Gol não tem DSG).
         */
        val SIMULADOS_ESTENDIDOS: Set<String> = setOf(
            "TOYOTA_ATF_CAMBIO", "TOYOTA_INJ1", "TOYOTA_INJ2", "TOYOTA_INJ3", "TOYOTA_INJ4",
            // SW4 (1KD-FTV + A750F): motor 7E0 (pedal, tensão da ECU, barômetro, bicos) e câmbio 7E1
            // (marcha, alavanca + ATF). O 4x4 (7C0), o VSC (7B0) e as variantes pelo motor (_ECM) ficam
            // de fora: mostram "N/D" como na SW4 real, onde esses módulos não respondem.
            "TOYOTA_PEDAL_D", "TOYOTA_PEDAL_E", "TOYOTA_TENSAO_ECU", "TOYOTA_BARO", "TOYOTA_MARCHA", "TOYOTA_ALAVANCA",
            "VW_TEMP_OLEO", "VW_TURBO_REAL", "VW_TURBO_ALVO",
            // Cruze 1.4T (LE2): o TCM 7E1 responde ao 22 1940; o 7E2 (alternativa) fica mudo, como num Cruze real.
            "GM_ATF",
        )

        const val VELOCIDADE_FIM_ARRANCADA = 115.0
        private const val ESPERA_NO_DATA_MS = 45L
        private const val JITTER_COMANDO_MS = 20L
        private const val SEGUNDOS_PARADO_ARRANCADA = 2.0
        private const val PASSO_POLLING_SIMPLES_S = 1.0

        /** Piso do ciclo da telemetria simulada (~10 atualizações/s, como um ELM327 rápido). */
        const val INTERVALO_MINIMO_SIMULADO_MS = 100L

        const val MENSAGEM_ECU_MUDA = "A ECU parou de responder (NO DATA). A ignição foi desligada? (simulado)"
        private const val CHANCE_NOVO_ALVO_POR_S = 0.12
        private const val FRENAGEM_FORTE_KMH_S = -10.0
        private const val VSC_ATUANDO = 1.0
        private const val BORBOLETA_FECHADA = 8.0 // % de abertura da borboleta em marcha lenta
        private const val PRESSAO_BAROMETRICA = 95.0 // kPa (~600 m de altitude)
        private const val PRESSAO_LINHA_COMBUSTIVEL = 380.0 // kPa, injeção indireta
        private const val TEMPERATURA_AMBIENTE = 24.0
        private const val PERCENTUAL_ETANOL_GASOLINA_C = 27.0 // gasolina comum brasileira (E27)
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

/** Carro simulado para a impressão digital da ECU (`0151`, `0904`, TCM), escolhido pelo VIN da conexão. */
private enum class CarroImpressao(val codigoCombustivel: Int, val calid: String, val temTcm: Boolean) {
    /** Hilux SW4 3.0 D-4D: diesel (`04`), A750F no `7E1`. */
    SW4_DIESEL(0x04, "TY1KDFTV16A750F1", true),

    /** Cruze 1.4 Turbo Flex: bicombustível gasolina/etanol (`09`), 6T35 no `7E1`. */
    CRUZE_FLEX_TURBO(0x09, "GM6T35LE2CAL018A", true),

    /** VW TSI: bicombustível (`09`), câmbio automático Aisin no `7E1`. */
    VW_TSI(0x09, "VWEA211TSI0019AB", true),

    /** Aspirado de injeção indireta, câmbio manual: gasolina (`01`), sem TCM. */
    GENERICO_ASPIRADO(0x01, "GENASP0000000001", false),
    ;

    companion object {
        fun de(vin: String): CarroImpressao = when {
            vin.startsWith("8AJ") -> SW4_DIESEL
            vin.startsWith("8AG") -> CRUZE_FLEX_TURBO
            vin.startsWith("9BWA") || vin.startsWith("9BWD") -> VW_TSI
            else -> GENERICO_ASPIRADO
        }
    }
}
