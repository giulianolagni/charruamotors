package com.charruamotors.carros

import android.content.Context
import com.charruamotors.direcao.desempenho.RecordeDesempenho
import com.charruamotors.sistema.preferencias.ArmazenamentoChaveValor
import com.charruamotors.sistema.preferencias.ArmazenamentoComPrefixo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persistência dos [PerfilCarro] (v2.3.0), num único arquivo `perfis_carro`: cada veículo no próprio
 * escopo `carro.<id>.` ([escopo]), o índice dos ids, o veículo ativo e a flag da migração das
 * preferências globais antigas. Nenhuma escrita de um veículo toca as chaves de outro.
 *
 * Sem `Context` nos testes JVM: basta um [ArmazenamentoChaveValor] em memória.
 */
class GerenciadorPerfisCarro(private val armazenamento: ArmazenamentoChaveValor) {

    constructor(context: Context) : this(
        ArmazenamentoChaveValor.deSharedPreferences(
            context.applicationContext.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE),
        ),
    )

    private val _veiculoAtivo = MutableStateFlow(armazenamento.lerTexto(ATIVO)?.toLongOrNull()?.takeIf { it > 0 } ?: 0L)

    /** Veículo selecionado (o perfil em uso no Painel, HUD e Cluster); 0 = nenhum. */
    val veiculoAtivo: StateFlow<Long> = _veiculoAtivo.asStateFlow()

    private val _alteracoes = MutableStateFlow(0L)

    /** Conta as gravações de perfis (o seletor do topo e Meus Carros se redesenham). */
    val alteracoes: StateFlow<Long> = _alteracoes.asStateFlow()

    /** A migração das preferências globais (≤ v2.2.9) já rodou? */
    val migrado: Boolean get() = armazenamento.lerBooleano(MIGRADO, false)

    fun marcarMigrado() = armazenamento.gravar(mapOf(MIGRADO to true))

    /** Escopo de chaves só deste veículo (é o que a `PreferenciasPainel` do carro ativo recebe). */
    fun escopo(veiculoId: Long): ArmazenamentoComPrefixo = ArmazenamentoComPrefixo(armazenamento, prefixo(veiculoId))

    /** Veículos com perfil gravado. */
    fun ids(): Set<Long> = armazenamento.lerTexto(IDS).orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }.filter { it > 0 }.toSet()

    fun existe(veiculoId: Long): Boolean = veiculoId in ids()

    /** Perfil de [veiculoId] lido agora do armazenamento (sem perfil gravado = o padrão). */
    fun ler(veiculoId: Long): PerfilCarro = CodecPerfilCarro.decodificar(veiculoId, escopo(veiculoId)::lerTexto)

    /** Perfis de todos os [veiculos], na ordem dada. */
    fun lerTodos(veiculos: Collection<Long>): List<PerfilCarro> = veiculos.map(::ler)

    @Synchronized
    fun salvar(perfil: PerfilCarro) {
        require(perfil.veiculoId > 0) { "perfil sem veículo" }
        escopo(perfil.veiculoId).gravar(CodecPerfilCarro.codificar(perfil))
        val ids = ids()
        if (perfil.veiculoId !in ids) gravarIds(ids + perfil.veiculoId)
        _alteracoes.value++
    }

    /** Lê, transforma e grava o perfil de [veiculoId] (só grava se mudou). Devolve o resultado. */
    @Synchronized
    fun atualizar(veiculoId: Long, transformar: (PerfilCarro) -> PerfilCarro): PerfilCarro {
        val atual = ler(veiculoId)
        val novo = transformar(atual).copy(veiculoId = veiculoId)
        if (novo != atual || !existe(veiculoId)) salvar(novo)
        return novo
    }

    /** Cria o perfil com [inicial] só se o veículo ainda não tiver um (nunca sobrescreve). */
    @Synchronized
    fun criarSeAusente(veiculoId: Long, inicial: () -> PerfilCarro): PerfilCarro {
        if (existe(veiculoId)) return ler(veiculoId)
        return inicial().copy(veiculoId = veiculoId).also(::salvar)
    }

    /**
     * "Duplicar" em Meus Carros: o novo veículo nasce com o Painel, HUD, Cluster e o perfil técnico
     * da [origem]; recordes e o Bluetooth do carro não vêm junto (são de cada carro físico).
     */
    @Synchronized
    fun duplicar(origem: Long, destino: Long): PerfilCarro {
        val copia = ler(origem).copy(veiculoId = destino, recorde = RecordeDesempenho(), gatilhoNome = null, gatilhoEndereco = null)
        salvar(copia)
        return copia
    }

    /** Recorde 0–60 / 0–100 (só melhora) do veículo da sessão. */
    fun registrarRecorde(veiculoId: Long, t060: Double?, t0100: Double?) {
        if (veiculoId <= 0) return
        atualizar(veiculoId) { it.copy(recorde = it.recorde.com(t060, t0100)) }
    }

    /** Veículo excluído: apaga todas as chaves dele; se era o ativo, fica sem ativo. */
    @Synchronized
    fun excluir(veiculoId: Long) {
        escopo(veiculoId).gravar(CodecPerfilCarro.TODAS_AS_CHAVES.associateWith { null })
        gravarIds(ids() - veiculoId)
        if (_veiculoAtivo.value == veiculoId) ativar(0)
        _alteracoes.value++
    }

    /** Seleciona o veículo ativo (0 = nenhum). */
    @Synchronized
    fun ativar(veiculoId: Long) {
        val id = veiculoId.coerceAtLeast(0)
        if (id == _veiculoAtivo.value && armazenamento.lerTexto(ATIVO) != null) return
        armazenamento.gravar(mapOf(ATIVO to id.toString()))
        _veiculoAtivo.value = id
    }

    /** Restauração "substituir tudo" do backup: apaga todos os perfis e grava os do arquivo. */
    @Synchronized
    fun substituirTodos(perfis: List<PerfilCarro>, ativo: Long) {
        ids().forEach { escopo(it).gravar(CodecPerfilCarro.TODAS_AS_CHAVES.associateWith { null }) }
        gravarIds(emptySet())
        perfis.filter { it.veiculoId > 0 }.forEach(::salvar)
        ativar(if (perfis.any { it.veiculoId == ativo }) ativo else perfis.firstOrNull()?.veiculoId ?: 0)
        _alteracoes.value++
    }

    private fun gravarIds(ids: Set<Long>) = armazenamento.gravar(mapOf(IDS to ids.sorted().joinToString(",").ifEmpty { null }))

    companion object {
        const val ARQUIVO = "perfis_carro"
        const val IDS = "carros.ids"
        const val ATIVO = "carros.ativo"
        const val MIGRADO = "carros.migrado_v230"

        fun prefixo(veiculoId: Long) = "carro.$veiculoId."
    }
}
