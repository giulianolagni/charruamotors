package com.charruamotors.ui.diagnostico.obd

import com.charruamotors.obd.DispositivoObd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Escolha padrão do scanner entre os dispositivos pareados de um celular real. */
class EscolhaDispositivoTest {

    private val buds = DispositivoObd("Galaxy Buds2 Pro", "AA:00:00:00:00:01")
    private val relogio = DispositivoObd("Galaxy Watch6 (4F2A)", "AA:00:00:00:00:02")
    private val carro = DispositivoObd("Hilux Multimídia", "AA:00:00:00:00:03")
    private val elm = DispositivoObd("OBDII", "AA:00:00:00:00:04")
    private val vlink = DispositivoObd("V-LINK", "AA:00:00:00:00:05")

    @Test
    fun `nomes comuns de adaptadores ELM327 sao reconhecidos`() {
        listOf("OBDII", "OBD2", "ELM327 v1.5", "V-LINK", "Vgate iCar Pro", "KONNWEI KW902", "Veepeak", "OBDLink MX+", "Android-Vlink")
            .forEach { assertTrue(it, ObdViewModel.pareceScannerObd(DispositivoObd(it, "00"))) }
        listOf(buds, relogio, carro).forEach { assertFalse(it.nome, ObdViewModel.pareceScannerObd(it)) }
    }

    @Test
    fun `prefere o scanner OBD a fones relogio e central do carro`() {
        assertEquals(elm, ObdViewModel.escolherDispositivo(listOf(buds, relogio, carro, elm), atual = null))
    }

    @Test
    fun `mantem a escolha do usuario enquanto ela existir`() {
        assertEquals(vlink, ObdViewModel.escolherDispositivo(listOf(elm, vlink), atual = vlink))
        assertEquals(elm, ObdViewModel.escolherDispositivo(listOf(buds, elm), atual = vlink)) // despareado
    }

    @Test
    fun `sem nenhum com cara de scanner fica o primeiro e sem lista nenhum`() {
        assertEquals(buds, ObdViewModel.escolherDispositivo(listOf(buds, relogio), atual = null))
        assertNull(ObdViewModel.escolherDispositivo(emptyList(), atual = elm))
    }
}
