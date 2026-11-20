package com.charruamotors.ui.util

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Regras de permissão e disponibilidade do Bluetooth para o scanner OBD2. */
object PermissoesBluetooth {

    /**
     * Android 12+ (API 31): `BLUETOOTH_CONNECT` e `BLUETOOTH_SCAN` pedidas em tempo de execução.
     * Android 11 ou anterior: `BLUETOOTH` (permissão normal, concedida na instalação).
     */
    fun necessarias(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.BLUETOOTH)
        }

    fun concedidas(context: Context): Boolean = necessarias().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** O aparelho tem Bluetooth? (o emulador normalmente não tem). */
    fun disponivel(context: Context): Boolean =
        context.getSystemService(BluetoothManager::class.java)?.adapter != null

    /** Bluetooth ligado? Exige a permissão de conexão no Android 12+, então chame após [concedidas]. */
    fun ligado(context: Context): Boolean = try {
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    } catch (_: SecurityException) {
        false
    }
}
