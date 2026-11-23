package com.charruamotors.sistema.gps

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Velocidade pelo GPS do celular (`LocationManager.GPS_PROVIDER`, sem Google Play Services). Só
 * escuta enquanto [iniciar] estiver valendo (no app: sessão OBD conectada + opção ativa nas
 * Configurações), para não gastar bateria à toa. A leitura passa sempre pelo [FiltroVelocidadeGps]:
 * sem permissão, com a localização desligada, sem fix ou com fix velho/impreciso, [velocidadeKmh]
 * devolve null — nunca um valor inventado ou congelado.
 *
 * Com o app em segundo plano o Android só entrega localização a quem tem um serviço em primeiro
 * plano do tipo `location`: o HUD flutuante sobre o Waze declara esse tipo quando a permissão
 * existe; fora isso, minimizado, a leitura simplesmente expira (fallback para "—"/ECU).
 */
class ProvedorVelocidadeGps(
    context: Context,
    private val relogio: () -> Long = SystemClock::elapsedRealtime,
) {
    private val contexto = context.applicationContext
    private val gerenciador: LocationManager? = contexto.getSystemService(LocationManager::class.java)

    @Volatile private var ultima: AmostraGps? = null
    private var ouvindo = false

    private val _situacao = MutableStateFlow(SituacaoGps.DESATIVADO)
    val situacao: StateFlow<SituacaoGps> = _situacao.asStateFlow()

    private val ouvinte = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            ultima = amostraDe(location)
            _situacao.value = SituacaoGps.ATIVO
        }

        override fun onProviderEnabled(provider: String) {
            _situacao.value = SituacaoGps.AGUARDANDO_SINAL
        }

        override fun onProviderDisabled(provider: String) {
            ultima = null
            _situacao.value = SituacaoGps.DESLIGADO
        }

        // Abstrato até o Android 9 (API 28): sem ele, a entrega de status derrubaria o app.
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    /** O app tem a permissão de localização precisa (exigida pelo provedor GPS)? */
    fun temPermissao(): Boolean =
        ContextCompat.checkSelfPermission(contexto, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Começa a escutar o GPS (idempotente). False = sem permissão, sem GPS ou recusado pelo sistema. */
    @SuppressLint("MissingPermission") // checada em temPermissao(); a SecurityException também é tratada
    @Synchronized
    fun iniciar(): Boolean {
        if (ouvindo) return true
        val g = gerenciador
        if (!temPermissao()) {
            _situacao.value = SituacaoGps.SEM_PERMISSAO
            return false
        }
        if (g == null || LocationManager.GPS_PROVIDER !in g.allProviders) {
            _situacao.value = SituacaoGps.DESLIGADO
            return false
        }
        return try {
            g.requestLocationUpdates(LocationManager.GPS_PROVIDER, INTERVALO_MS, 0f, ouvinte, Looper.getMainLooper())
            ouvindo = true
            _situacao.value = if (g.isProviderEnabled(LocationManager.GPS_PROVIDER)) SituacaoGps.AGUARDANDO_SINAL else SituacaoGps.DESLIGADO
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Localização recusada pelo sistema", e)
            _situacao.value = SituacaoGps.SEM_PERMISSAO
            false
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Provedor GPS indisponível", e)
            _situacao.value = SituacaoGps.DESLIGADO
            false
        }
    }

    /** Para de escutar e esquece o último fix (idempotente). */
    @Synchronized
    fun parar() {
        if (ouvindo) runCatching { gerenciador?.removeUpdates(ouvinte) }
        ouvindo = false
        ultima = null
        _situacao.value = SituacaoGps.DESATIVADO
    }

    /** Velocidade atual em km/h, ou null sem fix confiável (ver [FiltroVelocidadeGps]). */
    fun velocidadeKmh(): Double? = FiltroVelocidadeGps.kmh(ultima, relogio())

    private fun amostraDe(l: Location) = AmostraGps(
        velocidadeMs = if (l.hasSpeed()) l.speed else null,
        precisaoVelocidadeMs = if (l.hasSpeedAccuracy()) l.speedAccuracyMetersPerSecond else null,
        precisaoHorizontalM = if (l.hasAccuracy()) l.accuracy else null,
        instanteMs = l.elapsedRealtimeNanos / 1_000_000,
    )

    companion object {
        private const val TAG = "VelocidadeGps"

        /** Pedido de 5 Hz; a maioria dos celulares entrega 1 Hz e o S24 chega a mais com sinal bom. */
        const val INTERVALO_MS = 200L
    }
}
