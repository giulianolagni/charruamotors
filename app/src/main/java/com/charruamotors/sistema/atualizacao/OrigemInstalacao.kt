package com.charruamotors.sistema.atualizacao

import android.content.Context
import android.os.Build

/**
 * A Google Play proíbe que apps instalados por ela se atualizem por outro meio: a verificação
 * automática pelo GitHub Releases só roda quando o app NÃO veio da Play Store.
 */
object OrigemInstalacao {
    const val PLAY_STORE = "com.android.vending"

    /** [instalador] = `installerPackageName` (null em APK instalado manualmente/adb). */
    fun deveVerificarAtualizacao(instalador: String?): Boolean = instalador != PLAY_STORE

    @Suppress("DEPRECATION")
    fun instaladorDe(context: Context): String? = runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            pm.getInstallerPackageName(context.packageName)
        }
    }.getOrNull()

    fun deveVerificarAtualizacao(context: Context): Boolean = deveVerificarAtualizacao(instaladorDe(context))
}
