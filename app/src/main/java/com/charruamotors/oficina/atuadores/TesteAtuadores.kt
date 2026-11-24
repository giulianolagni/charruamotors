package com.charruamotors.oficina.atuadores

import com.charruamotors.obd.RespostaBruta
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Etapas da ferramenta (a tela habilita os botões por elas). */
enum class EtapaAtuadores { INATIVA, PREPARANDO, PRONTA, EXECUTANDO, ENCERRADA }

/** Como ficou a abertura da sessão de diagnóstico estendida (`10 03`). */
sealed interface ResultadoSessao {
    data object Estendida : ResultadoSessao

    /** A central recusou o `10 03`: os testes ainda são enviados, e a central dirá o que aceita. */
    data class Recusada(val resposta: RespostaUds.Negativa) : ResultadoSessao

    data class SemResposta(val motivo: String) : ResultadoSessao
}

/** Resultado de um teste de atuador. */
sealed interface ResultadoAtuador {
    val titulo: String

    data class Sucesso(override val titulo: String, val comando: String, val resposta: String) : ResultadoAtuador

    /** A central respondeu `7F 2F <NRC>` (Seed/Key `33`, não suportado `11`, fora da faixa `31`...). */
    data class Recusado(override val titulo: String, val comando: String, val negativa: RespostaUds.Negativa) : ResultadoAtuador

    /** Item crítico com o motor girando (ou sem como confirmar que está parado): nada foi enviado. */
    data class BloqueadoMotor(override val titulo: String, val rpm: Double?) : ResultadoAtuador

    data class SemResposta(override val titulo: String, val comando: String, val motivo: String) : ResultadoAtuador

    data class Falha(override val titulo: String, val mensagem: String) : ResultadoAtuador

    /** Interrompido pelo usuário (Parar): o controle já foi devolvido à central. */
    data class Interrompido(override val titulo: String) : ResultadoAtuador
}

/**
 * Máquina de comandos do Teste de Atuadores (UDS `2F`), pura e testável: recebe o envio de
 * comandos manuais ([enviar] — no app, `ObdClient.enviarComandoManual` numa sessão manual, com o
 * polling pausado) e a última leitura de RPM conhecida antes da pausa ([rpmConhecido]).
 *
 * Fluxo: [preparar] fixa o cabeçalho da ECU (`ATSH 7E0`) e abre a sessão estendida (`10 03`);
 * [manterSessao] envia o *Tester Present* (`3E 00`), que a tela repete a cada [INTERVALO_TESTER_PRESENT_MS]
 * e que também é enviado durante pulsos longos; [executar] confere o RPM (itens críticos só com
 * RPM = 0), ativa (`2F <DID> 03 <estado>`), espera e sempre devolve o controle (`2F <DID> 00`),
 * mesmo cancelado; [encerrar] volta à sessão padrão (`10 01`). Uma recusa (`7F 2F xx`) vira um
 * [ResultadoAtuador.Recusado] com a explicação do NRC — nunca uma exceção que derrube a sessão.
 */
class TesteAtuadores(
    private val enviar: suspend (String) -> RespostaBruta?,
    private val rpmConhecido: () -> Double? = { null },
    private val esperar: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()

    @Volatile var etapa: EtapaAtuadores = EtapaAtuadores.INATIVA
        private set

    @Volatile var sessaoEstendida: Boolean = false
        private set

    /** Último RPM lido da ECU (`010C`) ou, sem resposta, o conhecido antes da pausa. */
    @Volatile var ultimoRpm: Double? = null
        private set

    private var cabecalhoAtivo: String? = null

    suspend fun preparar(cabecalho: String): ResultadoSessao = mutex.withLock {
        etapa = EtapaAtuadores.PREPARANDO
        fixarCabecalho(cabecalho)
        val resultado = when (val r = pedir(COMANDO_SESSAO_ESTENDIDA, SERVICO_SESSAO)) {
            is RespostaUds.Positiva -> ResultadoSessao.Estendida
            is RespostaUds.Negativa -> ResultadoSessao.Recusada(r)
            is RespostaUds.SemResposta -> ResultadoSessao.SemResposta(r.motivo)
            is RespostaUds.Invalida -> ResultadoSessao.SemResposta("resposta inválida: ${r.bruto}")
        }
        sessaoEstendida = resultado == ResultadoSessao.Estendida
        etapa = EtapaAtuadores.PRONTA
        resultado
    }

    /** `3E 00` (Tester Present): mantém a sessão estendida viva. True = a central confirmou (`7E 00`). */
    suspend fun manterSessao(): Boolean = mutex.withLock { enviarTesterPresent() }

    /** Confere o RPM pela ECU (`010C`); sem resposta, vale o último conhecido do polling. */
    suspend fun lerRpm(): Double? = mutex.withLock { lerRpmSemLock() }

    /** Teste de um atuador da tabela; [duracaoMs] null = a do atuador. */
    suspend fun executar(atuador: Atuador, comando: ComandoAtuador?, duracaoMs: Long? = null): ResultadoAtuador {
        if (comando == null) return ResultadoAtuador.Falha(atuador.titulo, "Sem DID mapeado para este atuador nesta central.")
        return executarComando(atuador.titulo, comando, atuador.critico, duracaoMs ?: atuador.duracaoMs)
    }

    /**
     * Comando customizado (`2F <DID> <controle> <estado>`): sempre tratado como crítico (RPM = 0),
     * porque o app não sabe o que o DID aciona.
     */
    suspend fun executarCustomizado(comando: ComandoAtuador, duracaoMs: Long): ResultadoAtuador =
        executarComando("Comando customizado 2F ${comando.did}", comando, critico = true, duracaoMs = duracaoMs)

    private suspend fun executarComando(titulo: String, comando: ComandoAtuador, critico: Boolean, duracaoMs: Long): ResultadoAtuador =
        mutex.withLock {
            if (etapa == EtapaAtuadores.INATIVA || etapa == EtapaAtuadores.ENCERRADA) {
                return@withLock ResultadoAtuador.Falha(titulo, "Sessão de teste não iniciada.")
            }
            if (critico) {
                val rpm = lerRpmSemLock()
                if (rpm == null || rpm >= RPM_MOTOR_PARADO) return@withLock ResultadoAtuador.BloqueadoMotor(titulo, rpm)
            }
            etapa = EtapaAtuadores.EXECUTANDO
            try {
                fixarCabecalho(comando.cabecalho)
                when (val r = pedir(comando.ativar, SERVICO_ATUADOR)) {
                    is RespostaUds.Positiva -> {
                        manterAtivo(duracaoMs)
                        ResultadoAtuador.Sucesso(titulo, comando.ativar, r.bytes.joinToString(" ") { "%02X".format(it) })
                    }
                    is RespostaUds.Negativa -> ResultadoAtuador.Recusado(titulo, comando.ativar, r)
                    is RespostaUds.SemResposta -> ResultadoAtuador.SemResposta(titulo, comando.ativar, r.motivo)
                    is RespostaUds.Invalida -> ResultadoAtuador.SemResposta(titulo, comando.ativar, "resposta inválida: ${r.bruto}")
                }
            } finally {
                // Sempre devolve o controle à central — inclusive se o usuário tocou em Parar.
                withContext(NonCancellable) { pedir(comando.devolver, SERVICO_ATUADOR) }
                etapa = EtapaAtuadores.PRONTA
            }
        }

    /** Volta à sessão padrão (`10 01`). Idempotente; roda mesmo com o chamador cancelado. */
    suspend fun encerrar() {
        withContext(NonCancellable) {
            mutex.withLock {
                if (etapa == EtapaAtuadores.INATIVA || etapa == EtapaAtuadores.ENCERRADA) {
                    etapa = EtapaAtuadores.ENCERRADA
                    return@withLock
                }
                if (sessaoEstendida) pedir(COMANDO_SESSAO_PADRAO, SERVICO_SESSAO)
                sessaoEstendida = false
                etapa = EtapaAtuadores.ENCERRADA
            }
        }
    }

    /** Espera o pulso em fatias, com o Tester Present no meio (a sessão estendida cai em ~5 s sem ele). */
    private suspend fun manterAtivo(duracaoMs: Long) {
        var restante = duracaoMs.coerceAtLeast(0)
        while (restante > 0) {
            val fatia = restante.coerceAtMost(INTERVALO_TESTER_PRESENT_MS)
            esperar(fatia)
            restante -= fatia
            if (restante > 0 && sessaoEstendida) enviarTesterPresent()
        }
    }

    private suspend fun enviarTesterPresent(): Boolean =
        pedir(COMANDO_TESTER_PRESENT, SERVICO_TESTER_PRESENT) is RespostaUds.Positiva

    private suspend fun lerRpmSemLock(): Double? {
        val bruto = enviar(COMANDO_RPM)
        val rpm = bruto?.let { parseRpm(it.resposta) } ?: rpmConhecido()
        ultimoRpm = rpm
        return rpm
    }

    private suspend fun fixarCabecalho(cabecalho: String) {
        if (cabecalhoAtivo == cabecalho) return
        val r = enviar("ATSH$cabecalho")
        cabecalhoAtivo = if (r != null && "OK" in r.resposta.uppercase()) cabecalho else null
    }

    private suspend fun pedir(comando: String, servico: Int): RespostaUds {
        val r = enviar(comando) ?: return RespostaUds.SemResposta("falha de comunicação com o adaptador")
        return InterpretadorUds.interpretar(r.resposta, servico, r.semPrompt)
    }

    companion object {
        const val SERVICO_SESSAO = 0x10
        const val SERVICO_TESTER_PRESENT = 0x3E
        const val SERVICO_ATUADOR = 0x2F
        const val COMANDO_SESSAO_ESTENDIDA = "1003"
        const val COMANDO_SESSAO_PADRAO = "1001"
        const val COMANDO_TESTER_PRESENT = "3E00"
        const val COMANDO_RPM = "010C"

        /** Tester Present a cada 2 s (S3 da ISO 14229 = 5 s). */
        const val INTERVALO_TESTER_PRESENT_MS = 2_000L

        /** Abaixo disto o motor é considerado parado (o `010C` devolve 0 com a ignição ligada e o motor desligado). */
        const val RPM_MOTOR_PARADO = 1.0

        /** RPM de uma resposta `41 0C A B` (com ou sem espaços/cabeçalhos); null se não houver. */
        fun parseRpm(bruto: String): Double? {
            val hex = bruto.uppercase().filter { it in '0'..'9' || it in 'A'..'F' || it == '\r' || it == '\n' }
            val linha = hex.split('\r', '\n').firstOrNull { "410C" in it } ?: return null
            val i = linha.indexOf("410C")
            if (linha.length < i + 8) return null
            val a = linha.substring(i + 4, i + 6).toInt(16)
            val b = linha.substring(i + 6, i + 8).toInt(16)
            return (256 * a + b) / 4.0
        }
    }
}
