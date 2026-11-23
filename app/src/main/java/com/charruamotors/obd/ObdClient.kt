package com.charruamotors.obd

import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.obd.log.EntradaRegistro
import com.charruamotors.obd.pid.Pids
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Contrato de comunicação com o scanner OBD2. A UI/ViewModel depende apenas desta interface,
 * então pode usar o [ObdClientFake] (emulador/apresentação) ou o `Elm327BluetoothClient` (scanner real).
 *
 * Operações que podem falhar devolvem [Result] com [ObdException]; elas nunca lançam
 * na main thread e a UI nunca é bloqueada (toda I/O roda fora dela).
 */
interface ObdClient {

    /** Estado atual da conexão; a UI observa para habilitar/desabilitar ações. */
    val estado: StateFlow<EstadoConexaoObd>

    /**
     * Comandos do catálogo que o veículo conectado suporta (ex.: `010C`, `015C`, `ATRV`),
     * detectados na conexão pelos bitmasks `0100`/`0120`/`0140`. Vazio enquanto desconectado.
     */
    val pidsSuportados: StateFlow<Set<String>>

    /** Protocolo (`ATDP`) e camada da cascata (OBD2 padrão, modo 21 ou modo 22) da conexão atual. */
    val infoConexao: StateFlow<InfoConexao>

    /** Scanners disponíveis para escolha (pareados, no caso do Bluetooth real). */
    suspend fun listarDispositivos(): List<DispositivoObd>

    /**
     * Abre a conexão e inicializa o adaptador. Atualiza [estado] (Conectando → Conectado/Erro).
     * [marcaVeiculo] decide os fallbacks de montadora quando a ECU não fala o OBD2 padrão
     * (a ordem dos modos 21/22 da cascata); null = marca desconhecida.
     */
    suspend fun conectar(dispositivo: DispositivoObd, marcaVeiculo: String? = null): Result<Unit>

    /**
     * Fluxo frio de leituras em tempo real, emitindo um [DadosObd] a cada [intervaloMs] com os
     * [comandos] pedidos que o veículo suporta (`null` = todos os suportados). Menos comandos =
     * ciclos mais rápidos no ELM327. Termina se a conexão for perdida.
     * Cancelar a coleta (ex.: `onPause`/`onStop`) interrompe o polling.
     */
    fun lerDadosEmTempoReal(
        intervaloMs: Long = INTERVALO_PADRAO_MS,
        comandos: Collection<String>? = Pids.PAINEL,
    ): Flow<DadosObd>

    /**
     * Telemetria ao vivo em duas filas ([EscalonadorPolling]): RPM, MAP/turbo e velocidade (mais o
     * MAF, se [consumoPorMaf]) em todo ciclo e os demais PIDs suportados (inclusive os de
     * montadora ativados) em rodízio, [lentosPorVez] a cada [ciclosPorLento] ciclos. Cada emissão
     * é o instantâneo completo com o último valor conhecido de cada PID. Um ciclo começa a cada
     * [intervaloMinimoMs] no mínimo; no ELM327 real quem dita o ritmo é a velocidade do
     * Bluetooth/ECU. Termina se a conexão for perdida.
     */
    fun lerTelemetria(
        intervaloMinimoMs: Long = INTERVALO_TELEMETRIA_MS,
        consumoPorMaf: Boolean = true,
        lentosPorVez: Int = EscalonadorPolling.LENTOS_POR_VEZ,
        ciclosPorLento: Int = EscalonadorPolling.CICLOS_POR_LENTO,
    ): Flow<DadosObd>

    /**
     * Ativa, se possível, o perfil de PIDs da montadora do veículo (`pids_estendidos.json`):
     * exige perfil para a [marca], protocolo CAN de 11 bits e resposta positiva da ECU a cada
     * requisição. Os PIDs ativados entram em [pidsSuportados] e na fila lenta da telemetria.
     * Nunca lança: falhas viram [ResultadoPerfil.Indisponivel].
     */
    suspend fun ativarPerfilFabricante(marca: String?): ResultadoPerfil

    /** Lê os códigos de falha armazenados/confirmados (modo 03). Lista vazia = nenhum código. */
    suspend fun lerCodigosFalha(): Result<List<DtcLido>>

    /** Lê os códigos pendentes, ainda não confirmados pela ECU (modo 07). */
    suspend fun lerCodigosPendentes(): Result<List<DtcLido>>

    /** Lê os códigos permanentes, que o modo 04 não apaga (modo 0A). */
    suspend fun lerCodigosPermanentes(): Result<List<DtcLido>>

    /** Lê o Freeze Frame (modo 02): RPM, velocidade, temperatura e carga no momento da falha. */
    suspend fun lerFreezeFrame(): Result<FreezeFrame>

    /** Lê o VIN / número do chassi (modo 09, PID 02). */
    suspend fun lerVin(): Result<String>

    /** Limpa os códigos de falha (modo 04). A UI deve sempre pedir confirmação antes. */
    suspend fun limparCodigosFalha(): Result<Unit>

    /**
     * Fecha a conexão e libera recursos. Seguro chamar mais de uma vez, durante a conexão
     * (cancela) ou com um comando em andamento (o socket é fechado na hora).
     */
    suspend fun desconectar()

    /**
     * Últimas trocas com o adaptador (comandos, respostas brutas, timeouts e eventos), para o
     * diagnóstico de campo quando algo não lê no carro. Cópia; a mais antiga primeiro.
     */
    fun registroComunicacao(): List<EntradaRegistro> = emptyList()

    /**
     * Passa a entregar cada nova entrada do registro de comunicação a [ouvinte] (null = parar),
     * sem o limite do buffer circular: a sessão OBD grava assim o log bruto completo no `.txt`.
     */
    fun observarRegistro(ouvinte: ((EntradaRegistro) -> Unit)?) {}

    /** Log completo de tráfego: entrega ao ouvinte também as trocas do polling e os valores decodificados. */
    fun definirLogCompleto(ativo: Boolean) {}

    // ---------------------------------------------------------------- Ferramentas de Oficina

    /**
     * Sessão manual em andamento (teste de bateria, terminal, varredura de PIDs): enquanto true, os
     * fluxos de [lerTelemetria] e [lerDadosEmTempoReal] ficam suspensos entre ciclos — inclusive o
     * polling do HUD flutuante —, sem fechar o socket.
     */
    val sessaoManualAtiva: StateFlow<Boolean>

    /**
     * Pausa o polling (espera o ciclo em andamento terminar) e guarda o estado do adaptador
     * (cabeçalho `ATSH` ativo) para [encerrarSessaoManual] restaurar. Falha se desconectado.
     */
    suspend fun iniciarSessaoManual(): Result<Unit>

    /**
     * Restaura o adaptador (configuração `ATE0 ATL0 ATS0 ATH0`/`ATAT2` se um comando manual ou um
     * `LV RESET` a mudou, e o cabeçalho `ATSH` da entrada) e libera o polling. Idempotente; roda
     * mesmo com o chamador cancelado.
     */
    suspend fun encerrarSessaoManual()

    /**
     * Envia um comando bruto (`ATRV`, `ATSH 7E1`, `2101`, `13`...) exatamente como digitado (só
     * maiúsculas e sem espaços nas pontas) e devolve a resposta crua e a latência. Sem `ATSH`
     * automático: o cabeçalho é o que estiver no adaptador. `ATSH`/`ATST`/`ATZ` manuais são
     * acompanhados para o polling continuar correto depois. Timeout = [RespostaBruta.semPrompt].
     * @param registrar grava a troca no registro de comunicação (o teste de bateria, a 20 Hz, não grava).
     */
    suspend fun enviarComandoManual(
        comando: String,
        timeoutMs: Long = TIMEOUT_MANUAL_MS,
        registrar: Boolean = true,
    ): Result<RespostaBruta>

    companion object {
        const val INTERVALO_PADRAO_MS = 1000L

        /** Prazo padrão de um comando manual (terminal/varredura). */
        const val TIMEOUT_MANUAL_MS = 2_000L

        /** Maior comando manual aceito (o ELM327 tem buffer de entrada de ~64 caracteres). */
        const val MAX_COMANDO_MANUAL = 48

        /**
         * Normaliza um comando digitado: sem espaços nas pontas, maiúsculas; null se vazio, longo
         * demais ou com caracteres fora do ASCII imprimível (um `\r` no meio enviaria dois comandos).
         */
        fun normalizarComandoManual(comando: String): String? {
            val c = comando.trim().uppercase()
            if (c.isEmpty() || c.length > MAX_COMANDO_MANUAL) return null
            if (c.any { it !in ' '..'~' }) return null
            return c
        }

        /**
         * Período mínimo do ciclo da telemetria em duas filas: só um piso; no ELM327 real o ciclo
         * dura o que os comandos da fila rápida levam no Bluetooth/ECU.
         */
        const val INTERVALO_TELEMETRIA_MS = 50L
    }
}
