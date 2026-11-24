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
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Transporte Bluetooth Clássico (perfil Serial Port / SPP) por socket RFCOMM, usado pela
 * maioria dos adaptadores ELM327 "v1.5"/"v2.1". O protocolo de texto (prompt `>`, timeouts,
 * ressincronização) fica no [CanalElm327]. Cada instância serve a uma única conexão.
 *
 * - `connect()` é bloqueante e não responde a cancelamento: roda numa thread própria e o socket
 *   é fechado para destravá-lo em caso de timeout/cancelamento.
 * - Muitos clones baratos recusam o socket RFCOMM seguro (`read failed, socket might closed`):
 *   se a primeira tentativa falhar rápido, tenta de novo com o socket inseguro (sem criptografia
 *   de link — o ELM327 não tem dados sensíveis).
 * - [fechar] pode ser chamado de qualquer thread a qualquer momento (desconectar pela UI, carro
 *   desligado), inclusive no meio do [conectar]: o próximo `available()`/`write()` do canal lança
 *   `IOException` e o cliente trata; um `connect()` que termine depois disso é desfeito na hora.
 * - Nenhum socket fica aberto em caso de falha: streams e socket são fechados (nesta ordem, que
 *   é a que libera o canal RFCOMM em todas as versões da pilha Bluetooth) e a reconexão ao mesmo
 *   adaptador espera [PAUSA_APOS_FECHAMENTO_MS] desde o último fechamento, para não esbarrar no
 *   canal ainda ocupado (`read failed, socket might closed or timeout, read ret: -1`).
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
    @Volatile private var canal: CanalElm327? = null
    @Volatile private var fechado = false

    override suspend fun conectar() = withContext(Dispatchers.IO) {
        val bt = adaptador ?: throw ObdException("Este aparelho não possui Bluetooth")
        if (!bt.isEnabled) throw ObdException("Bluetooth desligado. Ligue-o e tente novamente")
        if (fechado) throw IOException("Conexão cancelada")

        // Reconexão logo depois de fechar: a pilha Bluetooth ainda pode estar liberando o canal.
        val espera = esperaAntesDeReconectar(agoraMs(), ultimosFechamentos[endereco])
        if (espera > 0) delay(espera)

        // Descoberta ativa deixa a conexão RFCOMM lenta/instável. No Android 12+ cancelar exige
        // BLUETOOTH_SCAN; sem ela a conexão ainda funciona, só não dá para cancelar a descoberta.
        try {
            bt.cancelDiscovery()
        } catch (_: SecurityException) {
        }

        try {
            val dispositivo = bt.getRemoteDevice(endereco)
            val inicio = System.nanoTime()
            val conectado = try {
                tentarConectar(dispositivo.createRfcommSocketToServiceRecord(SPP_UUID))
            } catch (e: IOException) {
                val decorridoMs = (System.nanoTime() - inicio) / 1_000_000
                liberarSocket()
                if (fechado || decorridoMs > LIMITE_FALHA_RAPIDA_MS) throw e
                delay(PAUSA_ENTRE_TENTATIVAS_MS) // a pilha Bluetooth precisa liberar o canal
                tentarConectar(dispositivo.createInsecureRfcommSocketToServiceRecord(SPP_UUID))
            }
            val i = conectado.inputStream
            val o = conectado.outputStream
            entrada = i
            saida = o
            canal = CanalElm327(i, o)
            // fechar() chegou entre o fim do connect() e aqui: não deixa um socket aberto sem dono.
            if (fechado) {
                fechar()
                throw IOException("Conexão cancelada")
            }
        } catch (e: Throwable) {
            // Qualquer falha (I/O, permissão, timeout, cancelamento): nada de socket aberto sobrando.
            canal = null
            liberarSocket()
            throw e
        }
    }

    /** Uma tentativa de `connect()` com timeout; devolve o socket aberto ou lança (fechando-o). */
    private suspend fun tentarConectar(novo: BluetoothSocket): BluetoothSocket {
        socket = novo
        if (fechado) {
            liberarSocket()
            throw IOException("Conexão cancelada")
        }
        val ok = try {
            withTimeoutOrNull(timeoutConexaoMs) { conectarSocket(novo) }
        } catch (e: Throwable) {
            liberarSocket()
            throw e
        }
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
        liberarSocket()
    }

    /**
     * Fecha streams e socket (fechar o socket destrava um `connect()`/`write()` em andamento) e
     * anota o instante, para a próxima conexão ao mesmo adaptador respeitar a pausa de liberação.
     */
    private fun liberarSocket() {
        val s = socket
        val i = entrada
        val o = saida
        socket = null
        entrada = null
        saida = null
        if (s == null && i == null && o == null) return
        fecharSilencioso(i)
        fecharSilencioso(o)
        fecharSilencioso(s)
        ultimosFechamentos[endereco] = agoraMs()
    }

    private suspend fun conectarSocket(s: BluetoothSocket) {
        suspendCancellableCoroutine<Unit> { cont ->
            cont.invokeOnCancellation { fecharSilencioso(s) }
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

        /** Tempo que a pilha Bluetooth leva para liberar o canal RFCOMM de um socket recém-fechado. */
        const val PAUSA_APOS_FECHAMENTO_MS = 600L

        /** Último fechamento de socket por endereço MAC (relógio monotônico, ms). */
        private val ultimosFechamentos = ConcurrentHashMap<String, Long>()

        private fun agoraMs(): Long = System.nanoTime() / 1_000_000

        /** Quanto esperar antes de abrir um socket novo, dado o último fechamento (null = nenhum). */
        fun esperaAntesDeReconectar(agoraMs: Long, ultimoFechamentoMs: Long?): Long {
            if (ultimoFechamentoMs == null) return 0
            val decorrido = agoraMs - ultimoFechamentoMs
            return if (decorrido in 0 until PAUSA_APOS_FECHAMENTO_MS) PAUSA_APOS_FECHAMENTO_MS - decorrido else 0
        }

        /** `close()` que nunca lança: pilhas Bluetooth de alguns aparelhos lançam até NPE ao fechar. */
        fun fecharSilencioso(recurso: Closeable?) {
            if (recurso == null) return
            try {
                recurso.close()
            } catch (_: Exception) {
            }
        }
    }
}
