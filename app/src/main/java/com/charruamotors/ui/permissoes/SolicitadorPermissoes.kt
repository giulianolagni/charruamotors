package com.charruamotors.ui.permissoes

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import com.charruamotors.R
import com.charruamotors.direcao.midia.ControladorMidia
import com.charruamotors.sistema.permissoes.ItemPermissao
import com.charruamotors.ui.util.PermissoesBluetooth

/**
 * Ação direta de cada item do checklist: pedidos em tempo de execução (Bluetooth, notificações)
 * ou a tela do sistema certa (bateria "Sem restrições", Acesso a Notificações, Sobrepor apps).
 *
 * Crie como propriedade da Activity (os launchers precisam ser registrados antes do onCreate
 * terminar). [aoMudar] é chamado na volta de um pedido; as telas também releem o estado no onResume.
 */
class SolicitadorPermissoes(
    private val activity: ComponentActivity,
    private val aoMudar: () -> Unit,
) {

    private val pedirBluetooth =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { resultado ->
            tratarNegacao(resultado.filterValues { !it }.keys)
            aoMudar()
        }

    private val pedirNotificacoes =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedida ->
            if (!concedida && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                tratarNegacao(setOf(Manifest.permission.POST_NOTIFICATIONS))
            }
            aoMudar()
        }

    fun solicitar(item: ItemPermissao) {
        when (item) {
            ItemPermissao.BLUETOOTH -> solicitarBluetooth()
            ItemPermissao.NOTIFICACOES -> solicitarNotificacoes()
            ItemPermissao.BATERIA_SEM_RESTRICOES -> liberarBateria()
            ItemPermissao.ACESSO_NOTIFICACOES -> abrir(ControladorMidia.intentPermissao())
            ItemPermissao.SOBREPOR_APPS ->
                abrir(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + activity.packageName)))
        }
    }

    fun solicitarBluetooth() {
        if (!PermissoesBluetooth.disponivel(activity)) {
            avisar(R.string.permissao_sem_bluetooth)
            return
        }
        pedirBluetooth.launch(PermissoesBluetooth.necessarias())
    }

    private fun solicitarNotificacoes() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pedirNotificacoes.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Pedido direto de "Sem restrições"; sem ele, a tela do app (Bateria → Sem restrições, no Samsung). */
    @SuppressLint("BatteryLife") // app pessoal: o pedido direto é o caminho de 1 toque para "Sem restrições"
    fun liberarBateria() {
        val pedido = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + activity.packageName))
        if (runCatching { activity.startActivity(pedido) }.isSuccess) return
        abrir(detalhesDoApp())
    }

    /**
     * Negada com "não perguntar de novo" (ou duas vezes): o Android nem mostra mais o pedido, então
     * o botão leva para a tela de permissões do app.
     */
    private fun tratarNegacao(negadas: Set<String>) {
        if (negadas.isEmpty()) return
        val bloqueada = negadas.any { !ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        if (bloqueada) {
            avisar(R.string.permissao_bloqueada)
            abrir(detalhesDoApp())
        }
    }

    private fun detalhesDoApp() =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.fromParts("package", activity.packageName, null))

    private fun abrir(intent: Intent) {
        val abriu = runCatching { activity.startActivity(intent) }.isSuccess ||
            runCatching { activity.startActivity(detalhesDoApp()) }.isSuccess
        if (!abriu) avisar(R.string.permissao_erro_abrir)
    }

    private fun avisar(texto: Int) = Toast.makeText(activity, texto, Toast.LENGTH_LONG).show()
}
