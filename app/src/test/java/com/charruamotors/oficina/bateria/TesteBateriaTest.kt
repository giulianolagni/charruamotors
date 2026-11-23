package com.charruamotors.oficina.bateria

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class TesteBateriaTest {

    // ---------------------------------------------------------------- classificação

    @Test
    fun `repouso classifica saudavel a partir de 12,4 V e critica abaixo de 12,0 V`() {
        assertEquals(VereditoRepouso.SAUDAVEL, ClassificadorBateria.repouso(12.4))
        assertEquals(VereditoRepouso.SAUDAVEL, ClassificadorBateria.repouso(12.7))
        assertEquals(VereditoRepouso.CARGA_BAIXA, ClassificadorBateria.repouso(12.39))
        assertEquals(VereditoRepouso.CARGA_BAIXA, ClassificadorBateria.repouso(12.3))
        assertEquals(VereditoRepouso.CARGA_BAIXA, ClassificadorBateria.repouso(12.0))
        assertEquals(VereditoRepouso.CRITICA, ClassificadorBateria.repouso(11.99))
        assertEquals(VereditoRepouso.CRITICA, ClassificadorBateria.repouso(10.5))
    }

    @Test
    fun `media com ruido de ponto flutuante nao cai na faixa de baixo`() {
        // (12.3 + 12.5) / 2 = 12.399999999999999 em Double
        assertEquals(VereditoRepouso.SAUDAVEL, ClassificadorBateria.repouso((12.3 + 12.5) / 2))
    }

    @Test
    fun `partida classifica OK a partir de 10,0 V atencao ate 9,6 V e fraca abaixo`() {
        assertEquals(VereditoPartida.OK, ClassificadorBateria.partida(10.0))
        assertEquals(VereditoPartida.OK, ClassificadorBateria.partida(10.3))
        assertEquals(VereditoPartida.ATENCAO, ClassificadorBateria.partida(9.9))
        assertEquals(VereditoPartida.ATENCAO, ClassificadorBateria.partida(9.6))
        assertEquals(VereditoPartida.FRACA, ClassificadorBateria.partida(9.5))
        assertEquals(VereditoPartida.FRACA, ClassificadorBateria.partida(8.2))
    }

    @Test
    fun `alternador classifica subcarga normal sobrecarga e os limites entre as faixas`() {
        assertEquals(VereditoAlternador.SUBCARGA, ClassificadorBateria.alternador(13.2))
        assertEquals(VereditoAlternador.SUBCARGA, ClassificadorBateria.alternador(13.49))
        assertEquals(VereditoAlternador.LIMITE_INFERIOR, ClassificadorBateria.alternador(13.5))
        assertEquals(VereditoAlternador.NORMAL, ClassificadorBateria.alternador(13.6))
        assertEquals(VereditoAlternador.NORMAL, ClassificadorBateria.alternador(14.2))
        assertEquals(VereditoAlternador.NORMAL, ClassificadorBateria.alternador(14.6))
        assertEquals(VereditoAlternador.LIMITE_SUPERIOR, ClassificadorBateria.alternador(14.7))
        assertEquals(VereditoAlternador.LIMITE_SUPERIOR, ClassificadorBateria.alternador(14.8))
        assertEquals(VereditoAlternador.SOBRECARGA, ClassificadorBateria.alternador(14.9))
        assertEquals(VereditoAlternador.SOBRECARGA, ClassificadorBateria.alternador(15.4))
    }

    // ---------------------------------------------------------------- leitura do ATRV

    @Test
    fun `resposta do ATRV vira leitura valida LV RESET ou perdida`() {
        assertEquals(LeituraTensao.Valida(12.4), LeituraTensao.deResposta("12.4V\r\r"))
        assertEquals(LeituraTensao.LvReset, LeituraTensao.deResposta("LV RESET\r\r"))
        assertEquals(LeituraTensao.LvReset, LeituraTensao.deResposta("\r\rELM327 v1.5\r\r"))
        assertEquals(LeituraTensao.Perdida, LeituraTensao.deResposta("", semPrompt = true))
        assertEquals(LeituraTensao.Perdida, LeituraTensao.deResposta("?\r\r"))
        assertEquals(LeituraTensao.Perdida, LeituraTensao.deResposta("lixo"))
    }

    // ---------------------------------------------------------------- estatísticas

    @Test
    fun `ripple mede a oscilacao em volta da media movel e ignora a deriva lenta`() {
        val deriva = List(20) { 13.8 + it * 0.02 } // sobe devagar, sem oscilar
        assertTrue(EstatisticaTensao.ripple(deriva) < 0.05)
        val oscilando = List(20) { if (it % 2 == 0) 14.0 else 14.6 }
        assertTrue(EstatisticaTensao.ripple(oscilando) > 0.4)
        assertEquals(0.3, EstatisticaTensao.ripple(listOf(14.0, 14.3)), 1e-9) // poucas amostras: máx − mín
    }

    // ---------------------------------------------------------------- máquina de estados

    /** Alimenta [teste] com [volts] a cada [passoMs] desde [inicio]; devolve o último evento e o instante final. */
    private fun alimentar(teste: TesteBateria, inicio: Long, passoMs: Long, volts: List<LeituraTensao>): Pair<EventoTesteBateria?, Long> {
        var t = inicio
        var evento: EventoTesteBateria? = null
        for (v in volts) {
            t += passoMs
            evento = teste.registrar(v, t) ?: evento
            if (!teste.capturando) break
        }
        return evento to t
    }

    private fun constante(v: Double, n: Int) = List(n) { LeituraTensao.Valida(v) }

    @Test
    fun `etapa 1 mede o repouso pela segunda metade e avanca para a partida`() {
        val teste = TesteBateria()
        teste.iniciarCaptura(0)
        // Carga superficial: começa alta e estabiliza em 12,5 V.
        val leituras = constante(12.8, 40) + constante(12.5, 70)
        val (evento, _) = alimentar(teste, 0, 50, leituras)

        assertEquals(EventoTesteBateria.EtapaConcluida(EtapaBateria.REPOUSO), evento)
        val r = teste.resultadoRepouso!!
        assertEquals(12.5, r.tensao, 1e-9)
        assertEquals(VereditoRepouso.SAUDAVEL, r.veredito)
        assertTrue(r.estavel)
        assertFalse(r.motorPareceLigado)
        assertEquals(EtapaBateria.PARTIDA, teste.etapa)
        assertFalse(teste.capturando)
    }

    @Test
    fun `repouso acima de 13,2 V indica motor ligado`() {
        val teste = TesteBateria()
        teste.iniciarCaptura(0)
        alimentar(teste, 0, 50, constante(14.1, 120))
        assertTrue(teste.resultadoRepouso!!.motorPareceLigado)
    }

    @Test
    fun `progresso do repouso avanca com o tempo e e indeterminado esperando a partida`() {
        val teste = TesteBateria()
        assertNull(teste.progresso(0))
        teste.iniciarCaptura(1_000)
        assertEquals(0.5f, teste.progresso(1_000 + TesteBateria.DURACAO_REPOUSO_MS / 2)!!, 1e-6f)
        alimentar(teste, 1_000, 50, constante(12.6, 120))
        teste.iniciarCaptura(20_000)
        assertNull(teste.progresso(21_000))
    }

    @Test
    fun `poucas leituras validas na janela pedem para medir de novo`() {
        val teste = TesteBateria()
        teste.iniciarCaptura(0)
        val (evento, _) = alimentar(teste, 0, 50, List(120) { LeituraTensao.Perdida })
        assertEquals(EventoTesteBateria.LeiturasInsuficientes, evento)
        assertEquals(EtapaBateria.REPOUSO, teste.etapa)
        assertFalse(teste.capturando)
    }

    @Test
    fun `partida captura o vale e sobrevive a LV RESET sem abortar`() {
        val teste = TesteBateria()
        teste.iniciarCaptura(0)
        alimentar(teste, 0, 50, constante(12.5, 120))
        teste.iniciarCaptura(10_000)

        val roteiro = constante(12.5, 20) + // esperando o motorista
            listOf(LeituraTensao.Valida(11.4), LeituraTensao.LvReset, LeituraTensao.Valida(10.3), LeituraTensao.LvReset) +
            constante(10.8, 20) + // compressões
            constante(12.6, 3) + constante(14.2, 30) // motor pegou
        val (evento, _) = alimentar(teste, 10_000, 50, roteiro)

        assertEquals(EventoTesteBateria.EtapaConcluida(EtapaBateria.PARTIDA), evento)
        val p = teste.resultadoPartida!!
        assertEquals(10.3, p.vMin, 1e-9)
        assertEquals(12.5, p.tensaoInicial, 1e-9)
        assertEquals(2, p.lvResets)
        assertEquals(2, p.amostrasPerdidas)
        assertTrue(p.motorPegou)
        assertEquals(VereditoPartida.OK, p.veredito)
        assertTrue("a queda dura do primeiro vale à recuperação", p.duracaoQuedaMs in 1_000..1_400)
        assertEquals(EtapaBateria.ALTERNADOR_LENTA, teste.etapa)
    }

    @Test
    fun `LV RESET sozinho ja conta como inicio da queda da partida`() {
        val teste = TesteBateria()
        teste.pular() // sem repouso: a referência é a 1ª amostra da captura
        teste.iniciarCaptura(0)
        teste.registrar(LeituraTensao.Valida(12.6), 50)
        teste.registrar(LeituraTensao.LvReset, 100)
        assertTrue(teste.quedaDetectada)
        val (evento, _) = alimentar(teste, 100, 50, constante(9.4, 10) + constante(14.0, 30))
        assertEquals(EventoTesteBateria.EtapaConcluida(EtapaBateria.PARTIDA), evento)
        assertEquals(VereditoPartida.FRACA, teste.resultadoPartida!!.veredito)
        assertEquals(12.6, teste.resultadoPartida!!.tensaoInicial, 1e-9)
    }

    @Test
    fun `sem queda de tensao em 20 s a partida nao e detectada e fica para repetir`() {
        val teste = TesteBateria()
        teste.pular()
        teste.iniciarCaptura(0)
        val (evento, _) = alimentar(teste, 0, 100, constante(12.5, 250))
        assertEquals(EventoTesteBateria.PartidaNaoDetectada, evento)
        assertEquals(EtapaBateria.PARTIDA, teste.etapa)
        assertNull(teste.resultadoPartida)
        assertFalse(teste.capturando)
    }

    @Test
    fun `motor que nao pega encerra a etapa apos o tempo maximo da queda`() {
        val teste = TesteBateria()
        teste.pular()
        teste.iniciarCaptura(0)
        val (evento, _) = alimentar(teste, 0, 100, constante(12.4, 5) + constante(10.1, 200))
        assertEquals(EventoTesteBateria.EtapaConcluida(EtapaBateria.PARTIDA), evento)
        assertFalse(teste.resultadoPartida!!.motorPegou)
    }

    @Test
    fun `alternador mede marcha lenta e carga e conclui o teste`() {
        val teste = TesteBateria()
        teste.pular()
        teste.pular()
        assertEquals(EtapaBateria.ALTERNADOR_LENTA, teste.etapa)

        teste.iniciarCaptura(0)
        val (e1, t1) = alimentar(teste, 0, 50, List(200) { LeituraTensao.Valida(if (it % 2 == 0) 14.2 else 14.3) })
        assertEquals(EventoTesteBateria.EtapaConcluida(EtapaBateria.ALTERNADOR_LENTA), e1)
        val lenta = teste.resultadoLenta!!
        assertEquals(14.25, lenta.media, 0.01)
        assertEquals(14.3, lenta.pico, 1e-9)
        assertEquals(VereditoAlternador.NORMAL, lenta.veredito)

        teste.iniciarCaptura(t1)
        val (e2, _) = alimentar(teste, t1, 50, constante(13.3, 250))
        assertEquals(EventoTesteBateria.EtapaConcluida(EtapaBateria.ALTERNADOR_CARGA), e2)
        assertEquals(VereditoAlternador.SUBCARGA, teste.resultadoCarga!!.veredito)
        assertTrue(teste.concluido)
        assertEquals(setOf(EtapaBateria.REPOUSO, EtapaBateria.PARTIDA), teste.puladas)
    }

    @Test
    fun `cancelar a captura nao muda de etapa e reiniciar limpa tudo`() {
        val teste = TesteBateria()
        teste.iniciarCaptura(0)
        teste.registrar(LeituraTensao.Valida(12.5), 50)
        teste.cancelarCaptura()
        assertFalse(teste.capturando)
        assertEquals(EtapaBateria.REPOUSO, teste.etapa)
        assertNull(teste.registrar(LeituraTensao.Valida(12.5), 100))

        teste.pular()
        teste.reiniciar()
        assertEquals(EtapaBateria.REPOUSO, teste.etapa)
        assertTrue(teste.puladas.isEmpty())
    }

    // ---------------------------------------------------------------- laudo

    private fun testeCompleto(repouso: Double, vale: Double, lenta: Double, carga: Double): TesteBateria {
        val teste = TesteBateria()
        teste.iniciarCaptura(0)
        var (_, t) = alimentar(teste, 0, 50, constante(repouso, 120))
        teste.iniciarCaptura(t)
        t = alimentar(teste, t, 50, constante(repouso, 5) + constante(vale, 10) + constante(14.2, 30)).second
        teste.iniciarCaptura(t)
        t = alimentar(teste, t, 50, constante(lenta, 200)).second
        teste.iniciarCaptura(t)
        alimentar(teste, t, 50, constante(carga, 250))
        assertTrue(teste.concluido)
        return teste
    }

    @Test
    fun `laudo resume vereditos e valores medidos`() {
        val teste = testeCompleto(12.6, 10.3, 14.2, 14.1)
        assertEquals(VereditoResumo("SAUDÁVEL", 0), LaudoBateria.vereditoBateria(teste))
        assertEquals(VereditoResumo("NORMAL", 0), LaudoBateria.vereditoAlternador(teste))

        val laudo = LaudoBateria.gerar(teste, "Toyota Hilux SW4 2015 (ABC-1234)", LocalDateTime.of(2026, 11, 22, 14, 30))
        assertTrue(laudo, laudo.startsWith("LAUDO — TESTE DE BATERIA E ALTERNADOR"))
        assertTrue(laudo, "22/11/2026 14:30" in laudo)
        assertTrue(laudo, "Veículo: Toyota Hilux SW4 2015 (ABC-1234)" in laudo)
        assertTrue(laudo, "Repouso (motor desligado): 12,60 V — Saudável" in laudo)
        assertTrue(laudo, "vale de 10,30 V" in laudo)
        assertTrue(laudo, "Marcha lenta: média 14,20 V" in laudo)
        assertTrue(laudo, "ALTERNADOR: NORMAL" in laudo)
    }

    @Test
    fun `laudo usa o pior veredito de cada componente`() {
        val teste = testeCompleto(12.2, 9.7, 14.2, 13.4)
        assertEquals(VereditoResumo("ATENÇÃO", 1), LaudoBateria.vereditoBateria(teste))
        assertEquals(VereditoResumo("SUBCARGA", 2), LaudoBateria.vereditoAlternador(teste))

        val sobrecarga = testeCompleto(12.7, 10.5, 15.1, 14.4)
        assertEquals("SOBRECARGA (regulador)", LaudoBateria.vereditoAlternador(sobrecarga).titulo)
    }

    @Test
    fun `etapas puladas aparecem como nao realizadas e LV RESET entra no laudo`() {
        val teste = TesteBateria()
        teste.pular()
        teste.iniciarCaptura(0)
        alimentar(teste, 0, 50, constante(12.4, 3) + listOf(LeituraTensao.LvReset) + constante(10.4, 5) + constante(14.0, 30))
        teste.pular()
        teste.pular()

        val laudo = LaudoBateria.gerar(teste, null, LocalDateTime.of(2026, 11, 22, 9, 0))
        assertTrue(laudo, "Repouso: não realizado" in laudo)
        assertTrue(laudo, "LV RESET) 1×" in laudo)
        assertTrue(laudo, "Marcha lenta: não realizado" in laudo)
        assertFalse(laudo, "Veículo:" in laudo)
        assertNotNull(LaudoBateria.vereditoBateria(teste).gravidade)
        assertNull(LaudoBateria.vereditoAlternador(teste).gravidade)
    }
}
