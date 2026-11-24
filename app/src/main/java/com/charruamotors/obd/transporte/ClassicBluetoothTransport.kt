package com.charruamotors.obd.transporte

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import com.charruamotors.obd.ObdException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Transporte Bluetooth Clássico (perfil Serial Port / SPP) por socket RFCOMM, usado pela
 * maioria dos adaptadores ELM327 "v1.5"/"v2.1". O protocolo de texto (prompt `>`, timeouts,
 * ressincronização) fica no [CanalElm327].
 *
 * - `connect()` é bloqueante e não responde a cancelamento: roda numa thread própria e o socket
 *   é fechado para destravá-lo em caso de timeout/cancelamento.
 * - Muitos clones baratos recusam o socket RFCOMM seguro (`read failed, socket might closed`):
 *   se a primeira tentativa falhar rápido, tenta de novo com o socket inseguro (sem criptografia
 *   de link — o ELM327 não tem dados sensíveis).
 * - [fechar] pode ser chamado de qualquer thread a qualquer momento (desconectar pela UI, carro
 *   desligado): o próximo `available()`/`write()` do canal lança `IOException` e o cliente trata.
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
    @Volatile private var canal: CanalElm327? = null
    @Volatile private var fechado = false

    override suspend fun conectar() = withContext(Dispatchers.IO) {
        val bt = adaptador ?: throw ObdException("Este aparelho não possui Bluetooth")
        if (!bt.isEnabled) throw ObdException("Bluetooth desligado. Ligue-o e tente novamente")
        fechado = false

        // Descoberta ativa deixa a conexão RFCOMM lenta/instável. No Android 12+ cancelar exige
        // BLUETOOTH_SCAN; sem ela a conexão ainda funciona, só não dá para cancelar a descoberta.
        try {
            bt.cancelDiscovery()
        } catch (_: SecurityException) {
        }

        val dispositivo = bt.getRemoteDevice(endereco)
        val inicio = System.nanoTime()
        val conectado = try {
            tentarConectar(dispositivo.createRfcommSocketToServiceRecord(SPP_UUID))
        } catch (e: IOException) {
            val decorridoMs = (System.nanoTime() - inicio) / 1_000_000
            if (fechado || decorridoMs > LIMITE_FALHA_RAPIDA_MS) throw e
            runCatching { socket?.close() }
            delay(PAUSA_ENTRE_TENTATIVAS_MS) // a pilha Bluetooth precisa liberar o canal
            tentarConectar(dispositivo.createInsecureRfcommSocketToServiceRecord(SPP_UUID))
        }
        canal = CanalElm327(conectado.inputStream, conectado.outputStream)
    }

    /** Uma tentativa de `connect()` com timeout; devolve o socket aberto ou lança. */
    private suspend fun tentarConectar(novo: BluetoothSocket): BluetoothSocket {
        socket = novo
        if (fechado) {
            runCatching { novo.close() }
            throw IOException("Conexão cancelada")
        }
        val ok = withTimeoutOrNull(timeoutConexaoMs) { conectarSocket(novo) }
        if (ok == null) {
            fechar()
            throw ObdException("Tempo esgotado ao conectar com o scanner")
        }
        return novo
    }

    override suspend fun enviarComando(comando: String, timeoutMs: Long): String = withContext(Dispatchers.IO) {
        val c = canal ?: throw IOException("Canal RFCOMM fechado")
        c.enviar(comando, timeoutMs)
    }

    override fun fechar() {
        fechado = true
        canal = null
        // Fechar o socket fecha os streams e destrava um connect()/write() em andamento.
        runCatching { socket?.close() }
        socket = null
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

        /** Falha mais rápida que isto = recusa do socket seguro (vale tentar o inseguro), não fora de alcance. */
        private const val LIMITE_FALHA_RAPIDA_MS = 4_000L
        private const val PAUSA_ENTRE_TENTATIVAS_MS = 300L
    }
}
