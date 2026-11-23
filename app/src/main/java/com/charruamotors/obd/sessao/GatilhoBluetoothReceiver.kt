package com.charruamotors.obd.sessao

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.IntentCompat
import com.charruamotors.CharruaMotorsApp

/**
 * Gatilho da caixa-preta: recebe `ACTION_ACL_CONNECTED`/`ACTION_ACL_DISCONNECTED` (registrado no
 * manifesto — broadcasts isentos da restrição de broadcasts implícitos e que liberam o início do
 * serviço em primeiro plano vindo do segundo plano). Se o aparelho for o Bluetooth do carro
 * (padrão `Toyota SW4`), inicia ou encerra a sessão; a decisão fica em [GatilhoBluetooth.decidir].
 */
class GatilhoBluetoothReceiver : BroadcastReceiver() {

    @SuppressLint("MissingPermission") // sem BLUETOOTH_CONNECT o nome vem null (SecurityException tratada)
    override fun onReceive(context: Context, intent: Intent) {
        val evento = when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> EventoBluetooth.CONECTADO
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> EventoBluetooth.DESCONECTADO
            else -> return
        }
        val dispositivo = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
        val nome = try {
            dispositivo.name
        } catch (_: SecurityException) {
            null
        }
        val endereco = runCatching { dispositivo.address }.getOrNull()
        val app = context.applicationContext as? CharruaMotorsApp ?: return
        val config = app.preferenciasAutoConexao.config.value
        // Só consulta a sessão quando importa (o broadcast chega para qualquer fone ou relógio).
        val sessaoAutomatica = evento == EventoBluetooth.DESCONECTADO &&
            GatilhoBluetooth.corresponde(nome, endereco, config) &&
            app.sessaoObd.estadoSessao.value.automatica

        when (GatilhoBluetooth.decidir(evento, nome, endereco, config, sessaoAutomatica)) {
            AcaoGatilho.INICIAR_SESSAO -> {
                Log.i(TAG, "Bluetooth do carro conectou ($nome): iniciando a sessão OBD automática")
                ObdSessaoService.iniciarAutomatico(context, nome ?: config.gatilhoNome)
            }
            AcaoGatilho.ENCERRAR_SESSAO -> {
                Log.i(TAG, "Bluetooth do carro desconectou ($nome): encerrando a sessão OBD")
                app.sessaoObd.encerrarPorGatilho(nome ?: config.gatilhoNome)
            }
            AcaoGatilho.IGNORAR -> Unit
        }
    }

    private companion object {
        const val TAG = "GatilhoBluetooth"
    }
}
