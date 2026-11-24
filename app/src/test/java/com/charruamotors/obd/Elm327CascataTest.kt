package com.charruamotors.obd

import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.obd.log.EntradaRegistro
import com.charruamotors.obd.pid.CatalogoTeste
import com.charruamotors.obd.pid.Pids
import com.charruamotors.obd.transporte.ObdTransport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Cascata de conexão em 3 camadas (`cascata_conexao.json`): OBD2 padrão → fallback de protocolo
 * físico (`ATSP6/7/5/3`) → fallback de serviço (modo 21 M-OBD/KWP → modo 22 UDS). O cenário da
 * Hilux SW4 3.0 D-4D 2013 (Denso, ELM327 v2.1) reproduz exatamente o Log de comunicação do carro.
 */
class Elm327CascataTest {

    /**
     * ELM327 + ECU roteirizados que acompanham o protocolo (`ATSP`) e o cabeçalho (`ATSH`). A
     * resposta é procurada como `"P<protocolo>:<comando>"`, depois `"<cabecalho>:<comando>"` e por
     * fim só pelo comando; sem roteiro, AT = `OK` e o resto = `NO DATA`.
     */
    private class EcuSimulada(respostas: Map<String, String>, val relogio: () -> Long = { 0L }) : ObdTransport {
        val respostas = respostas.toMutableMap()
        val enviados = mutableListOf<String>()
        val instantes = mutableListOf<Long>()
        var protocolo = "0"
        var cabecalho = "7DF"
        private var aberto = false

        override suspend fun conectar() {
            aberto = true
        }

        override suspend fun enviarComando(comando: String, timeoutMs: Long): String {
            if (!aberto) throw IOException("fechado")
            enviados += comando
            instantes += relogio()
            val r = respostas["P$protocolo:$comando"] ?: respostas["$cabecalho:$comando"] ?: respostas[comando]
            when {
                comando.startsWith("ATSP") -> {
                    protocolo = comando.removePrefix("ATSP")
                    return r ?: "OK\r\r"
                }
                comando.startsWith("ATSH") -> {
                    val resposta = r ?: "OK\r\r"
                    if ("OK" in resposta) cabecalho = comando.removePrefix("ATSH")
                    return resposta
                }
            }
            return r ?: if (comando.startsWith("AT")) "OK\r\r" else "NO DATA\r\r"
        }

        override fun fechar() {
            aberto = false
        }
    }

    private val dispositivo = DispositivoObd("OBDII", "00:11:22:33:44:55")

    private fun TestScope.cliente(t: EcuSimulada) = Elm327BluetoothClient(
        adaptador = null,
        catalogo = CatalogoTeste.padrao,
        criarTransporte = { t },
        dispatcher = StandardTestDispatcher(testScheduler),
        catalogoEstendido = CatalogoTeste.estendido,
        catalogoCascata = CatalogoTeste.cascata,
    )

    private fun Elm327BluetoothClient.eventos() =
        registroComunicacao().filterIsInstance<EntradaRegistro.Evento>().map { it.texto }

    private fun Elm327BluetoothClient.comandos() =
        registroComunicacao().filterIsInstance<EntradaRegistro.Comando>()

    /** ECU OBD2 padrão (CAN), igual à dos demais testes do cliente. */
    private fun ecuPadrao() = mutableMapOf(
        "ATZ" to "\r\rELM327 v1.5\r\r",
        "ATDP" to "AUTO, ISO 15765-4 (CAN 11/500)\r\r",
        "ATRV" to "12.6V\r\r",
        "0100" to "SEARCHING...\r41 00 BE 1F A8 13\r\r",
        "0120" to "41 20 80 02 20 01\r\r",
        "0140" to "41 40 44 00 00 10\r\r",
        "01001" to "41 00 BE 1F A8 13\r\r",
    )

    /** Exatamente o que o Log de comunicação mostrou na Hilux SW4 3.0 D-4D 2013 (Denso, ELM327 v2.1). */
    private fun sw4() = mutableMapOf(
        "ATZ" to "\r\rELM327 v2.1\r\r",
        "0100" to "SEARCHING...\r7F0111\r\r",
        "ATDP" to "AUTO, ISO 15765-4 (CAN 11/500)\r\r",
        "ATRV" to "13.8V\r\r",
        "7E0:210C" to "610C0C3B\r\r", // (256·0x0C + 0x3B) / 4 = 782,75 rpm
        "7E0:2105" to "61055F\r\r", // 0x5F − 40 = 55 °C
        "7E0:2104" to "61042D\r\r", // 0x2D · 100 / 255 = 17,6 %
        "7E0:210B" to "610B4A\r\r", // 74 kPa
        "7E0:13" to "5300\r\r", // nenhuma falha
        "7E0:213C" to "NO DATA\r\r",
        "7E0:21AD" to "NO DATA\r\r",
        "7E0:03" to "NO DATA\r\r",
        "7E0:1800FF00" to "7F1811\r\r",
        "7E0:0902" to "NO DATA\r\r",
        "7E1:2182" to "NO DATA\r\r",
    )

    // ---------------------------------------------------------------- camada 1: OBD2 padrão

    @Test
    fun `camada 1 com OBD2 padrao nao aciona nenhum fallback`() = runTest {
        val t = EcuSimulada(ecuPadrao())
        val obd = cliente(t)

        assertTrue(obd.conectar(dispositivo, "Toyota").isSuccess)

        val info = obd.infoConexao.value
        assertEquals(ModoComunicacaoEcu.OBD2_PADRAO, info.modo)
        assertEquals(InfoConexao.NOME_OBD2_PADRAO, info.nomeModo)
        assertNull(info.protocoloForcado)
        assertEquals(obd.pidsSuportados.value.count { it != "ATRV" }, info.sensoresResponderam)
        assertTrue(info.sensoresResponderam > 0)
        assertFalse(t.enviados.any { it.startsWith("ATSP") && it != "ATSP0" })
        assertFalse(t.enviados.any { it.startsWith("ATSH") || it.startsWith("21") || it.startsWith("22") })
    }

    // ---------------------------------------------------------------- camada 2: protocolo físico

    @Test
    fun `camada 2 forca ATSP6 7 e 5 quando o automatico falha e conecta no KWP`() = runTest {
        val ecu = ecuPadrao().apply {
            put("P0:0100", "SEARCHING...\rUNABLE TO CONNECT\r\r")
            put("P6:0100", "CAN ERROR\r\r")
            put("P7:0100", "NO DATA\r\r")
            put("P5:0100", "BUS INIT: ...OK\r41 00 BE 1F A8 13\r\r")
            put("P5:ATDP", "ISO 14230-4 (KWP FAST)\r\r")
            remove("0100")
        }
        val t = EcuSimulada(ecu)
        val obd = cliente(t)

        val r = obd.conectar(dispositivo)

        assertTrue(r.exceptionOrNull()?.message, r.isSuccess)
        assertEquals(listOf("ATSP0", "ATSP6", "ATSP7", "ATSP5"), t.enviados.filter { it.startsWith("ATSP") })
        assertFalse("parou no primeiro que respondeu", "ATSP3" in t.enviados)
        val info = obd.infoConexao.value
        assertEquals(ModoComunicacaoEcu.OBD2_PADRAO, info.modo)
        assertEquals("ISO 14230-4 (KWP FAST)", info.protocolo)
        assertTrue(info.protocoloForcado!!, info.protocoloForcado!!.startsWith("ATSP5"))
        assertTrue("010C" in obd.pidsSuportados.value)
        assertTrue(obd.eventos().any { "Camada 2" in it && "ATSP6, ATSP7, ATSP5, ATSP3" in it })
    }

    @Test
    fun `nenhum protocolo responde e a falha lista o que foi tentado`() = runTest {
        val t = EcuSimulada(ecuPadrao().apply { put("0100", "SEARCHING...\rUNABLE TO CONNECT\r\r") })
        val obd = cliente(t)

        val r = obd.conectar(dispositivo)

        val msg = r.exceptionOrNull()!!.message!!
        assertTrue(msg, "ignição" in msg && "ATSP0, ATSP6, ATSP7, ATSP5, ATSP3" in msg)
        assertTrue(obd.estado.value is EstadoConexaoObd.Erro)
        assertFalse("sem ECU no barramento, a camada 3 não é tentada", t.enviados.any { it.startsWith("ATSH") })
    }

    @Test
    fun `protocolo forcado que recusa o modo 01 vai para a camada 3 sem cabecalho fisico fora do CAN 11 bits`() = runTest {
        val ecu = ecuPadrao().apply {
            put("0100", "UNABLE TO CONNECT\r\r")
            put("P5:0100", "7F0111\r\r")
            put("P5:ATDP", "ISO 14230-4 (KWP FAST)\r\r")
            put("P5:210C", "610C0FA0\r\r") // 1000 rpm
        }
        val t = EcuSimulada(ecu)
        val obd = cliente(t)

        assertTrue(obd.conectar(dispositivo, "Mitsubishi").isSuccess)

        assertEquals(ModoComunicacaoEcu.MODO_21, obd.infoConexao.value.modo)
        assertFalse("na linha K o ATSH 7E0 não faz sentido", t.enviados.any { it.startsWith("ATSH") })
        assertEquals(1000.0, obd.lerTelemetria(intervaloMinimoMs = 250).first()[Pids.RPM]!!, 1e-9)
    }

    // ---------------------------------------------------------------- camada 3A: modo 21 (log da SW4)

    @Test
    fun `SW4 com 7F0111 conecta no modo 21 so com os PIDs espelhados e sem sondagens inuteis`() = runTest {
        val t = EcuSimulada(sw4())
        val obd = cliente(t)

        val r = obd.conectar(dispositivo, "Toyota")

        assertTrue(r.exceptionOrNull()?.message, r.isSuccess)
        assertEquals(EstadoConexaoObd.Conectado, obd.estado.value)
        val i0100 = t.enviados.indexOf("0100")
        assertEquals(listOf("ATDP", "ATRV", "ATSH7E0", "210C"), t.enviados.subList(i0100 + 1, i0100 + 5))
        assertEquals("ATSH 7E0 uma vez só", 1, t.enviados.count { it.startsWith("ATSH") })
        assertFalse("7F 01 11 é resposta da ECU: sem fallback de protocolo", t.enviados.any { it.startsWith("ATSP") && it != "ATSP0" })
        val proibidos = listOf("0120", "0140", "01001", "0902", "2101", "213C", "21AD", "03", "13", "1800FF00", "ATSH7E1", "2182")
        proibidos.forEach { assertFalse("$it não é sondado no handshake", it in t.enviados) }
        assertFalse("o modo 21 respondeu: o 22 nem é tentado", t.enviados.any { it.startsWith("22") })

        val info = obd.infoConexao.value
        assertEquals(ModoComunicacaoEcu.MODO_21, info.modo)
        assertEquals("Modo 21 (M-OBD / KWP)", info.nomeModo)
        assertEquals("AUTO, ISO 15765-4 (CAN 11/500)", info.protocolo)
        assertEquals(4, info.sensoresResponderam)
        assertEquals(listOf("210C", "210B", "2105", "2104"), info.comandosServico)
        assertEquals(setOf("ATRV", Pids.RPM, Pids.PRESSAO_COLETOR, Pids.TEMPERATURA_MOTOR, Pids.CARGA_MOTOR), obd.pidsSuportados.value)
        assertEquals(listOf("7E0 · 210C", "7E0 · 210B", "7E0 · 2105", "7E0 · 2104"), info.espelhos.map { it.rotuloComando })

        val trocas = obd.comandos().associateBy { it.comando }
        assertEquals("SEARCHING...\r7F0111\r\r", trocas["0100"]!!.resposta)
        assertEquals("610C0C3B\r\r", trocas["210C"]!!.resposta)
        assertTrue(obd.eventos().any { "7F 01 11" in it && "não suporta OBD2 padrão (Modo 01)" in it })
        assertTrue(obd.eventos().any { "Conectado em Modo 21 (M-OBD / KWP)" in it })
    }

    @Test
    fun `mapeamento semantico publica o modo 21 nas mesmas chaves dos mostradores e do grafico`() = runTest {
        val t = EcuSimulada(sw4())
        val obd = cliente(t)
        obd.conectar(dispositivo, "Toyota")
        t.enviados.clear()

        val leituras = obd.lerTelemetria(intervaloMinimoMs = 250, lentosPorVez = 1, ciclosPorLento = 1).take(3).toList()
        val dados = leituras.last()

        // Mesmas fórmulas do 010C / 0105 / 0104 / 010B.
        assertEquals(782.75, dados[Pids.RPM]!!, 1e-9)
        assertEquals(55.0, dados[Pids.TEMPERATURA_MOTOR]!!, 1e-9)
        assertEquals(45 * 100.0 / 255.0, dados[Pids.CARGA_MOTOR]!!, 1e-9)
        assertEquals(74.0, dados[Pids.PRESSAO_COLETOR]!!, 1e-9)
        assertEquals(13.8, dados.tensaoBateria!!, 1e-9)
        assertEquals(783, dados.rpm) // 782,75 arredondado, como no 010C
        assertEquals(55, dados.temperaturaMotor)

        // Fila rápida (RPM, MAP) em todo ciclo; fila lenta em rodízio; nada do modo 01 nem ATSH.
        assertEquals(3, t.enviados.count { it == "210C" })
        assertEquals(3, t.enviados.count { it == "210B" })
        assertTrue(t.enviados.count { it == "2105" } < 3)
        assertFalse(t.enviados.any { it.startsWith("01") || it.startsWith("ATSH") })
    }

    @Test
    fun `perfil Toyota no modo 21 so sonda o modulo do motor e o VIN nao gasta comando`() = runTest {
        val t = EcuSimulada(sw4())
        val obd = cliente(t)
        obd.conectar(dispositivo, "Toyota Hilux SW4")
        t.enviados.clear()

        val perfil = obd.ativarPerfilFabricante("Toyota")
        val vin = obd.lerVin()

        assertTrue(perfil is ResultadoPerfil.Indisponivel)
        // Requisições do motor (7E0 · 21) do perfil: bicos, tensão da ECU, barômetro, pedal, ATF e alavanca (o 7E0 2130 não é marcha);
        // depois a visita ao câmbio (só o 7E1 · 21D9: o 2130 devolve 0xFF fixo) e a volta ao 7E0.
        assertEquals(
            "7E0 · 21xx do perfil + sondagem do câmbio",
            listOf("21AD", "2142", "2133", "2149", "214A", "2182", "21D9", "ATSH7E1", "ATCRA7E9", "21D9", "ATSH7E0", "ATCRA"),
            t.enviados,
        )
        assertTrue(vin.isFailure)
        assertTrue(vin.exceptionOrNull()!!.message!!, "Modo 21" in vin.exceptionOrNull()!!.message!!)
        assertTrue(obd.lerCodigosPendentes().isFailure)
        assertTrue(obd.lerFreezeFrame().isFailure)
        assertEquals(12, t.enviados.size) // VIN, 07 e 02 não gastaram comando
    }

    @Test
    fun `perfil Toyota que responde no modo 21 entra na telemetria`() = runTest {
        val t = EcuSimulada(sw4().apply { put("7E0:21AD", "61AD00E600E1015200000000\r\r") })
        val obd = cliente(t)
        obd.conectar(dispositivo, "Toyota")

        val perfil = obd.ativarPerfilFabricante("Toyota")

        assertTrue(perfil is ResultadoPerfil.Ativo)
        assertEquals(setOf("TOYOTA_INJ1", "TOYOTA_INJ2", "TOYOTA_INJ3", "TOYOTA_INJ4"), (perfil as ResultadoPerfil.Ativo).pids.map { it.comando }.toSet())
        assertTrue("TOYOTA_INJ1" in obd.pidsSuportados.value)
    }

    @Test
    fun `falhas no modo 21 pelo servico 13`() = runTest {
        val semFalhas = cliente(EcuSimulada(sw4()))
        semFalhas.conectar(dispositivo, "Toyota")
        assertEquals(emptyList<DtcLido>(), semFalhas.lerCodigosFalha().getOrThrow())

        val t = EcuSimulada(sw4().apply { put("7E0:13", "530201020304\r\r") })
        val comFalhas = cliente(t)
        comFalhas.conectar(dispositivo, "Toyota")
        t.enviados.clear()

        assertEquals(listOf("P0102", "P0304"), comFalhas.lerCodigosFalha().getOrThrow().map { it.codigo })
        // DTC é comando de prazo longo: volta ao ATST padrão antes (responsePending 7F xx 78).
        assertEquals("13 positivo: nem 03 nem 18 00 FF 00", listOf("ATST32", "13"), t.enviados)
    }

    @Test
    fun `servico 13 recusado cai para o KWP 18 00 FF 00`() = runTest {
        val obd = cliente(EcuSimulada(sw4().apply {
            put("7E0:13", "7F1311\r\r")
            put("7E0:1800FF00", "5801012160\r\r")
        }))
        obd.conectar(dispositivo, "Toyota")

        assertEquals(listOf("P0121"), obd.lerCodigosFalha().getOrThrow().map { it.codigo })
    }

    @Test
    fun `limpeza no modo 21 usa 14 e cai para 14 FF 00`() = runTest {
        val direto = EcuSimulada(sw4().apply { put("7E0:14", "54\r\r") })
        val a = cliente(direto)
        a.conectar(dispositivo, "Toyota")
        direto.enviados.clear()
        assertTrue(a.limparCodigosFalha().isSuccess)
        assertEquals(listOf("ATST32", "14"), direto.enviados)

        val comFallback = EcuSimulada(sw4().apply {
            put("7E0:14", "7F1412\r\r")
            put("7E0:14FF00", "54FF00\r\r")
        })
        val b = cliente(comFallback)
        b.conectar(dispositivo, "Toyota")
        comFallback.enviados.clear()
        assertTrue(b.limparCodigosFalha().isSuccess)
        assertEquals(listOf("ATST32", "14", "14FF00"), comFallback.enviados)
    }

    @Test
    fun `limpeza no modo 21 com 7F 14 11 tenta 04 e depois 14 FF 00`() = runTest {
        val com04 = EcuSimulada(sw4().apply {
            put("7E0:14", "7F1411\r\r")
            put("7E0:04", "44\r\r")
        })
        val a = cliente(com04)
        a.conectar(dispositivo, "Toyota")
        com04.enviados.clear()
        assertTrue(a.limparCodigosFalha().isSuccess)
        assertEquals(listOf("ATST32", "14", "04"), com04.enviados)

        val ate14ff00 = EcuSimulada(sw4().apply {
            put("7E0:14", "7F1411\r\r")
            put("7E0:04", "7F0411\r\r")
            put("7E0:14FF00", "54FF00\r\r")
        })
        val b = cliente(ate14ff00)
        b.conectar(dispositivo, "Toyota")
        ate14ff00.enviados.clear()
        assertTrue(b.limparCodigosFalha().isSuccess)
        assertEquals(listOf("ATST32", "14", "04", "14FF00"), ate14ff00.enviados)
    }

    @Test
    fun `limpeza recusada pela ECU explica o NRC`() = runTest {
        val obd = cliente(EcuSimulada(sw4().apply {
            put("7E0:14", "7F1422\r\r")
            put("7E0:14FF00", "7F1422\r\r")
        }))
        obd.conectar(dispositivo, "Toyota")

        val msg = obd.limparCodigosFalha().exceptionOrNull()!!.message!!
        assertTrue(msg, "7F 14 22" in msg && "conditionsNotCorrect" in msg)
    }

    @Test
    fun `NO DATA no 0100 passa pela camada 2 e entra no modo 21 em CAN 11 bits`() = runTest {
        val t = EcuSimulada(sw4().apply { put("0100", "SEARCHING...\rNO DATA\r\r") })
        val obd = cliente(t)

        assertTrue(obd.conectar(dispositivo, "Toyota").isSuccess)

        assertEquals(listOf("ATSP0", "ATSP6", "ATSP7", "ATSP5", "ATSP3", "ATSP6"), t.enviados.filter { it.startsWith("ATSP") })
        val info = obd.infoConexao.value
        assertEquals(ModoComunicacaoEcu.MODO_21, info.modo)
        assertTrue(info.protocoloForcado!!.startsWith("ATSP6"))
        assertEquals(1, t.enviados.count { it == "ATSH7E0" })
    }

    @Test
    fun `adaptador v2_1 que recusa ATSH com interrogacao encerra com mensagem clara`() = runTest {
        val t = EcuSimulada(sw4().apply { put("ATSH7E0", "?\r\r") })
        val obd = cliente(t)

        val r = obd.conectar(dispositivo, "Toyota")

        val msg = r.exceptionOrNull()!!.message!!
        assertTrue(msg, "ELM327 v2.1" in msg && "cabeçalhos customizados" in msg && "ATSH 7E0" in msg)
        assertFalse(msg, "não respondeu como um adaptador ELM327" in msg)
        assertTrue(obd.estado.value is EstadoConexaoObd.Erro)
        assertFalse("sem ATSH não adianta sondar os modos 21/22", t.enviados.any { it.startsWith("21") || it.startsWith("22") })
        assertTrue("ATDP fica no log mesmo na falha", obd.comandos().any { it.comando == "ATDP" })
    }

    // ---------------------------------------------------------------- camada 3B: modo 22 UDS

    /** ECU UDS: recusa o modo 01 e o serviço 21, responde aos DIDs F4xx. */
    private fun ecuUds() = mutableMapOf(
        "ATZ" to "\r\rELM327 v2.1\r\r",
        "0100" to "7F0111\r\r",
        "ATDP" to "AUTO, ISO 15765-4 (CAN 11/500)\r\r",
        "ATRV" to "12.4V\r\r",
        "7E0:210C" to "7F2111\r\r",
        "7E0:22F40C" to "62F40C1AF8\r\r", // 1726 rpm
        "7E0:22F40D" to "62F40D3C\r\r", // 60 km/h
        "7E0:22F405" to "62F4057B\r\r", // 83 °C
        "7E0:22F410" to "62F41001F4\r\r", // 5 g/s
        "7E0:22F411" to "7F2231\r\r", // requestOutOfRange
    )

    @Test
    fun `modo 21 recusado com serviceNotSupported desiste na hora e o modo 22 UDS assume`() = runTest {
        val t = EcuSimulada(ecuUds())
        val obd = cliente(t)

        assertTrue(obd.conectar(dispositivo, "Honda").isSuccess)

        assertEquals("7F 21 11 no primeiro PID encerra o modo 21", listOf("210C"), t.enviados.filter { it.startsWith("21") })
        assertEquals(1, t.enviados.count { it.startsWith("ATSH") })
        val info = obd.infoConexao.value
        assertEquals(ModoComunicacaoEcu.MODO_22, info.modo)
        assertEquals("Modo 22 (UDS)", info.nomeModo)
        assertEquals(listOf("22F40C", "22F40D", "22F405", "22F410"), info.comandosServico)
        assertEquals(4, info.sensoresResponderam)

        val dados = obd.lerTelemetria(intervaloMinimoMs = 250, lentosPorVez = 10, ciclosPorLento = 1).first()
        assertEquals(1726.0, dados[Pids.RPM]!!, 1e-9)
        assertEquals(60.0, dados[Pids.VELOCIDADE]!!, 1e-9)
        assertEquals(83.0, dados[Pids.TEMPERATURA_MOTOR]!!, 1e-9)
        assertEquals(5.0, dados[Pids.MAF]!!, 1e-9)
        assertNull(dados[Pids.BORBOLETA])
    }

    @Test
    fun `marca VW tenta o modo 22 antes do 21`() = runTest {
        val t = EcuSimulada(ecuUds())
        val obd = cliente(t)

        assertTrue(obd.conectar(dispositivo, "Volkswagen Gol").isSuccess)

        assertEquals(ModoComunicacaoEcu.MODO_22, obd.infoConexao.value.modo)
        assertFalse(t.enviados.any { it.startsWith("21") })
    }

    @Test
    fun `modo 21 calado desiste depois de tres PIDs`() = runTest {
        val t = EcuSimulada(ecuUds().apply { remove("7E0:210C") }) // NO DATA em tudo do 21
        val obd = cliente(t)

        assertTrue(obd.conectar(dispositivo).isSuccess)

        assertEquals(Elm327BluetoothClient.SONDAGENS_ATE_DESISTIR, t.enviados.count { it.startsWith("21") })
        assertEquals(ModoComunicacaoEcu.MODO_22, obd.infoConexao.value.modo)
    }

    @Test
    fun `falhas e limpeza no modo 22 UDS`() = runTest {
        val t = EcuSimulada(ecuUds().apply {
            put("7E0:1902FF", "5902FF01021F08043000AF\r\r")
            put("7E0:14FFFFFF", "54\r\r")
        })
        val obd = cliente(t)
        obd.conectar(dispositivo, "Fiat")

        assertEquals(listOf("P0102", "P0430"), obd.lerCodigosFalha().getOrThrow().map { it.codigo })
        assertTrue(obd.limparCodigosFalha().isSuccess)
        assertTrue("14FFFFFF" in t.enviados)
        assertFalse(t.enviados.any { it == "03" || it == "04" })
    }

    @Test
    fun `nenhum modo de servico responde e a falha cita os dois`() = runTest {
        val r = cliente(EcuSimulada(sw4().apply {
            listOf("210C", "2105", "2104", "210B").forEach { remove("7E0:$it") }
        })).conectar(dispositivo, "Toyota")

        val msg = r.exceptionOrNull()!!.message!!
        assertTrue(msg, "Modo 21 (M-OBD / KWP)" in msg && "Modo 22 (UDS)" in msg && "Log de comunicação" in msg)
    }

    @Test
    fun `desconectar volta para o OBD2 padrao`() = runTest {
        val obd = cliente(EcuSimulada(sw4()))
        obd.conectar(dispositivo, "Toyota")
        obd.desconectar()
        assertEquals(InfoConexao(), obd.infoConexao.value)
        assertTrue(obd.pidsSuportados.value.isEmpty())
    }

    // ---------------------------------------------------------------- baixa latência

    /** A SW4 de um ELM327 que aceita o sufixo de resposta única também no modo 21. */
    private fun sw4ComSufixo() = sw4().apply {
        put("7E0:210C1", "610C0C3B\r\r")
        put("7E0:210B1", "610B4A\r\r")
        put("7E0:21051", "61055F\r\r")
        put("7E0:21041", "61042D\r\r")
    }

    @Test
    fun `SW4 aceita o sufixo 1 no modo 21 e o polling inteiro usa 210C1`() = runTest {
        val t = EcuSimulada(sw4ComSufixo())
        val obd = cliente(t)

        assertTrue(obd.conectar(dispositivo, "Toyota").isSuccess)

        assertTrue(obd.usaSufixoRespostaUnica)
        assertTrue(obd.usaTimingAdaptativoAgressivo)
        assertTrue(obd.usaTimeoutCurto)
        assertEquals("ATAT2 logo depois do ATSP0", "ATAT2", t.enviados[t.enviados.indexOf("ATSP0") + 1])
        // Sufixo testado no primeiro PID que respondeu; ATST0C validado com a mesma leitura.
        val iSt = t.enviados.indexOf("ATST0C")
        assertEquals(listOf("210C1", "ATST0C", "210C1"), t.enviados.subList(iSt - 1, iSt + 2))
        assertTrue(obd.eventos().any { "sufixo de resposta única: sim; ATAT2: sim; ATST 0C: sim" in it })

        t.enviados.clear()
        val dados = obd.lerTelemetria(lentosPorVez = 10, ciclosPorLento = 1).first()
        assertEquals(listOf("210C1", "210B1", "ATRV", "21051", "21041"), t.enviados)
        assertEquals(782.75, dados[Pids.RPM]!!, 1e-9)
        assertEquals(74.0, dados[Pids.PRESSAO_COLETOR]!!, 1e-9)
        assertEquals(55.0, dados[Pids.TEMPERATURA_MOTOR]!!, 1e-9)
    }

    @Test
    fun `DTC volta ao ATST padrao e o polling seguinte reaplica o curto`() = runTest {
        val t = EcuSimulada(sw4ComSufixo())
        val obd = cliente(t)
        obd.conectar(dispositivo, "Toyota")
        t.enviados.clear()

        assertTrue(obd.lerCodigosFalha().isSuccess)
        obd.lerCodigosFalha()
        assertEquals("ATST32 só quando muda", listOf("ATST32", "13", "13"), t.enviados)

        t.enviados.clear()
        obd.lerTelemetria().first()
        assertEquals(listOf("ATST0C", "210C1", "210B1", "ATRV"), t.enviados)
    }

    @Test
    fun `SW4 que recusa o sufixo no modo 21 segue sem ele`() = runTest {
        val t = EcuSimulada(sw4().apply { put("7E0:210C1", "?\r\r") })
        val obd = cliente(t)

        assertTrue(obd.conectar(dispositivo, "Toyota").isSuccess)

        assertFalse(obd.usaSufixoRespostaUnica)
        assertTrue("o ATST0C não depende do sufixo", obd.usaTimeoutCurto)
        val iSt = t.enviados.indexOf("ATST0C")
        assertEquals("validado sem o sufixo", "210C", t.enviados[iSt + 1])
        assertTrue(obd.eventos().any { "210C1 sem resposta válida" in it })

        t.enviados.clear()
        val dados = obd.lerTelemetria(lentosPorVez = 10, ciclosPorLento = 1).first()
        assertEquals(listOf("210C", "210B", "ATRV", "2105", "2104"), t.enviados)
        assertEquals(782.75, dados[Pids.RPM]!!, 1e-9)
    }

    @Test
    fun `clone que recusa ATAT2 e ATST com interrogacao conecta em silencio no timing padrao`() = runTest {
        val t = EcuSimulada(sw4ComSufixo().apply {
            put("ATAT2", "?\r\r")
            put("ATST0C", "?\r\r")
        })
        val obd = cliente(t)

        assertTrue(obd.conectar(dispositivo, "Toyota").isSuccess)

        assertFalse(obd.usaTimingAdaptativoAgressivo)
        assertFalse(obd.usaTimeoutCurto)
        assertTrue("o sufixo independe do timing", obd.usaSufixoRespostaUnica)
        assertEquals("ATST0C tentado uma vez só", 1, t.enviados.count { it == "ATST0C" })
        assertTrue(obd.eventos().any { "ATAT2 recusado" in it })
        assertTrue(obd.eventos().any { "ATST 0C recusado" in it })

        t.enviados.clear()
        assertTrue(obd.lerCodigosFalha().isSuccess)
        val dados = obd.lerTelemetria().first()
        assertFalse("sem timeout curto, nada de trocar o ATST", t.enviados.any { it.startsWith("ATST") })
        assertEquals(782.75, dados[Pids.RPM]!!, 1e-9)
    }

    @Test
    fun `ECU que nao responde no ATST0C volta ao ATST32`() = runTest {
        val ecu = EcuSimulada(sw4ComSufixo())
        var curto = false
        val t = object : ObdTransport by ecu {
            override suspend fun enviarComando(comando: String, timeoutMs: Long): String {
                if (comando.startsWith("ATST")) curto = comando == "ATST0C"
                val resposta = ecu.enviarComando(comando, timeoutMs)
                return if (curto && !comando.startsWith("AT")) "NO DATA\r\r" else resposta
            }
        }
        val obd = Elm327BluetoothClient(
            adaptador = null,
            catalogo = CatalogoTeste.padrao,
            criarTransporte = { t },
            dispatcher = StandardTestDispatcher(testScheduler),
            catalogoCascata = CatalogoTeste.cascata,
        )

        assertTrue(obd.conectar(dispositivo, "Toyota").isSuccess)

        assertFalse(obd.usaTimeoutCurto)
        val iSt = ecu.enviados.indexOf("ATST0C")
        assertEquals(listOf("ATST0C", "210C1", "ATST32"), ecu.enviados.subList(iSt, iSt + 3))
        assertTrue(obd.eventos().any { "voltando ao ATST 32" in it })
        assertEquals(782.75, obd.lerTelemetria().first()[Pids.RPM]!!, 1e-9)
    }

    @Test
    fun `sem pausa fixa entre comandos quando nada falha`() = runTest {
        val t = EcuSimulada(sw4ComSufixo(), relogio = { testScheduler.currentTime })
        val obd = cliente(t)
        obd.conectar(dispositivo, "Toyota")
        obd.lerTelemetria().first()

        assertEquals(0L, Elm327BluetoothClient.INTERVALO_ENTRE_COMANDOS_MS)
        val intervalos = t.instantes.zipWithNext { a, b -> b - a }
        assertTrue(intervalos.toString(), intervalos.all { it == 0L })
    }
}
