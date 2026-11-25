package com.charruamotors.carros

import com.charruamotors.direcao.desempenho.RecordeDesempenho
import com.charruamotors.direcao.painel.CodecPainel
import com.charruamotors.direcao.painel.ConfigHud
import com.charruamotors.direcao.painel.ConfigPainel
import com.charruamotors.direcao.painel.EstiloMostrador
import com.charruamotors.direcao.painel.PreferenciasPainel
import com.charruamotors.direcao.painel.PresetHud
import com.charruamotors.direcao.painel.SecaoPainel
import com.charruamotors.direcao.painel.SensorPainel
import com.charruamotors.direcao.painel.TamanhoHud
import com.charruamotors.direcao.painel.TamanhoHudEsportivo
import com.charruamotors.domain.faixas.NivelPerfil
import com.charruamotors.domain.faixas.PresetVeiculo
import com.charruamotors.domain.model.TipoCombustivel
import com.charruamotors.domain.model.Veiculo
import com.charruamotors.domain.perfis.CarregadorPerfis
import com.charruamotors.obd.sessao.VeiculoSessao
import com.charruamotors.sistema.preferencias.ArmazenamentoChaveValor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.3.0 — Perfis 100% isolados por veículo: migração das preferências globais, isolamento do
 * Painel/HUD/Cluster/recordes entre carros, carro novo no OBD2 Genérico (sem PIDs Toyota/GM),
 * perfil técnico (faixas, câmbio, camada) e o Bluetooth do carro por veículo.
 */
class PerfisIsoladosTest {

    private class Memoria : ArmazenamentoChaveValor {
        val valores = mutableMapOf<String, Any?>()
        override fun lerTexto(chave: String): String? = valores[chave] as? String
        override fun lerBooleano(chave: String, padrao: Boolean): Boolean = valores[chave] as? Boolean ?: padrao
        override fun gravar(valores: Map<String, Any?>) {
            valores.forEach { (k, v) -> if (v == null) this.valores.remove(k) else this.valores[k] = v }
        }
    }

    private val sw4 = Veiculo(id = 7, marca = "Toyota", modelo = "SW4", ano = 2013, placa = "ABC1D23", kmAtual = 182_450, tipoCombustivel = TipoCombustivel.DIESEL)
    private val cruze = Veiculo(id = 9, marca = "Chevrolet", modelo = "Cruze", ano = 2018, placa = "CRZ1A18", kmAtual = 60_000, tipoCombustivel = TipoCombustivel.FLEX)
    private val kwid = Veiculo(id = 12, marca = "Renault", modelo = "Kwid", ano = 2021, placa = "KWD2C21", kmAtual = 30_000, tipoCombustivel = TipoCombustivel.FLEX)
    private val corolla = Veiculo(id = 13, marca = "Toyota", modelo = "Corolla", ano = 2020, placa = "COR0L20", kmAtual = 40_000, tipoCombustivel = TipoCombustivel.FLEX)
    private val onix = Veiculo(id = 14, marca = "Chevrolet", modelo = "Onix", ano = 2016, placa = "ONX1A16", kmAtual = 70_000, tipoCombustivel = TipoCombustivel.FLEX)

    /** O Painel que o usuário tinha na v2.2.9 (global). */
    private val painelLegado = CodecPainel.normalizar(ConfigPainel()
        .ocultar(SecaoPainel.MUSICA)
        .adicionarMostrador()
        .definirEstilo(0, EstiloMostrador.DIGITAL)
        .alternarListaRapida("21D9")
        .copy(hud = ConfigHud(preset = PresetHud.ESPORTIVO, tamanhoEsportivo = TamanhoHudEsportivo.GRANDE)))

    // ---------------------------------------------------------------- migração das preferências antigas

    @Test
    fun `migracao leva o painel global inteiro para a SW4 sem perder nada`() {
        val legado = PreferenciasLegadas(
            painel = painelLegado,
            cluster = listOf(SensorPainel.TEMP_CAMBIO, null, SensorPainel.TURBO, null),
            gatilhoNome = "Toyota SW4",
            gatilhoEndereco = "AA:BB:CC:DD:EE:01",
            recordes = mapOf("Toyota SW4" to RecordeDesempenho(9.8, 14.2), MigracaoPerfisCarro.RECORDE_SEM_VEICULO to RecordeDesempenho(9.1, null)),
        )
        val perfis = MigracaoPerfisCarro.migrar(listOf(cruze, sw4), favorito = 0, legado = legado).associateBy { it.veiculoId }

        val doSw4 = perfis.getValue(sw4.id)
        assertEquals(painelLegado, doSw4.painel)
        assertEquals(PresetHud.ESPORTIVO, doSw4.painel.hud.preset)
        assertEquals(TamanhoHudEsportivo.GRANDE, doSw4.painel.hud.tamanhoEsportivo)
        assertEquals(listOf(SensorPainel.TEMP_CAMBIO, null, SensorPainel.TURBO, null), doSw4.cluster)
        assertEquals("Toyota SW4", doSw4.gatilhoNome)
        assertEquals("AA:BB:CC:DD:EE:01", doSw4.gatilhoEndereco)
        // O recorde "sem veículo" melhora o da SW4 (9,1 < 9,8) e o 0–100 dela fica.
        assertEquals(RecordeDesempenho(9.1, 14.2), doSw4.recorde)
        assertEquals(CamadaPreferida.AUTO, doSw4.camada)
        assertNull(doSw4.perfilFaixasId)

        // O Cruze já cadastrado ganha perfil próprio no padrão (nada vaza da SW4).
        val doCruze = perfis.getValue(cruze.id)
        assertEquals(ConfigPainel(), doCruze.painel)
        assertEquals(PerfilCarro.CLUSTER_PADRAO, doCruze.cluster)
        assertNull(doCruze.gatilhoNome)
        assertEquals(RecordeDesempenho(), doCruze.recorde)
    }

    @Test
    fun `migracao respeita o favorito da caixa-preta e espera o primeiro veiculo`() {
        val legado = PreferenciasLegadas(painel = painelLegado)
        val perfis = MigracaoPerfisCarro.migrar(listOf(sw4, cruze), favorito = cruze.id, legado = legado).associateBy { it.veiculoId }
        assertEquals(painelLegado, perfis.getValue(cruze.id).painel)
        assertEquals(ConfigPainel(), perfis.getValue(sw4.id).painel)
        assertEquals(cruze, MigracaoPerfisCarro.veiculoPadrao(listOf(sw4, cruze), cruze.id))
        // Sem favorito: a SW4 (perfil validado), mesmo cadastrada depois.
        assertEquals(sw4, MigracaoPerfisCarro.veiculoPadrao(listOf(cruze, sw4), 0))
        // Sem SW4: o primeiro cadastrado.
        assertEquals(cruze, MigracaoPerfisCarro.veiculoPadrao(listOf(kwid, cruze), 0))
        assertTrue(MigracaoPerfisCarro.migrar(emptyList(), 0, legado).isEmpty())
    }

    @Test
    fun `migracao gravada no armazenamento e lida de volta pelo escopo do carro`() {
        val memoria = Memoria()
        val perfis = GerenciadorPerfisCarro(memoria)
        assertFalse(perfis.migrado)
        MigracaoPerfisCarro.migrar(listOf(sw4, cruze), 0, PreferenciasLegadas(painel = painelLegado)).forEach { p -> perfis.criarSeAusente(p.veiculoId) { p } }
        perfis.ativar(sw4.id)
        perfis.marcarMigrado()

        val reaberto = GerenciadorPerfisCarro(memoria)
        assertTrue(reaberto.migrado)
        assertEquals(sw4.id, reaberto.veiculoAtivo.value)
        assertEquals(setOf(sw4.id, cruze.id), reaberto.ids())
        assertEquals(painelLegado, PreferenciasPainel(reaberto.escopo(sw4.id)).config.value)
        assertEquals(ConfigPainel(), PreferenciasPainel(reaberto.escopo(cruze.id)).config.value)
        // criarSeAusente nunca sobrescreve um perfil já existente (a migração não roda duas vezes por cima).
        reaberto.criarSeAusente(sw4.id) { PerfilCarro(sw4.id) }
        assertEquals(painelLegado, reaberto.ler(sw4.id).painel)
    }

    // ---------------------------------------------------------------- isolamento entre carros

    @Test
    fun `trocar layout e HUD no Cruze nao altera a SW4`() {
        val memoria = Memoria()
        val perfis = GerenciadorPerfisCarro(memoria)
        perfis.salvar(PerfilCarro(sw4.id, painel = painelLegado))
        perfis.salvar(PerfilCarro(cruze.id))

        // App aberto na SW4.
        val painel = PreferenciasPainel(perfis.escopo(sw4.id))
        assertEquals(painelLegado, painel.config.value)

        // Seleciona o Cruze e personaliza tudo.
        perfis.ativar(cruze.id)
        painel.trocarArmazenamento(perfis.escopo(cruze.id))
        assertEquals(ConfigPainel(), painel.config.value)
        painel.atualizar { it.aplicarLayout(ConfigPainel.LAYOUT_TURBO_FLEX).ocultar(SecaoPainel.CRONOMETRO) }
        painel.definirPresetHud(PresetHud.ESPORTIVO)
        painel.atualizar { it.copy(hud = it.hud.copy(tamanhoEsportivo = TamanhoHudEsportivo.PEQUENO, tamanho = TamanhoHud.MINIMIZADO)) }
        val doCruze = painel.config.value

        // A SW4 continua exatamente como estava.
        assertEquals(painelLegado, perfis.ler(sw4.id).painel)
        assertEquals(TamanhoHudEsportivo.GRANDE, perfis.ler(sw4.id).painel.hud.tamanhoEsportivo)
        assertEquals(doCruze, perfis.ler(cruze.id).painel)
        assertEquals(TamanhoHudEsportivo.PEQUENO, perfis.ler(cruze.id).painel.hud.tamanhoEsportivo)

        // Volta para a SW4: o Painel/HUD dela; o do Cruze segue guardado.
        perfis.ativar(sw4.id)
        painel.trocarArmazenamento(perfis.escopo(sw4.id))
        assertEquals(painelLegado, painel.config.value)
        painel.alternarPresetHud() // na SW4: Esportivo → Padrão
        assertEquals(PresetHud.PADRAO, perfis.ler(sw4.id).painel.hud.preset)
        assertEquals(PresetHud.ESPORTIVO, perfis.ler(cruze.id).painel.hud.preset)

        // Nenhuma chave fora dos escopos: tudo em carro.<id>. ou no índice carros.*.
        assertTrue(memoria.valores.keys.all { it.startsWith("carro.${sw4.id}.") || it.startsWith("carro.${cruze.id}.") || it.startsWith("carros.") })
    }

    @Test
    fun `cluster calibracao do perfil tecnico e recordes ficam em cada carro`() {
        val perfis = GerenciadorPerfisCarro(Memoria())
        perfis.atualizar(cruze.id) { it.copy(cluster = listOf(SensorPainel.TURBO, SensorPainel.TEMP_CAMBIO, null, null), camada = CamadaPreferida.OBD2) }
        perfis.registrarRecorde(cruze.id, 7.4, 10.9)
        perfis.registrarRecorde(sw4.id, 9.9, null)
        perfis.registrarRecorde(sw4.id, 10.5, 15.0) // pior no 0–60: não troca; 0–100 entra

        assertEquals(PerfilCarro.CLUSTER_PADRAO, perfis.ler(sw4.id).cluster)
        assertEquals(CamadaPreferida.AUTO, perfis.ler(sw4.id).camada)
        assertEquals(RecordeDesempenho(7.4, 10.9), perfis.ler(cruze.id).recorde)
        assertEquals(RecordeDesempenho(9.9, 15.0), perfis.ler(sw4.id).recorde)
        assertEquals(listOf(SensorPainel.TURBO, SensorPainel.TEMP_CAMBIO, null, null), perfis.ler(cruze.id).cluster)
        // Recorde sem veículo (sessão sem cadastro) não grava em ninguém.
        perfis.registrarRecorde(0, 1.0, 1.0)
        assertEquals(RecordeDesempenho(7.4, 10.9), perfis.ler(cruze.id).recorde)
    }

    @Test
    fun `excluir apaga so o carro e duplicar copia painel sem recordes nem bluetooth`() {
        val memoria = Memoria()
        val perfis = GerenciadorPerfisCarro(memoria)
        perfis.salvar(PerfilCarro(sw4.id, painel = painelLegado, gatilhoNome = "Toyota SW4", recorde = RecordeDesempenho(9.0, 14.0), camada = CamadaPreferida.MODO_21))
        perfis.salvar(PerfilCarro(cruze.id))
        perfis.ativar(cruze.id)

        val copia = perfis.duplicar(sw4.id, 21)
        assertEquals(painelLegado, copia.painel)
        assertEquals(CamadaPreferida.MODO_21, copia.camada)
        assertNull(copia.gatilhoNome)
        assertEquals(RecordeDesempenho(), copia.recorde)
        assertEquals(copia, perfis.ler(21))

        perfis.excluir(cruze.id)
        assertEquals(setOf(sw4.id, 21L), perfis.ids())
        assertTrue(memoria.valores.keys.none { it.startsWith("carro.${cruze.id}.") })
        assertEquals(0L, perfis.veiculoAtivo.value) // era o ativo
        assertEquals(painelLegado, perfis.ler(sw4.id).painel)
    }

    @Test
    fun `codec do perfil faz ida e volta completa e ignora lixo`() {
        val completo = PerfilCarro(
            veiculoId = 7,
            painel = painelLegado,
            cluster = listOf(null, SensorPainel.TEMP_OLEO, SensorPainel.TURBO, SensorPainel.TENSAO),
            perfilFaixasId = PresetVeiculo.ID_SW4_2013,
            perfilCambioId = "toyota_a750f",
            camada = CamadaPreferida.MODO_21,
            gatilhoNome = "Toyota SW4",
            gatilhoEndereco = "AA:BB:CC:DD:EE:01",
            favoritosGraficos = listOf("010C", "21D9"),
            recorde = RecordeDesempenho(9.5, 13.75),
        )
        val chaves = CodecPerfilCarro.codificar(completo)
        assertEquals(completo, CodecPerfilCarro.decodificar(7) { chaves[it] })

        val lixo = mapOf(
            CodecPerfilCarro.CLUSTER to "XYZ,,TURBO",
            CodecPerfilCarro.CAMADA to "MODO_99",
            CodecPerfilCarro.RECORDE_060 to "-3",
            CodecPerfilCarro.RECORDE_0100 to "abc",
        )
        val lido = CodecPerfilCarro.decodificar(5) { lixo[it] }
        assertEquals(listOf(null, null, SensorPainel.TURBO, null), lido.cluster)
        assertEquals(CamadaPreferida.AUTO, lido.camada)
        assertEquals(RecordeDesempenho(), lido.recorde)
    }

    // ---------------------------------------------------------------- carro novo (3º em diante)

    @Test
    fun `carro novo sem perfil especifico comeca limpo no OBD2 Generico`() {
        val resolucao = PerfilCarro(kwid.id).resolver(kwid)
        assertNotEquals(NivelPerfil.EXATO, resolucao.nivel)
        val perfil = PerfilCarro.paraCarroNovo(kwid.id, resolucao)
        assertTrue(perfil.obd2Generico)
        assertEquals(CamadaPreferida.OBD2, perfil.camada)
        assertEquals(ConfigPainel(), perfil.painel)
        assertEquals(PerfilCarro.TAG_GENERICO, perfil.tag(perfil.resolver(kwid)))

        val sessao = VeiculoSessao.de(kwid, perfil)
        assertNull("sem marca para a cascata/perfil de montadora", sessao.marcaMontadora)
        assertNull(sessao.servicoPreferido)
        assertNull(sessao.cambio)
        assertEquals(NivelPerfil.OBD2_PADRAO, sessao.resolucao?.nivel)
        assertNull(sessao.resolucao?.preset)
    }

    @Test
    fun `Toyota ou Chevrolet sem perfil nao herdam os PIDs da SW4 nem do Cruze`() {
        for (v in listOf(corolla, onix)) {
            val resolucao = PerfilCarro(v.id).resolver(v)
            if (resolucao.nivel == NivelPerfil.EXATO) continue // Onix Turbo tem perfil base; o 2016 aspirado não
            val perfil = PerfilCarro.paraCarroNovo(v.id, resolucao)
            val sessao = VeiculoSessao.de(v, perfil)
            assertTrue(v.descricao, perfil.obd2Generico)
            // A marca continua no cadastro (DTCs da montadora), mas não vai para o Modo 21/22 nem para os PIDs estendidos.
            assertEquals(v.marca, sessao.marca)
            assertNull(v.descricao, sessao.marcaMontadora)
            assertNull(v.descricao, sessao.cambio)
        }
        val corollaGenerico = VeiculoSessao.de(corolla, PerfilCarro.paraCarroNovo(corolla.id, PerfilCarro(corolla.id).resolver(corolla)))
        assertNull(corollaGenerico.marcaMontadora)
    }

    @Test
    fun `carro novo com perfil exato fica no automatico com a marca e o layout sugerido`() {
        val doSw4 = PerfilCarro.paraCarroNovo(sw4.id, PerfilCarro(sw4.id).resolver(sw4))
        assertFalse(doSw4.obd2Generico)
        assertEquals(CamadaPreferida.AUTO, doSw4.camada)
        val sessao = VeiculoSessao.de(sw4, doSw4)
        assertEquals("Toyota", sessao.marcaMontadora)
        assertEquals(PresetVeiculo.ID_SW4_2013, sessao.resolucao?.preset?.id)
        assertEquals(PerfilCarro.TAG_MODO_21, doSw4.tag(doSw4.resolver(sw4)))

        val doCruze = PerfilCarro.paraCarroNovo(cruze.id, PerfilCarro(cruze.id).resolver(cruze))
        assertEquals(ConfigPainel.LAYOUT_TURBO_FLEX, doCruze.painel.mostradores.map { it.sensor })
        assertEquals(PerfilCarro.TAG_CAN, doCruze.tag(doCruze.resolver(cruze)))
        assertEquals("Chevrolet", VeiculoSessao.de(cruze, doCruze).marcaMontadora)
    }

    // ---------------------------------------------------------------- perfil técnico

    @Test
    fun `perfil tecnico escolhido vale sobre o cadastro`() {
        // Kwid vinculado ao genérico Flex Turbo do catálogo.
        val comFaixas = PerfilCarro(kwid.id, perfilFaixasId = "generico_flex_turbo")
        assertEquals(NivelPerfil.GENERICO, comFaixas.resolver(kwid).nivel)
        assertEquals("generico_flex_turbo", comFaixas.resolver(kwid).preset?.id)

        // SW4 com o perfil exato forçado e o câmbio manual no lugar da A750F.
        val catalogo = CarregadorPerfis.catalogo
        val manual = catalogo.cambios.keys.first { it == "manual" }
        val sw4Manual = PerfilCarro(sw4.id, perfilFaixasId = PresetVeiculo.ID_SW4_2013, perfilCambioId = manual)
        assertEquals(manual, VeiculoSessao.de(sw4, sw4Manual).cambio?.id)
        assertEquals("toyota_a750f", VeiculoSessao.de(sw4, PerfilCarro(sw4.id)).cambio?.id)

        // Id desconhecido (perfil removido do catálogo): volta ao reconhecimento pelo cadastro.
        assertEquals(PresetVeiculo.ID_SW4_2013, PerfilCarro(sw4.id, perfilFaixasId = "nao_existe").resolver(sw4).preset?.id)
    }

    @Test
    fun `camada preferida decide marca e servico da conexao`() {
        val auto = VeiculoSessao.de(sw4, PerfilCarro(sw4.id))
        assertEquals("Toyota", auto.marcaMontadora)
        assertNull(auto.servicoPreferido)

        val obd2 = PerfilCarro(sw4.id, camada = CamadaPreferida.OBD2)
        assertNull(VeiculoSessao.de(sw4, obd2).marcaMontadora)
        assertEquals(PerfilCarro.TAG_CAN, obd2.tag(obd2.resolver(sw4)))

        val modo21 = PerfilCarro(cruze.id, camada = CamadaPreferida.MODO_21)
        assertEquals("21", VeiculoSessao.de(cruze, modo21).servicoPreferido)
        assertEquals("Chevrolet", VeiculoSessao.de(cruze, modo21).marcaMontadora)
        assertEquals(PerfilCarro.TAG_MODO_21, modo21.tag(modo21.resolver(cruze)))

        // OBD2 Genérico nunca pede serviço de montadora, mesmo com a camada Modo 21 salva.
        assertNull(PerfilCarro(kwid.id, perfilFaixasId = PerfilCarro.OBD2_GENERICO, camada = CamadaPreferida.MODO_21).servicoPreferido)
        assertEquals(CamadaPreferida.AUTO, CamadaPreferida.deCodigo(null))
        assertEquals(CamadaPreferida.MODO_21, CamadaPreferida.deCodigo("modo_21"))
    }

    @Test
    fun `protocolos CAN reconhecidos para a tag`() {
        assertTrue(PerfilCarro.protocoloCan("ATSP6"))
        assertTrue(PerfilCarro.protocoloCan("atsp9"))
        assertFalse(PerfilCarro.protocoloCan("ATSP0"))
        assertFalse(PerfilCarro.protocoloCan("ATSP3"))
        assertFalse(PerfilCarro.protocoloCan("ATSP"))
    }

    // ---------------------------------------------------------------- Bluetooth do carro e seleção

    @Test
    fun `bluetooth do carro escolhe o veiculo pelo MAC ou pelo nome`() {
        val perfis = listOf(
            PerfilCarro(sw4.id, gatilhoNome = "Toyota SW4", gatilhoEndereco = "AA:BB:CC:DD:EE:01"),
            PerfilCarro(cruze.id, gatilhoNome = "MyLink Cruze"),
            PerfilCarro(kwid.id),
        )
        assertEquals(sw4.id, SelecaoPerfisCarro.veiculoDoGatilho("Outro nome", "aa:bb:cc:dd:ee:01", perfis))
        assertEquals(cruze.id, SelecaoPerfisCarro.veiculoDoGatilho("mylink  cruze", "11:22:33:44:55:66", perfis))
        assertNull(SelecaoPerfisCarro.veiculoDoGatilho("Fone JBL", "99:99:99:99:99:99", perfis))
        assertNull(SelecaoPerfisCarro.veiculoDoGatilho(null, null, perfis))
    }

    @Test
    fun `ativo efetivo e restauracao de todos os perfis`() {
        assertEquals(9L, SelecaoPerfisCarro.ativoEfetivo(listOf(7, 9), ativo = 9, favorito = 7))
        assertEquals(7L, SelecaoPerfisCarro.ativoEfetivo(listOf(7, 9), ativo = 3, favorito = 7))
        assertEquals(7L, SelecaoPerfisCarro.ativoEfetivo(listOf(7, 9), ativo = 0, favorito = 0))
        assertEquals(0L, SelecaoPerfisCarro.ativoEfetivo(emptyList(), ativo = 3, favorito = 3))

        val memoria = Memoria()
        val perfis = GerenciadorPerfisCarro(memoria)
        perfis.salvar(PerfilCarro(99, painel = painelLegado))
        perfis.substituirTodos(listOf(PerfilCarro(sw4.id, camada = CamadaPreferida.MODO_21), PerfilCarro(cruze.id)), ativo = cruze.id)
        assertEquals(setOf(sw4.id, cruze.id), perfis.ids())
        assertTrue(memoria.valores.keys.none { it.startsWith("carro.99.") })
        assertEquals(cruze.id, perfis.veiculoAtivo.value)
        assertEquals(CamadaPreferida.MODO_21, perfis.ler(sw4.id).camada)
        // Ativo inexistente no arquivo: o primeiro perfil.
        perfis.substituirTodos(listOf(PerfilCarro(sw4.id)), ativo = 55)
        assertEquals(sw4.id, perfis.veiculoAtivo.value)
    }
}
