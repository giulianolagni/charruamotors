package com.charruamotors.oficina.replay

import com.charruamotors.obd.log.AmostraLog
import com.charruamotors.obd.log.GeradorCsv
import com.charruamotors.obd.log.SessaoLog
import com.charruamotors.obd.pid.CatalogoTeste
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class LeitorCsvLogTest {

    @Test
    fun `le de volta o CSV gerado pelo Data Logger`() {
        val colunas = listOf("010C", "0110", "0114", "0123").map(CatalogoTeste::pid)
        val inicio = 1_763_659_805_000L
        val sessao = SessaoLog(
            inicio,
            colunas,
            listOf(
                AmostraLog(inicio, doubleArrayOf(800.0, 2.5, 0.45, 35_000.0)),
                AmostraLog(inicio + 250, doubleArrayOf(1726.0, Double.NaN, 0.8, 36_500.0)),
                AmostraLog(inicio + 500, doubleArrayOf(2100.0, 4.36, Double.NaN, 38_000.0)),
            ),
        )
        val log = LeitorCsvLog.ler(GeradorCsv.gerar(sessao, ZoneOffset.ofHours(-3)))

        assertEquals(3, log.amostras)
        assertTrue(log.tempoAbsoluto)
        assertArrayEquals(longArrayOf(inicio, inicio + 250, inicio + 500), log.instantesMs)
        assertEquals(listOf("010C", "0110", "0114", "0123"), log.colunas.map { it.comando })
        assertEquals("rpm", log.colunas[0].unidade)
        // O nome da sonda tem vírgula (campo entre aspas no CSV).
        assertTrue(log.colunas[2].nome, log.colunas[2].nome.contains("banco 1, sensor 1"))
        assertEquals("V", log.colunas[2].unidade)
        assertEquals(1726.0, log.series[0][1], 1e-9)
        assertTrue(log.series[1][1].isNaN())
        assertEquals(4.36, log.series[1][2], 1e-9)
        assertEquals(0, log.linhasIgnoradas)
    }

    @Test
    fun `cabecalho com parenteses no nome separa nome unidade e comando`() {
        val c = ColunaLog.deCabecalho("Fluxo de massa de ar (MAF) (g/s) [0110]")
        assertEquals("Fluxo de massa de ar (MAF)", c.nome)
        assertEquals("g/s", c.unidade)
        assertEquals("0110", c.comando)
        assertEquals("Fluxo de massa de ar (MAF) (g/s)", c.rotulo)

        val livre = ColunaLog.deCabecalho("Pressao do rail")
        assertEquals("Pressao do rail", livre.nome)
        assertEquals("", livre.unidade)
        assertNull(livre.comando)
    }

    @Test
    fun `aceita CSV do Excel pt-BR com ponto e virgula e virgula decimal`() {
        val texto = "tempo_s;RPM (rpm);Turbo (kPa)\r\n0,0;800;101,3\r\n0,25;1.250;145,5\r\n0,5;;150\r\n"
        val log = LeitorCsvLog.ler(texto)
        assertFalse(log.tempoAbsoluto)
        assertArrayEquals(longArrayOf(0, 250, 500), log.instantesMs)
        assertEquals(1250.0, log.series[0][1], 1e-9)
        assertEquals(145.5, log.series[1][1], 1e-9)
        assertTrue(log.series[0][2].isNaN())
    }

    @Test
    fun `sem coluna de tempo usa uma amostra por segundo`() {
        val log = LeitorCsvLog.ler("RPM,MAP\n800,100\n900,110\n")
        assertArrayEquals(longArrayOf(0, 1000), log.instantesMs)
        assertEquals(2, log.colunas.size)
    }

    @Test
    fun `BOM quebras LF e campos faltando no fim da linha`() {
        val log = LeitorCsvLog.ler("﻿timestamp_ms,A (x),B (y)\n1000,1,2\n2000,3\n")
        assertTrue("o BOM não atrapalha a detecção da coluna de tempo", log.tempoAbsoluto)
        assertEquals(3.0, log.series[0][1], 1e-9)
        assertTrue(log.series[1][1].isNaN())
    }

    @Test
    fun `linhas malformadas sao ignoradas e contadas e linhas em branco puladas`() {
        val log = LeitorCsvLog.ler("timestamp_ms,A (x)\n1000,1\n\nlixo,2\nrodape\n3000,5\n")
        assertArrayEquals(longArrayOf(1000, 3000), log.instantesMs)
        assertEquals(2, log.linhasIgnoradas)
    }

    @Test
    fun `log fora de ordem e ordenado pelo tempo`() {
        val log = LeitorCsvLog.ler("timestamp_ms,A (x)\n3000,3\n1000,1\n2000,2\n")
        assertArrayEquals(longArrayOf(1000, 2000, 3000), log.instantesMs)
        assertArrayEquals(doubleArrayOf(1.0, 2.0, 3.0), log.series[0], 1e-9)
    }

    @Test
    fun `aspas duplicadas e quebra de linha dentro de campo entre aspas`() {
        assertEquals(listOf("a", "b \"c\"", "d,e"), LeitorCsvLog.dividir("a,\"b \"\"c\"\"\",\"d,e\"", ','))
        val log = LeitorCsvLog.ler("timestamp_ms,\"Nome\r\nlongo (x)\"\r\n1000,7\r\n")
        assertEquals(1, log.amostras)
        assertEquals(7.0, log.series[0][0], 1e-9)
    }

    @Test
    fun `colunas so com campos vazios ficam fora dos chips`() {
        val log = LeitorCsvLog.ler("timestamp_ms,A (x),B (y)\n1000,1,\n2000,2,\n")
        assertEquals(listOf(0), log.colunasComDados)
    }

    @Test
    fun `arquivo vazio sem sensores ou sem amostras e recusado com mensagem`() {
        assertThrows(CsvInvalidoException::class.java) { LeitorCsvLog.ler("") }
        assertThrows(CsvInvalidoException::class.java) { LeitorCsvLog.ler("timestamp_ms,data_hora,tempo_s\n1,2,3\n") }
        assertThrows(CsvInvalidoException::class.java) { LeitorCsvLog.ler("timestamp_ms,A (x)\n") }
    }

    @Test
    fun `separador e detectado fora das aspas`() {
        assertEquals(',', LeitorCsvLog.detectarSeparador("a,\"b;c\",d"))
        assertEquals(';', LeitorCsvLog.detectarSeparador("a;\"b,c\";d"))
    }
}
