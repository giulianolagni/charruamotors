package com.charruamotors.carros

import com.charruamotors.direcao.desempenho.RecordeDesempenho
import com.charruamotors.direcao.painel.ConfigPainel
import com.charruamotors.direcao.painel.SensorPainel
import com.charruamotors.domain.faixas.PresetVeiculo
import com.charruamotors.domain.faixas.ResolucaoPerfil
import com.charruamotors.domain.model.Veiculo
import com.charruamotors.obd.sessao.ConfigAutoConexao
import com.charruamotors.obd.sessao.GatilhoBluetooth

/**
 * O que havia de global até a v2.2.9 e passa a ser por veículo: o Painel/HUD (arquivo `painel`),
 * as bolinhas do Cluster (`cluster_painel`), o Bluetooth do carro (`auto_conexao`) e os recordes
 * (`desempenho`, por nome curto `Toyota SW4` ou `padrao`).
 */
data class PreferenciasLegadas(
    /** null = instalação nova (nenhuma personalização salva): o veículo padrão fica no padrão. */
    val painel: ConfigPainel? = null,
    val cluster: List<SensorPainel?> = PerfilCarro.CLUSTER_PADRAO,
    val gatilhoNome: String? = null,
    val gatilhoEndereco: String? = null,
    val recordes: Map<String, RecordeDesempenho> = emptyMap(),
)

/**
 * Migração única da v2.3.0 (lógica pura): as preferências globais vão **inteiras** para o veículo
 * padrão — o favorito da caixa-preta; sem ele, a SW4 cadastrada; sem ela, o primeiro veículo. Os
 * demais veículos já existentes ganham um perfil próprio no padrão (automático pelo cadastro, como
 * antes), com os recordes que já eram deles pelo nome curto. Nada é perdido: o veículo padrão
 * continua exatamente como o usuário deixou.
 */
object MigracaoPerfisCarro {

    /** Chave dos recordes sem veículo da v2.0–v2.2.9. */
    const val RECORDE_SEM_VEICULO = "padrao"

    fun veiculoPadrao(veiculos: List<Veiculo>, favorito: Long): Veiculo? =
        veiculos.firstOrNull { it.id == favorito }
            ?: veiculos.firstOrNull { runCatching { ResolucaoPerfil.deVeiculo(it).preset?.id }.getOrNull() == PresetVeiculo.ID_SW4_2013 }
            ?: veiculos.minByOrNull { it.id }

    /** Perfis de todos os [veiculos] (vazio sem veículos: a migração espera o primeiro cadastro). */
    fun migrar(veiculos: List<Veiculo>, favorito: Long, legado: PreferenciasLegadas): List<PerfilCarro> {
        val alvo = veiculoPadrao(veiculos, favorito) ?: return emptyList()
        return veiculos.map { v ->
            val recorde = legado.recordes["${v.marca} ${v.modelo}".trim()] ?: RecordeDesempenho()
            if (v.id == alvo.id) {
                val semVeiculo = legado.recordes[RECORDE_SEM_VEICULO]
                PerfilCarro(
                    veiculoId = v.id,
                    painel = legado.painel ?: ConfigPainel(),
                    cluster = legado.cluster,
                    gatilhoNome = legado.gatilhoNome,
                    gatilhoEndereco = legado.gatilhoEndereco,
                    recorde = recorde.com(semVeiculo?.melhor060, semVeiculo?.melhor0100),
                )
            } else {
                PerfilCarro(veiculoId = v.id, recorde = recorde)
            }
        }
    }
}

/** Regras puras de seleção entre os perfis (Bluetooth do carro, ordem do seletor). */
object SelecaoPerfisCarro {

    /**
     * Veículo cujo Bluetooth do carro (perfil) é o aparelho que acabou de conectar: o endereço MAC
     * vale mesmo que o nome mude; o nome ignora maiúsculas e espaços. Null = nenhum perfil reconhece.
     */
    fun veiculoDoGatilho(nome: String?, endereco: String?, perfis: List<PerfilCarro>): Long? =
        perfis.firstOrNull { p ->
            p.temGatilho && GatilhoBluetooth.corresponde(nome, endereco, ConfigAutoConexao(gatilhoNome = p.gatilhoNome!!, gatilhoEndereco = p.gatilhoEndereco))
        }?.veiculoId

    /** Veículo ativo efetivo: o salvo se existir; senão o favorito; senão o primeiro. */
    fun ativoEfetivo(ids: List<Long>, ativo: Long, favorito: Long): Long = when {
        ativo in ids -> ativo
        favorito in ids -> favorito
        else -> ids.firstOrNull() ?: 0L
    }
}
