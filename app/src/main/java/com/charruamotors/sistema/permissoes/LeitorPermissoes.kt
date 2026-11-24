package com.charruamotors.sistema.permissoes

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.charruamotors.direcao.midia.ControladorMidia
import com.charruamotors.ui.util.PermissoesBluetooth

/** Lê o estado real das permissões no aparelho (as regras ficam em [ChecklistPermissoes]). */
object LeitorPermissoes {

    fun ler(context: Context): EstadoSistemaPermissoes {
        val energia = context.getSystemService(PowerManager::class.java)
        return EstadoSistemaPermissoes(
            sdk = Build.VERSION.SDK_INT,
            bluetoothDisponivel = PermissoesBluetooth.disponivel(context),
            bluetoothConcedido = PermissoesBluetooth.concedidas(context),
            notificacoesConcedidas = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            bateriaSemRestricoes = energia?.isIgnoringBatteryOptimizations(context.packageName) == true,
            acessoNotificacoes = ControladorMidia.permissaoConcedida(context),
            sobreporApps = Settings.canDrawOverlays(context),
        )
    }

    fun avaliar(context: Context, itens: List<ItemPermissao> = ItemPermissao.entries): List<StatusPermissao> =
        ChecklistPermissoes.avaliar(ler(context), itens)
}
