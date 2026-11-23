package com.charruamotors.obd.sessao

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Tela que usa o scanner sob comando do usuário (Teste de Bateria, Terminal/Varredura, Diagnóstico de Erros). */
interface FerramentaManualObd

/**
 * Protege a sessão iniciada pelo Bluetooth do carro (`Toyota SW4`) de ser encerrada enquanto o usuário
 * a usa, e tolera "piscadas" do Bluetooth em segundo plano.
 *
 * Quando chega `ACL_DISCONNECTED` do carro e há uma sessão automática:
 * - app em primeiro plano, ferramenta manual aberta ou sessão assumida → **mantém** a sessão e anota
 *   no log; se o app depois for para o segundo plano, a janela de tolerância começa nesse momento;
 * - app em segundo plano → espera [toleranciaMs] (15 s); se o carro reconectar ou o usuário abrir o
 *   app nesse intervalo, o encerramento é cancelado.
 *
 * Abrir uma ferramenta manual promove a sessão a "assumida" ([SessaoObd.assumir]): daí em diante só o
 * botão Desconectar ou a ECU muda (ignição desligada) a encerram. Use do mesmo contexto do [escopo]
 * (main thread); os callbacks vêm do `Application` e do `GatilhoBluetoothReceiver`.
 */
class ProtecaoSessao(
    private val escopo: CoroutineScope,
    private val estadoSessao: () -> EstadoSessao,
    private val encerrar: (motivo: String) -> Unit,
    private val registrar: (texto: String) -> Unit,
    private val assumir: () -> Unit = {},
    private val toleranciaMs: Long = TOLERANCIA_PADRAO_MS,
) {
    private var activitiesVisiveis = 0
    private var ferramentasAbertas = 0
    private var encerramentoPendente: Job? = null

    /** O Bluetooth do carro caiu enquanto o app estava em uso e a sessão foi mantida. */
    private var carroDesconectado = false
    private var nomeCarro: String? = null

    val appEmPrimeiroPlano: Boolean get() = activitiesVisiveis > 0
    val ferramentaAberta: Boolean get() = ferramentasAbertas > 0

    /** Aguardando a janela de tolerância para encerrar. */
    val encerramentoAgendado: Boolean get() = encerramentoPendente?.isActive == true

    private fun emUso(): Boolean =
        appEmPrimeiroPlano || ferramentaAberta || estadoSessao().assumidaPeloUsuario

    // ------------------------------------------------------------------ ciclo de vida das telas

    /** `onStart` de uma Activity ([ferramenta] = é uma [FerramentaManualObd]). */
    fun activityIniciada(ferramenta: Boolean) {
        activitiesVisiveis++
        if (ferramenta) {
            ferramentasAbertas++
            if (estadoSessao().automatica) assumir()
        }
        if (cancelarPendente()) {
            registrar("App aberto durante a espera: encerramento por Bluetooth cancelado, sessão mantida")
            carroDesconectado = true // volta a valer a regra quando o app sair de cena
        }
    }

    /** `onStop` de uma Activity. */
    fun activityParada(ferramenta: Boolean) {
        activitiesVisiveis = (activitiesVisiveis - 1).coerceAtLeast(0)
        if (ferramenta) ferramentasAbertas = (ferramentasAbertas - 1).coerceAtLeast(0)
        // O carro já tinha caído e a sessão só ficou por causa do uso: agora que o app saiu, vale a tolerância.
        if (carroDesconectado && !emUso() && estadoSessao().automatica) agendar(nomeCarro)
    }

    // ------------------------------------------------------------------ Bluetooth do carro

    /** `ACTION_ACL_DISCONNECTED` do carro (a decisão de que é o carro já foi tomada pelo gatilho). */
    fun carroDesconectou(nome: String?) {
        if (!estadoSessao().automatica) return
        nomeCarro = nome
        if (emUso()) {
            carroDesconectado = true
            registrar("Bluetooth do carro desconectado, mas sessão mantida pois o app está em uso")
            return
        }
        agendar(nome)
    }

    /** `ACTION_ACL_CONNECTED` do carro: cancela um encerramento em espera. */
    fun carroConectou() {
        carroDesconectado = false
        if (cancelarPendente()) registrar("Bluetooth do carro reconectou dentro da tolerância: encerramento cancelado")
    }

    /** A sessão terminou por outro caminho (usuário, ECU muda): zera o estado. */
    fun sessaoEncerrada() {
        cancelarPendente()
        carroDesconectado = false
    }

    // ------------------------------------------------------------------ internos

    private fun agendar(nome: String?) {
        if (encerramentoAgendado) return
        registrar("Bluetooth do carro desconectado: aguardando ${toleranciaMs / 1000} s antes de encerrar a sessão")
        encerramentoPendente = escopo.launch {
            delay(toleranciaMs)
            encerramentoPendente = null
            carroDesconectado = false
            // Reavalia na hora: a sessão pode ter acabado, sido assumida ou o app aberto.
            if (estadoSessao().automatica && !emUso()) encerrar("Bluetooth do carro (${nome ?: "gatilho"}) desconectado")
        }
    }

    /** True se havia um encerramento em espera. */
    private fun cancelarPendente(): Boolean {
        val job = encerramentoPendente ?: return false
        encerramentoPendente = null
        val ativo = job.isActive
        job.cancel()
        return ativo
    }

    companion object {
        /** Janela em que o Bluetooth do carro pode voltar antes de a sessão ser encerrada. */
        const val TOLERANCIA_PADRAO_MS = 15_000L
    }
}
