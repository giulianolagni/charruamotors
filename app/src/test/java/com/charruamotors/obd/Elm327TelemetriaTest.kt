package com.charruamotors.obd

import com.charruamotors.domain.model.EstadoConexaoObd
import com.charruamotors.obd.pid.CatalogoEstendido
import com.charruamotors.obd.pid.CatalogoTeste
import com.charruamotors.obd.transporte.ObdTransport
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Telemetria em duas filas e perfis de montadora (ATSH + serviços 21/22) no cliente ELM327 real,
 * com um transporte roteirizado.
 */
class Elm327TelemetriaTest {

    private class TransporteRoteirizado(respostas: Map<String, String>) : ObdTransport {
        val respostas = respostas.toMutableMap()
        val enviados = mutableListOf<String>()
        private var aberto = false

        override suspend fun conectar() {
            aberto = true
        }

        override suspend fun enviarComando(comando: String, timeoutMs: Long): String {
            if (!aberto) throw IOException("fechado")
            enviados += comando
            return respostas[comando] ?: if (comando.startsWith("AT")) "OK\r\r" else "NO DATA\r\r"
        }

        override fun fechar() {
            aberto = false
        }
    }

    private val dispositivo = DispositivoObd("ELM327", "00:11:22:33:44:55")

    /**
     * Mesma ECU do `Elm327BluetoothClientTest`: suporta, do catálogo, 0104 0105 0106 0107 010C 010D
     * 010E 010F 0110 0111 011F 0121 012F 0133 0142 0146 015C + ATRV (sem MAP: a borboleta entra na fila rápida).
     */
    private fun ecu() = mutableMapOf(
        "ATZ" to "ELM327 v1.5\r\r",
        "ATDPN" to "A6\r\r",
        "ATRV" to "12.6V\r\r",
        "0100" to "41 00 BE 1F A8 13\r\r",
        "0120" to "41 20 80 02 20 01\r\r",
        "0140" to "41 40 44 00 00 10\r\r",
        "01001" to "41 00 BE 1F A8 13\r\r",
        "010C1" to "41 0C 1A F8\r\r",
        "010D1" to "41 0D 5A\r\r",
        "01101" to "41 10 01 F4\r\r",
        "01111" to "41 11 33\r\r",
        "01041" to "41 04 80\r\r",
        "01051" to "41 05 7B\r\r",
        "015C1" to "41 5C 87\r\r",
    )

    /** Sem MAP: RPM, velocidade e MAF (consumo por MAF); a borboleta vai para a fila lenta. */
    private val rapidos = listOf("010C1", "010D1", "01101")

    private fun TestScope.cliente(t: ObdTransport, estendido: CatalogoEstendido = CatalogoEstendido.VAZIO) =
        Elm327BluetoothClient(
            adaptador = null,
            catalogo = CatalogoTeste.padrao,
            criarTransporte = { t },
            dispatcher = StandardTestDispatcher(testScheduler),
            catalogoEstendido = estendido,
        )

    // ---------------------------------------------------------------- duas filas

    @Test
    fun `cada ciclo le os rapidos e 1 lento a cada 3 ciclos`() = runTest {
        val t = TransporteRoteirizado(ecu())
        val obd = cliente(t)
        obd.conectar(dispositivo)
        t.enviados.clear()

        val leituras = obd.lerTelemetria(intervaloMinimoMs = 250).take(7).toList()

        assertEquals(
            rapidos + "01041" + rapidos + rapidos +
                rapidos + "01051" + rapidos + rapidos +
                rapidos + "01061",
            t.enviados,
        )
        assertEquals(1726, leituras[0].rpm)
        assertEquals(90, leituras[0].velocidade)
        assertEquals(5.0, leituras[0]["0110"]!!, 1e-9)
        assertEquals(null, leituras[2].temperaturaMotor)
        assertEquals(83, leituras[3].temperaturaMotor)
    }

    @Test
    fun `diesel tira o MAF da fila rapida`() = runTest {
        val t = TransporteRoteirizado(ecu())
        val obd = cliente(t)
        obd.conectar(dispositivo)
        t.enviados.clear()

        obd.lerTelemetria(250, consumoPorMaf = false).take(3).toList()

        assertEquals(listOf("010C1", "010D1", "01041", "010C1", "010D1", "010C1", "010D1"), t.enviados)
    }

    @Test
    fun `lentos ficam retidos entre ciclos e a grade inteira se atualiza numa volta`() = runTest {
        val t = TransporteRoteirizado(ecu())
        val obd = cliente(t)
        obd.conectar(dispositivo)

        // 15 lentos (com a borboleta), 1 a cada 3 ciclos = 45 ciclos por volta.
        val leituras = obd.lerTelemetria(250).take(45).toList()
        leituras.drop(3).forEach { assertEquals(83, it.temperaturaMotor) } // lido no 4º ciclo e retido
        assertFalse("015C" in leituras[38].valores)
        assertEquals(95.0, leituras[39]["015C"]!!, 0.0) // óleo: 14º lento, 40º ciclo
        assertEquals(95.0, leituras[44]["015C"]!!, 0.0)
        assertEquals(12.6, leituras[42].tensaoBateria!!, 0.0) // ATRV: 15º lento
    }

    @Test
    fun `PID que para de responder sai do instantaneo no ciclo seguinte`() = runTest {
        val t = TransporteRoteirizado(ecu())
        val obd = cliente(t)
        obd.conectar(dispositivo)

        val leituras = obd.lerTelemetria(250)
            .onEach { t.respostas["010D1"] = "NO DATA\r\r" } // depois do 1º ciclo, a velocidade some
            .take(2).toList()
        assertEquals(90, leituras[0].velocidade)
        assertEquals(null, leituras[1].velocidade)
        assertEquals(1726, leituras[1].rpm)
    }

    @Test
    fun `scanner mudo em todos os comandos derruba a conexao`() = runTest {
        val mudo = object : ObdTransport {
            var inicializado = false
            val base = TransporteRoteirizado(ecu())
            override suspend fun conectar() = base.conectar()
            override suspend fun enviarComando(comando: String, timeoutMs: Long): String {
                if (inicializado) throw ObdTimeoutException("sem prompt")
                val r = base.enviarComando(comando, timeoutMs)
                if (comando == "01001") inicializado = true
                return r
            }
            override fun fechar() = base.fechar()
        }
        val obd = cliente(mudo)
        obd.conectar(dispositivo)
        assertTrue(obd.lerTelemetria(250).toList().isEmpty())
        assertTrue(obd.estado.value is EstadoConexaoObd.Erro)
    }

    // ---------------------------------------------------------------- perfis de montadora

    private val catalogoVw = CatalogoEstendido.deJson(
        """{"perfis":[{"fabricante":"Volkswagen","aliases":["vw"],"pids":[
            {"id":"VW_OLEO","cabecalho":"7E0","servico":"22","identificador":"11BD","nome":"Óleo","unidade":"°C","bytes":1,"formula":"A-40","min":-40,"max":215},
            {"id":"VW_DSG","cabecalho":"7E1","servico":"22","identificador":"2109","nome":"DSG","unidade":"°C","bytes":1,"formula":"A-40","min":-40,"max":215}
        ]},{"fabricante":"Toyota","pids":[
            {"id":"TOY_INJ1","cabecalho":"7E0","servico":"21","identificador":"13","nome":"Inj 1","unidade":"mm³","bytes":4,"formula":"(256*A+B-32768)/1000","min":-10,"max":10},
            {"id":"TOY_INJ2","cabecalho":"7E0","servico":"21","identificador":"13","nome":"Inj 2","unidade":"mm³","bytes":4,"formula":"(256*C+D-32768)/1000","min":-10,"max":10}
        ]}]}""",
    )

    private fun ecuVw() = ecu().apply {
        put("2211BD", "62 11 BD 82\r\r") // óleo 90 °C
        put("2211BD1", "62 11 BD 82\r\r") // com o sufixo de resposta única
        put("222109", "7F 22 31\r\r") // carro sem DSG: requestOutOfRange
    }

    @Test
    fun `perfil VW ativa so os DIDs que a ECU responde com o cabecalho fisico`() = runTest {
        val t = TransporteRoteirizado(ecuVw())
        val obd = cliente(t, catalogoVw)
        obd.conectar(dispositivo)
        t.enviados.clear()

        val r = obd.ativarPerfilFabricante("VW Gol") as ResultadoPerfil.Ativo
        assertEquals("Volkswagen", r.fabricante)
        assertEquals(listOf("VW_OLEO"), r.pids.map { it.comando })
        assertEquals(listOf("ATDPN", "ATSH7E0", "2211BD", "ATSH7E1", "222109"), t.enviados)
        assertTrue("VW_OLEO" in obd.pidsSuportados.value)
        assertFalse("VW_DSG" in obd.pidsSuportados.value)
    }

    private val catalogoChassi = CatalogoEstendido.deJson(
        """{"perfis":[{"fabricante":"Toyota","pids":[
            {"id":"TOY_VSC","cabecalho":"7B0","servico":"21","identificador":"03","nome":"VSC","unidade":"","bytes":1,"formula":"A","min":0,"max":4,"rotulos":{"0":"Normal","1":"Atuando"}},
            {"id":"TOY_PEDAL","cabecalho":"7E0","servico":"21","identificador":"49","nome":"Pedal","unidade":"%","bytes":1,"formula":"A*100/255","min":0,"max":100}
        ]}]}""",
    )

    @Test
    fun `modulo de chassi 7B0 ajusta o filtro ATCRA e o motor volta ao padrao`() = runTest {
        val t = TransporteRoteirizado(ecu().apply {
            put("2103", "61 03 01 00\r\r")
            put("2149", "61 49 CC\r\r")
        })
        val obd = cliente(t, catalogoChassi)
        obd.conectar(dispositivo)
        t.enviados.clear()

        val r = obd.ativarPerfilFabricante("Toyota SW4") as ResultadoPerfil.Ativo
        assertEquals(setOf("TOY_VSC", "TOY_PEDAL"), r.pids.map { it.comando }.toSet())
        // ABS/VSC responde em 7B8 (fora do filtro padrão 7E8–7EF): ATCRA 7B8 depois do ATSH; o motor desfaz.
        assertEquals(listOf("ATDPN", "ATSH7B0", "ATCRA7B8", "2103", "ATSH7E0", "ATCRA", "2149"), t.enviados)
        assertEquals("Atuando", r.pids.single { it.comando == "TOY_VSC" }.rotulo(1.0))

        // Na volta ao funcional (7DF) o filtro já é o padrão: não repete o ATCRA.
        t.enviados.clear()
        obd.lerCodigosFalha()
        assertFalse(t.enviados.any { it.startsWith("ATCRA") })
    }

    @Test
    fun `adaptador que recusa o ATCRA nao insiste a cada troca de cabecalho`() = runTest {
        val t = TransporteRoteirizado(ecu().apply {
            put("ATCRA7B8", "?\r\r")
            put("2149", "61 49 CC\r\r")
        })
        val obd = cliente(t, catalogoChassi)
        obd.conectar(dispositivo)
        t.enviados.clear()

        val r = obd.ativarPerfilFabricante("Toyota") as ResultadoPerfil.Ativo
        assertEquals(listOf("TOY_PEDAL"), r.pids.map { it.comando }) // o VSC não respondeu sem o filtro
        assertEquals(1, t.enviados.count { it.startsWith("ATCRA") })
    }

    @Test
    fun `cabecalho funcional volta antes de comandos OBD padrao`() = runTest {
        val t = TransporteRoteirizado(ecuVw().apply { put("03", "43 01 03 00\r\r") })
        val obd = cliente(t, catalogoVw)
        obd.conectar(dispositivo)
        obd.ativarPerfilFabricante("VW")
        t.enviados.clear()

        assertEquals(listOf("P0300"), obd.lerCodigosFalha().getOrThrow().map { it.codigo })
        assertEquals(listOf("ATST32", "ATSH7DF", "03"), t.enviados)
        obd.lerCodigosPendentes()
        assertEquals("07", t.enviados.last()) // já no 7DF: não repete o ATSH
        assertEquals(1, t.enviados.count { it.startsWith("ATSH") })
    }

    @Test
    fun `PID de montadora entra na fila lenta da telemetria`() = runTest {
        val t = TransporteRoteirizado(ecuVw())
        val obd = cliente(t, catalogoVw)
        obd.conectar(dispositivo)
        obd.ativarPerfilFabricante("VW")
        t.enviados.clear()

        // 15 lentos padrão + VW_OLEO = 16; o DID é o último da fila: 46º ciclo.
        val leituras = obd.lerTelemetria(250).take(46).toList()
        assertEquals(90.0, leituras.last()["VW_OLEO"]!!, 0.0)
        val i = t.enviados.indexOf("2211BD1") // com o sufixo de resposta única
        assertEquals("ATSH7E0", t.enviados[i - 1])
        // Ciclo agrupado por cabeçalho: os PIDs padrão do mesmo ciclo vêm antes do DID, então
        // na volta só há uma ida ao 7DF (o perfil deixou o 7E1) e uma ao 7E0.
        assertEquals(listOf("ATSH7DF", "ATSH7E0"), t.enviados.filter { it.startsWith("ATSH") })
    }

    @Test
    fun `ciclo que comeca no cabecalho fisico le o DID antes de voltar ao funcional`() = runTest {
        val t = TransporteRoteirizado(ecuVw())
        val obd = cliente(t, catalogoVw)
        obd.conectar(dispositivo)
        obd.ativarPerfilFabricante("VW")
        t.enviados.clear()

        // Todo ciclo tem o DID. Sem a ordenação seriam 2 ATSH por ciclo (6); com ela, o ciclo 2
        // começa no 7E0 que o ciclo 1 deixou e o ciclo 3 no 7DF que o ciclo 2 deixou.
        obd.lerTelemetria(250, lentosPorVez = 20, ciclosPorLento = 1).take(3).toList()
        assertEquals(listOf("ATSH7DF", "ATSH7E0", "ATSH7DF", "ATSH7E0"), t.enviados.filter { it.startsWith("ATSH") })
    }

    @Test
    fun `respostas compartilhadas sao lidas com uma unica requisicao`() = runTest {
        val t = TransporteRoteirizado(ecu().apply {
            put("2113", "61 13 80 64 7F 9C\r\r")
            put("21131", "61 13 80 64 7F 9C\r\r")
        })
        val obd = cliente(t, catalogoVw)
        obd.conectar(dispositivo)
        t.enviados.clear()

        val r = obd.ativarPerfilFabricante("Toyota") as ResultadoPerfil.Ativo
        assertEquals(listOf("TOY_INJ1", "TOY_INJ2"), r.pids.map { it.comando })
        assertEquals(1, t.enviados.count { it == "2113" })

        // Na telemetria os dois injetores ocupam uma só vez da fila lenta: 1 requisição por volta
        // (16º lento → 46º ciclo), já com o sufixo de resposta única.
        val leituras = obd.lerTelemetria(250).take(46).toList()
        assertEquals(0.1, leituras.last()["TOY_INJ1"]!!, 1e-9)
        assertEquals(-0.1, leituras.last()["TOY_INJ2"]!!, 1e-9)
        assertEquals(1, t.enviados.count { it == "2113" })
        assertEquals(1, t.enviados.count { it == "21131" })
    }

    @Test
    fun `requisicao truncada com o sufixo e conferida uma vez e segue sem ele`() = runTest {
        val t = TransporteRoteirizado(ecu().apply {
            put("2113", "61 13 80 64 7F 9C\r\r")
            put("21131", "61 13 80 64\r\r") // multi-frame cortado no 1º quadro: faltam C e D
        })
        val obd = cliente(t, catalogoVw)
        obd.conectar(dispositivo)
        obd.ativarPerfilFabricante("Toyota")
        t.enviados.clear()

        val leituras = obd.lerTelemetria(250, lentosPorVez = 20, ciclosPorLento = 1).take(3).toList()

        assertEquals("conferida uma única vez", 1, t.enviados.count { it == "21131" })
        assertEquals(3, t.enviados.count { it == "2113" })
        assertEquals(t.enviados.indexOf("21131") + 1, t.enviados.indexOf("2113"))
        leituras.forEach {
            assertEquals(0.1, it["TOY_INJ1"]!!, 1e-9)
            assertEquals(-0.1, it["TOY_INJ2"]!!, 1e-9)
        }
        assertTrue("o modo 01 continua com o sufixo", "010C1" in t.enviados)
    }

    @Test
    fun `protocolo que nao e CAN 11 bits nao usa cabecalho fisico`() = runTest {
        val t = TransporteRoteirizado(ecuVw().apply { put("ATDPN", "A3\r\r") }) // ISO 9141-2
        val obd = cliente(t, catalogoVw)
        obd.conectar(dispositivo)
        t.enviados.clear()

        val r = obd.ativarPerfilFabricante("VW") as ResultadoPerfil.Indisponivel
        assertTrue(r.motivo, r.motivo.contains("CAN"))
        assertEquals(listOf("ATDPN"), t.enviados)
    }

    @Test
    fun `marca sem perfil ou ECU sem resposta nao ativam nada`() = runTest {
        val t = TransporteRoteirizado(ecu())
        val obd = cliente(t, catalogoVw)
        obd.conectar(dispositivo)
        t.enviados.clear()

        assertEquals(ResultadoPerfil.SemPerfil, obd.ativarPerfilFabricante("Fiat"))
        assertEquals(ResultadoPerfil.SemPerfil, obd.ativarPerfilFabricante(null))
        assertTrue(t.enviados.isEmpty())

        val semResposta = obd.ativarPerfilFabricante("VW") // ECU devolve NO DATA aos DIDs
        assertTrue(semResposta is ResultadoPerfil.Indisponivel)
        assertTrue(obd.pidsSuportados.value.none { it.startsWith("VW_") })
    }

    @Test
    fun `perfil desconectado e indisponivel e desconectar limpa os PIDs ativados`() = runTest {
        val t = TransporteRoteirizado(ecuVw())
        val obd = cliente(t, catalogoVw)
        assertTrue(obd.ativarPerfilFabricante("VW") is ResultadoPerfil.Indisponivel)

        obd.conectar(dispositivo)
        obd.ativarPerfilFabricante("VW")
        obd.desconectar()
        assertTrue(obd.pidsSuportados.value.isEmpty())

        // Reconectar começa no cabeçalho padrão (ATZ), sem ATSH herdado.
        obd.conectar(dispositivo)
        t.enviados.clear()
        obd.lerCodigosFalha()
        assertEquals(listOf("ATST32", "03"), t.enviados)
    }
}
