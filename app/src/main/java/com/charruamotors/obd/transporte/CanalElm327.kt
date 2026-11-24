package com.charruamotors.obd.transporte

import com.charruamotors.obd.ObdParser
import com.charruamotors.obd.ObdTimeoutException
import kotlinx.coroutines.delay
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Troca de texto com o ELM327 sobre um par de streams (RFCOMM hoje, qualquer canal serial amanhã).
 * Não depende do Android, então é testada na JVM com streams roteirizados.
 *
 * **Leitura até o prompt.** O ELM327 encerra toda resposta com `>`; tudo antes dele (várias linhas,
 * `SEARCHING...`, `BUS INIT: ...OK`, quadros CAN `0:`/`1:`) é devolvido junto. Nada bloqueia: a
 * leitura consulta `available()` e só chama `read()` quando há bytes, então cancelar a corrotina ou
 * fechar o socket nunca deixa uma thread presa num `read()`.
 *
 * **Timeout por inatividade.** O prazo [enviar] `timeoutMs` conta a partir do último byte recebido:
 * a inicialização de 5 baud da linha K imprime `BUS INIT: ...` aos poucos e pode passar de 3 s sem
 * estourar, enquanto um adaptador mudo estoura no prazo. Um teto de [FATOR_TETO]× o prazo impede
 * laço infinito se o adaptador ficar mandando bytes sem nunca enviar o prompt (lixo, modo monitor).
 *
 * **Ressincronização.** Se um comando estourou o prazo ou voltou `STOPPED`, a resposta atrasada
 * dele ainda pode chegar. Antes do próximo comando, o canal descarta tudo até o `>` pendente (ou até
 * um silêncio curto), para que a resposta de um comando nunca seja lida como a do seguinte.
 *
 * Não é thread-safe: o `Elm327BluetoothClient` serializa os comandos com um mutex.
 *
 * @param agoraMs relógio monotônico em ms (injetável para testes com tempo virtual).
 */
class CanalElm327(
    private val entrada: InputStream,
    private val saida: OutputStream,
    private val agoraMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {

    /** Um comando anterior ficou sem prompt: pode haver resposta atrasada a caminho. */
    @Volatile var dessincronizado: Boolean = false
        private set

    private val buffer = ByteArray(TAMANHO_BUFFER)

    /**
     * Envia [comando] + `\r` e devolve o texto até o prompt `>` (sem ele).
     * @throws ObdTimeoutException sem prompt no prazo (o canal continua utilizável)
     * @throws IOException canal fechado ou link Bluetooth perdido
     */
    suspend fun enviar(comando: String, timeoutMs: Long): String {
        require(timeoutMs > 0) { "timeoutMs deve ser positivo" }
        if (dessincronizado) ressincronizar() else descartarPendentes()
        saida.write("$comando\r".toByteArray(Charsets.US_ASCII))
        saida.flush()
        val resposta = try {
            lerAtePrompt(comando, timeoutMs)
        } catch (e: ObdTimeoutException) {
            dessincronizado = true
            throw e
        }
        // STOPPED: o ELM327 foi interrompido e o resto do comando pode virar um "?" atrasado.
        if (ObdParser.interrompido(resposta)) dessincronizado = true
        return resposta
    }

    /** Descarta resíduos já recebidos (eco atrasado, prompt extra de clones). */
    private fun descartarPendentes() {
        var pendentes = entrada.available()
        while (pendentes > 0) {
            val lidos = entrada.read(buffer, 0, minOf(pendentes, buffer.size))
            if (lidos < 0) throw IOException("Fim do fluxo do scanner")
            pendentes = entrada.available()
        }
    }

    /**
     * Espera o prompt da resposta atrasada (até [TETO_RESSINCRONIA_MS]) ou um silêncio de
     * [SILENCIO_RESSINCRONIA_MS], descartando tudo. Se o adaptador ainda estiver ocupado depois
     * disso, o próximo comando o interrompe (`STOPPED`) e o cliente repete o comando.
     */
    private suspend fun ressincronizar() {
        val inicio = agoraMs()
        var ultimoByte = inicio
        while (true) {
            val pendentes = entrada.available()
            if (pendentes > 0) {
                val lidos = entrada.read(buffer, 0, minOf(pendentes, buffer.size))
                if (lidos < 0) throw IOException("Fim do fluxo do scanner")
                ultimoByte = agoraMs()
                val prompt = (0 until lidos).indexOfFirst { buffer[it] == PROMPT }
                if (prompt >= 0) {
                    descartarPendentes()
                    break
                }
            } else {
                val agora = agoraMs()
                if (agora - ultimoByte >= SILENCIO_RESSINCRONIA_MS || agora - inicio >= TETO_RESSINCRONIA_MS) break
                delay(INTERVALO_POLLING_MS)
            }
        }
        dessincronizado = false
    }

    private suspend fun lerAtePrompt(comando: String, timeoutMs: Long): String {
        val resposta = StringBuilder()
        val inicio = agoraMs()
        var ultimoByte = inicio
        val teto = timeoutMs * FATOR_TETO
        while (true) {
            val pendentes = entrada.available()
            if (pendentes > 0) {
                val lidos = entrada.read(buffer, 0, minOf(pendentes, buffer.size))
                if (lidos < 0) throw IOException("Fim do fluxo do scanner")
                ultimoByte = agoraMs()
                for (i in 0 until lidos) {
                    if (buffer[i] == PROMPT) {
                        // Bytes depois do prompt são resíduo; o próximo envio os descarta.
                        return resposta.toString()
                    }
                    resposta.append((buffer[i].toInt() and 0xFF).toChar())
                }
                if (resposta.length > MAX_CARACTERES_RESPOSTA) {
                    throw ObdTimeoutException("O scanner enviou dados demais sem concluir o comando $comando")
                }
                if (ultimoByte - inicio >= teto) {
                    throw ObdTimeoutException("O scanner não concluiu o comando $comando")
                }
            } else {
                val agora = agoraMs()
                if (agora - ultimoByte >= timeoutMs || agora - inicio >= teto) {
                    throw ObdTimeoutException("O scanner não respondeu ao comando $comando")
                }
                delay(INTERVALO_POLLING_MS) // cede a thread e permite cancelamento
            }
        }
    }

    companion object {
        private const val PROMPT = '>'.code.toByte()
        private const val TAMANHO_BUFFER = 512

        /** Intervalo entre consultas a `available()`: curto para não somar latência a cada comando. */
        const val INTERVALO_POLLING_MS = 5L

        /** O teto absoluto de um comando é este múltiplo do prazo de inatividade. */
        const val FATOR_TETO = 2

        /** Maior resposta legítima (VIN/DTCs multi-frame têm algumas centenas de caracteres). */
        const val MAX_CARACTERES_RESPOSTA = 4_096

        const val SILENCIO_RESSINCRONIA_MS = 200L
        const val TETO_RESSINCRONIA_MS = 1_500L
    }
}
