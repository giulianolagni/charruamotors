package com.charruamotors.obd.transporte

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import com.charruamotors.obd.ObdException
import com.charruamotors.obd.ObdTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Transporte Bluetooth Clássico (perfil Serial Port / SPP) por socket RFCOMM, usado pela
 * maioria dos adaptadores ELM327 "v1.5"/"v2.1".
 *
 * - `connect()` é bloqueante e não responde a cancelamento: roda numa thread própria e o socket
 *   é fechado para destravá-lo em caso de timeout/cancelamento.
 * - Leituras usam polling de `available()`, então respeitam timeout e cancelamento de corrotina.
 *
 * @param adaptador `BluetoothAdapter` do aparelho (nulo se não houver Bluetooth).
 * @param endereco MAC do adaptador pareado.
 */
@SuppressLint("MissingPermission") // SecurityException é propagada e convertida em estado de erro pelo cliente
class ClassicBluetoothTransport(
    private val adaptador: BluetoothAdapter?,
    private val endereco: String,
    private val timeoutConexaoMs: Long = 10_000,
) : ObdTransport {

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var entrada: InputStream? = null
    @Volatile private var saida: OutputStream? = null

    override suspend fun conectar() = withContext(Dispatchers.IO) {
        val bt = adaptador ?: throw ObdException("Este aparelho não possui Bluetooth")
        if (!bt.isEnabled) throw ObdException("Bluetooth desligado. Ligue-o e tente novamente")

        bt.cancelDiscovery() // descoberta ativa deixa a conexão RFCOMM lenta/instável
        val novo = bt.getRemoteDevice(endereco).createRfcommSocketToServiceRecord(SPP_UUID)
        socket = novo
        val conectou = withTimeoutOrNull(timeoutConexaoMs) { conectarSocket(novo) }
        if (conectou == null) {
            fechar()
            throw ObdException("Tempo esgotado ao conectar com o scanner")
        }
        entrada = novo.inputStream
        saida = novo.outputStream
    }

    override suspend fun enviarComando(comando: String, timeoutMs: Long): String = withContext(Dispatchers.IO) {
        val ent = entrada ?: throw IOException("Canal RFCOMM fechado")
        val sai = saida ?: throw IOException("Canal RFCOMM fechado")
        val pendentes = ent.available()
        if (pendentes > 0) ent.skip(pendentes.toLong()) // descarta resíduo de respostas antigas
        sai.write("$comando\r".toByteArray(Charsets.US_ASCII))
        sai.flush()
        lerAtePrompt(ent, comando, timeoutMs)
    }

    override fun fechar() {
        runCatching { entrada?.close() }
        runCatching { saida?.close() }
        runCatching { socket?.close() }
        entrada = null
        saida = null
        socket = null
    }

    /** Lê bytes até o prompt `>`; usa polling de `available()` para respeitar timeout e cancelamento. */
    private suspend fun lerAtePrompt(ent: InputStream, comando: String, timeoutMs: Long): String {
        val resposta = StringBuilder()
        val limite = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            if (ent.available() > 0) {
                val byte = ent.read()
                if (byte < 0) throw IOException("Fim do fluxo")
                if (byte.toChar() == '>') return resposta.toString()
                resposta.append(byte.toChar())
            } else {
                if (System.nanoTime() > limite) {
                    throw ObdTimeoutException("O scanner não respondeu ao comando $comando")
                }
                delay(INTERVALO_POLLING_LEITURA_MS) // cede a thread e permite cancelamento
            }
        }
    }

    private suspend fun conectarSocket(s: BluetoothSocket) {
        suspendCancellableCoroutine<Unit> { cont ->
            cont.invokeOnCancellation { runCatching { s.close() } }
            thread(isDaemon = true, name = "obd-connect") {
                cont.resumeWith(runCatching { s.connect() })
            }
        }
    }

    companion object {
        /** UUID do perfil Serial Port (SPP), usado pelos adaptadores ELM327 Bluetooth Clássico. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        private const val INTERVALO_POLLING_LEITURA_MS = 15L
    }
}
