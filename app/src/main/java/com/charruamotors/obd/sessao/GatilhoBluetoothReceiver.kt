package com.charruamotors.obd.sessao

import com.charruamotors.carros.SelecaoPerfisCarro
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
 * (padrão `Toyota SW4`), inicia ou encerra a sessão; a decisão fica em [GatilhoBluetooth.decidir] e o
 * encerramento passa pela [ProtecaoSessao] (app em uso mantém a sessão; segundo plano espera 15 s).
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
        if (evento == EventoBluetooth.CONECTADO) selecionarCarroDoGatilho(app, nome, endereco)
        val config = app.preferenciasAutoConexao.config.value
        // Só consulta a sessão quando importa (o broadcast chega para qualquer fone ou relógio).
        val ehCarro = GatilhoBluetooth.corresponde(nome, endereco, config)
        // O carro reconectou: cancela um encerramento que estava na janela de tolerância.
        if (evento == EventoBluetooth.CONECTADO && ehCarro) app.protecaoSessao.carroConectou()
        val sessaoAutomatica = evento == EventoBluetooth.DESCONECTADO &&
            ehCarro &&
            app.sessaoObd.estadoSessao.value.automatica

        when (GatilhoBluetooth.decidir(evento, nome, endereco, config, sessaoAutomatica)) {
            AcaoGatilho.INICIAR_SESSAO -> {
                Log.i(TAG, "Bluetooth do carro conectou ($nome): iniciando a sessão OBD automática")
                ObdSessaoService.iniciarAutomatico(context, nome ?: config.gatilhoNome)
            }
            AcaoGatilho.ENCERRAR_SESSAO -> {
                // Só encerra se o app não estiver em uso e depois da tolerância contra piscadas do Bluetooth.
                Log.i(TAG, "Bluetooth do carro desconectou ($nome): avaliando o encerramento da sessão OBD")
                app.protecaoSessao.carroDesconectou(nome ?: config.gatilhoNome)
            }
            AcaoGatilho.IGNORAR -> Unit
        }
    }

    /**
     * v2.3.0: cada carro tem o próprio Bluetooth (perfil isolado). Se o aparelho que conectou é o
     * de outro veículo, ele vira o carro ativo antes da decisão — a sessão automática abre no carro certo.
     * Com uma sessão já ativa nada muda (ela segue no veículo dela).
     */
    private fun selecionarCarroDoGatilho(app: CharruaMotorsApp, nome: String?, endereco: String?) {
        if (app.sessaoObd.estadoSessao.value.ativa) return
        val perfis = app.perfisCarro.lerTodos(app.perfisCarro.ids())
        val id = SelecaoPerfisCarro.veiculoDoGatilho(nome, endereco, perfis) ?: return
        if (id == app.perfisCarro.veiculoAtivo.value) return
        Log.i(TAG, "Bluetooth $nome é o do veículo $id: selecionando o perfil dele")
        app.ativarVeiculo(id, null)
    }

    private companion object {
        const val TAG = "GatilhoBluetooth"
    }
}
