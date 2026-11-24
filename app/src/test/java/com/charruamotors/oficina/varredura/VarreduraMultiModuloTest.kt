package com.charruamotors.oficina.varredura

import com.charruamotors.obd.DispositivoObd
import com.charruamotors.obd.EcuSimulada
import com.charruamotors.obd.ObdClientFake
import com.charruamotors.obd.ObdException
import com.charruamotors.obd.RespostaBruta
import com.charruamotors.obd.pid.CatalogoTeste
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import kotlin.random.Random

/** Presets 7B0/7C0 (opcionais), Varredura Completa SW4 (7E0 + 7E1) e o relatório consolidado. */
class VarreduraMultiModuloTest {

    private val identificador = IdentificadorPids.de(CatalogoTeste.padrao, CatalogoTeste.estendido, CatalogoTeste.cascata)
    private val quando = LocalDateTime.of(2026, 11, 23, 14, 30)
    private val TODOS_OS_MODULOS = VarreduraPids.PRESET_COMPLETA_SW4.modulos + VarreduraPids.PRESET_CHASSI_SW4.modulos

    private suspend fun fakeConectado(): ObdClientFake {
        val obd = ObdClientFake(random = Random(11), latenciaMs = 0, latenciaComandoMs = 0)
        obd.conectar(DispositivoObd("Teste", "00:00:00:00:00:01"))
        obd.iniciarSessaoManual()
        return obd
    }

    @Test
    fun `presets incluem ABS VSC 7B0, Painel 4WD 7C0 e a Varredura Completa SW4`() {
        val nomes = VarreduraPids.PRESETS.map { it.nome }
        assertTrue("Toyota ABS / VSC / Estabilidade (7B0 · 2100–21FF)" in nomes)
        assertTrue("Toyota Painel / 4WD / Carroceria (7C0 · 2100–21FF)" in nomes)
        val completa = VarreduraPids.PRESET_COMPLETA_SW4
        assertEquals("Varredura Completa SW4 (7E0 + 7E1)", completa.nome)
        assertEquals(listOf("7E0", "7E1"), completa.modulos.map { it.cabecalho })
        // 7B0 e 7C0 não respondem na SW4: preset manual opcional, fora da varredura completa.
        val chassi = VarreduraPids.PRESET_CHASSI_SW4
        assertEquals(listOf("7B0", "7C0"), chassi.modulos.map { it.cabecalho })
        assertEquals(listOf(completa, chassi), VarreduraPids.PRESETS.filter { it.multiModulo })
        assertTrue(completa.modulos.all { it.servico == "21" && it.inicio == 0x00 && it.fim == 0xFF })
        assertTrue("7B0" in VarreduraPids.CABECALHOS && "7C0" in VarreduraPids.CABECALHOS)
        assertEquals("ABS / VSC / Estabilidade", VarreduraPids.nomeModulo("7b0"))
        assertEquals("Módulo 7A0", VarreduraPids.nomeModulo("7A0"))
        // Os presets de um módulo seguem como antes.
        assertFalse(VarreduraPids.PRESETS.first().multiModulo)
    }

    @Test
    fun `modulo de chassi recebe ATCRA depois do ATSH e o filtro volta ao padrao no fim`() = runTest {
        val enviados = mutableListOf<String>()
        val resultado = VarreduraPids.executar(ConfigVarredura("7B0", "21", 0x01, 0x03), enviar = { c ->
            enviados += c
            Result.success(RespostaBruta(c, if (c.startsWith("AT")) "OK\r\r" else "7F2131\r\r", 30))
        })
        assertEquals(listOf("ATSH 7B0", "ATCRA 7B8", "2101", "2102", "2103", "ATCRA"), enviados)
        assertTrue(resultado.completa)

        // Motor e câmbio já estão no filtro padrão (7E8–7EF): sem ATCRA.
        enviados.clear()
        VarreduraPids.executar(ConfigVarredura("7E1", "21", 0x82, 0x82), enviar = { c ->
            enviados += c
            Result.success(RespostaBruta(c, "OK\r\r", 30))
        })
        assertEquals(listOf("ATSH 7E1", "2182"), enviados)
    }

    @Test
    fun `parar no meio ainda desfaz o ATCRA`() = runTest {
        val enviados = mutableListOf<String>()
        var parar = false
        val r = VarreduraPids.executar(
            ConfigVarredura("7C0", "21", 0x00, 0xFF),
            enviar = { c ->
                enviados += c
                if (c == "2102") parar = true
                Result.success(RespostaBruta(c, if (c.startsWith("AT")) "OK\r\r" else "7F2131\r\r", 30))
            },
            deveParar = { parar },
        )
        assertEquals(VarreduraPids.PARADA_USUARIO, r.motivoParada)
        assertEquals("ATCRA", enviados.last())
    }

    @Test
    fun `varredura dos 4 modulos no simulador encontra motor, cambio, ABS e painel`() = runTest {
        val obd = fakeConectado()
        val etapas = mutableListOf<String>()
        val multi = VarreduraPids.executarModulos(
            TODOS_OS_MODULOS,
            enviar = { obd.enviarComandoManual(it, registrar = false) },
            aoIniciarModulo = { i, c -> etapas += "${i + 1}:${c.cabecalho}" },
        )
        assertEquals(listOf("1:7E0", "2:7E1", "3:7B0", "4:7C0"), etapas)
        assertNull(multi.motivoParada)
        assertEquals(4, multi.modulos.size)
        assertEquals(4, multi.modulosPresentes.size)
        assertEquals(4 * 256, multi.total)
        assertEquals(4 * 256, multi.consultados)

        fun positivos(cabecalho: String) = multi.modulos.single { it.config.cabecalho == cabecalho }.positivas.map { it.comando }.toSet()
        assertTrue(positivos("7E0").containsAll(listOf("2149", "214A", "21AD", "2142", "2133")))
        assertTrue(positivos("7E1").containsAll(listOf("2130", "21D9")))
        assertEquals((EcuSimulada.IDS_ABS + setOf(0x01, 0x03)).map { "21%02X".format(it) }.toSet(), positivos("7B0"))
        assertEquals((EcuSimulada.IDS_PAINEL + setOf(0x01)).map { "21%02X".format(it) }.toSet(), positivos("7C0"))
        // Ao fim, o simulador está de volta ao filtro padrão (o ATCRA foi desfeito).
        assertEquals("NO DATA\r\r", obd.enviarComandoManual("2103").getOrThrow().resposta)
    }

    @Test
    fun `modulo ausente e pulado cedo e os demais continuam`() = runTest {
        val obd = fakeConectado()
        val modulos = listOf(ConfigVarredura("7E2", "21", 0x00, 0xFF), ConfigVarredura("7B0", "21", 0x00, 0x0F))
        val multi = VarreduraPids.executarModulos(modulos, enviar = { obd.enviarComandoManual(it, registrar = false) })
        val ausente = multi.modulos[0]
        assertEquals(VarreduraPids.NO_DATA_PARA_MODULO_AUSENTE, ausente.resultados.size)
        assertTrue(ausente.motivoParada!!.contains("ausente"))
        assertFalse(ausente.conexaoPerdida)
        assertEquals(16, multi.modulos[1].resultados.size)
        assertEquals(listOf(multi.modulos[1]), multi.modulosPresentes)
    }

    @Test
    fun `conexao perdida encerra a varredura multi-modulo`() = runTest {
        var enviados = 0
        val multi = VarreduraPids.executarModulos(
            VarreduraPids.PRESET_COMPLETA_SW4.modulos,
            enviar = { c ->
                enviados++
                if (enviados > 5) Result.failure(ObdException("Conexão com o scanner perdida")) else Result.success(RespostaBruta(c, "OK\r\r", 10))
            },
        )
        assertEquals(1, multi.modulos.size)
        assertTrue(multi.modulos[0].conexaoPerdida)
        assertEquals("Conexão com o scanner perdida", multi.motivoParada)
    }

    @Test
    fun `relatorio consolidado traz uma secao por modulo com os bytes brutos`() = runTest {
        val obd = fakeConectado()
        val multi = VarreduraPids.executarModulos(
            TODOS_OS_MODULOS,
            enviar = { obd.enviarComandoManual(it, registrar = false) },
        )
        val identificacoes = multi.modulos.associate { m ->
            m.config.cabecalho to identificador.identificarTodos(m.resultados, m.config.cabecalho)
        }
        val texto = RelatorioVarredura.gerarConsolidado(
            multi, quando, veiculo = "Toyota SW4 (ABC1D23)", protocolo = "ISO 15765-4 (CAN 11/500)",
            duracaoMs = 61_500, identificacoes = identificacoes,
        )
        assertTrue(texto.startsWith("VARREDURA COMPLETA MULTI-MÓDULO — Charrua OBD Pro"))
        assertTrue(texto.contains("23/11/2026 14:30 · Toyota SW4 (ABC1D23)"))
        assertTrue(texto.contains("Módulos: 7E0 (Motor) + 7E1 (Câmbio) + 7B0 (ABS / VSC / Estabilidade) + 7C0 (Painel / 4WD / Carroceria)"))
        assertTrue(texto.contains("Consultados: 1024/1024 · responderam: ${multi.positivas} · módulos presentes: 4/4 · 61,5 s"))
        for (cab in listOf("7E0", "7E1", "7B0", "7C0")) assertTrue(cab, texto.contains("=== $cab · "))
        // Cada seção na ordem planejada, com a faixa do módulo.
        val ordem = listOf("=== 7E0", "=== 7E1", "=== 7B0", "=== 7C0").map { texto.indexOf(it) }
        assertEquals(ordem.sorted(), ordem)
        assertTrue(texto.contains("=== 7B0 · ABS / VSC / Estabilidade (7B0 · 2100–21FF) ==="))
        // Bytes brutos e cruzamento com o catálogo: pedal no motor, alavanca no câmbio, VSC no ABS, 4x4 no painel.
        assertTrue(texto.contains("2149 → 61 49"))
        assertTrue(Regex("21D9 → 61 D9 00 00 00 04 .*= Posição da alavanca do câmbio.*: D").containsMatchIn(texto))
        assertTrue(Regex("2103 → 61 03 00 00 00 02 .*= VSC / controle de estabilidade: Normal").containsMatchIn(texto))
        assertTrue(Regex("2101 → 61 01 00 00 00 00 .*= Tração 4x4 / bloqueio do diferencial: 4x2 \\(H2\\)").containsMatchIn(texto))
        assertTrue(texto.contains("Respostas negativas: 7F 21 31"))
    }

    @Test
    fun `relatorio consolidado de varredura parada lista o motivo e os modulos nao consultados`() {
        val motor = ConfigVarredura("7E0", "21", 0x00, 0x01)
        val abs = ConfigVarredura("7B0", "21", 0x00, 0x01)
        val feito = ResultadoVarredura(
            motor,
            listOf(ResultadoPid("2101", TipoRespostaPid.POSITIVA, bytes = listOf(0x61, 0x01, 0xAA))),
            2,
            VarreduraPids.PARADA_USUARIO,
        )
        val texto = RelatorioVarredura.gerarConsolidado(ResultadoVarreduraMulti(listOf(motor, abs), listOf(feito), VarreduraPids.PARADA_USUARIO), quando)
        assertTrue(texto.contains("Interrompida: ${VarreduraPids.PARADA_USUARIO}"))
        assertTrue(texto.contains("2101 → 61 01 AA  [1 byte de dados]"))
        assertTrue(texto.contains("=== 7B0 · ABS / VSC / Estabilidade (7B0 · 2100–2101) ===\nNão consultado."))
        assertTrue(texto.contains("módulos presentes: 1/2"))
    }

    @Test
    fun `identificacao guarda o cabecalho e o rotulo do estado`() {
        val r = ResultadoPid("21D9", TipoRespostaPid.POSITIVA, bytes = listOf(0x61, 0xD9, 0x00, 0x00, 0x00, 0x04, 0x42, 0xE0))
        val id = identificador.identificar(r, "7e1")
        assertEquals("7E1", id.cabecalho)
        assertEquals(StatusIdentificacao.IDENTIFICADO, id.status)
        assertEquals("D", id.rotulo)
        assertEquals("D", id.valorTexto)
        assertEquals("7E0", identificador.identificar(r.copy(tipo = TipoRespostaPid.SEM_DADOS, bytes = emptyList()), "7E0").cabecalho)
    }

    @Test
    fun `identificador nao cruza o motor com os modulos de chassi`() {
        // 2101 é o 4x4 só no painel 7C0; no motor 7E0 é um bloco desconhecido.
        val bloco = ResultadoPid("2101", TipoRespostaPid.POSITIVA, bytes = listOf(0x61, 0x01, 0x00, 0x7D))
        assertEquals(StatusIdentificacao.DESCONHECIDO, identificador.identificar(bloco, "7E0").status)
        assertEquals("Tração 4x4 / bloqueio do diferencial", identificador.identificar(bloco, "7C0").conhecido!!.nome)
        assertNull(identificador.conhecido("7B0", "2101"))
        // No trem de força o fallback segue valendo (espelho do 7E0 consultado no 7E1).
        assertEquals("Modo 21 (M-OBD / KWP)", identificador.conhecido("7E1", "210C")!!.origem)
    }
}
