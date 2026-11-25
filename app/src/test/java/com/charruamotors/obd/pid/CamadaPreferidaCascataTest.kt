package com.charruamotors.obd.pid

import org.junit.Assert.assertEquals
import org.junit.Test

/** v2.3.0 — a camada preferida do perfil do carro (`21`) vai para a frente da camada 3 da cascata. */
class CamadaPreferidaCascataTest {

    private val cascata = CatalogoTeste.cascata

    @Test
    fun `sem preferencia vale a ordem da marca`() {
        assertEquals(cascata.modosPara("Chevrolet").map { it.id }, cascata.modosPara("Chevrolet", null).map { it.id })
        assertEquals(listOf("MODO_22", "MODO_21"), cascata.modosPara("VW Gol", null).map { it.id })
    }

    @Test
    fun `camada Modo 21 passa na frente mesmo em marca que prefere o 22`() {
        assertEquals(listOf("MODO_21", "MODO_22"), cascata.modosPara("Chevrolet", "21").map { it.id })
        assertEquals(listOf("MODO_21", "MODO_22"), cascata.modosPara(null, "21").map { it.id })
        assertEquals(listOf("MODO_22", "MODO_21"), cascata.modosPara("Toyota", "22").map { it.id })
        // Serviço que o catálogo não tem: a ordem da marca, sem perder nenhum modo.
        assertEquals(listOf("MODO_21", "MODO_22"), cascata.modosPara("Toyota", "99").map { it.id })
    }
}
