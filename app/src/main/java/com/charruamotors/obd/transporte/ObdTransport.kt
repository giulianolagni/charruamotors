package com.charruamotors.obd.transporte

/**
 * Canal de bytes com o adaptador ELM327, independente do meio físico. O protocolo ELM327
 * (comandos AT, PIDs, parsing) fica no `Elm327BluetoothClient`; cada transporte só sabe
 * abrir o canal, trocar texto e fechar:
 * - [ClassicBluetoothTransport]: Bluetooth Clássico, perfil SPP (socket RFCOMM);
 * - futuro `BleTransport`: Bluetooth Low Energy (característica GATT de escrita + notificação);
 * - testes: transporte falso com respostas roteirizadas.
 *
 * Implementações não precisam ser thread-safe: o cliente serializa os comandos.
 */
interface ObdTransport {

    /**
     * Abre o canal. Bloqueia (suspendendo) até conectar ou falhar.
     * @throws com.charruamotors.obd.ObdException falha esperada, com mensagem para o usuário
     * @throws java.io.IOException falha de I/O ao abrir o canal
     * @throws SecurityException permissão de Bluetooth não concedida
     */
    suspend fun conectar()

    /**
     * Envia [comando] (o terminador `\r` é responsabilidade do transporte) e devolve o texto
     * recebido até o prompt `>` do ELM327, sem o prompt.
     * @throws com.charruamotors.obd.ObdTimeoutException se o prompt não chegar em [timeoutMs]
     *   (o canal continua utilizável)
     * @throws java.io.IOException se o canal caiu
     */
    suspend fun enviarComando(comando: String, timeoutMs: Long = TIMEOUT_PADRAO_MS): String

    /** Fecha o canal e libera os recursos. Idempotente e não lança exceções. */
    fun fechar()

    companion object {
        const val TIMEOUT_PADRAO_MS = 3_000L
    }
}
