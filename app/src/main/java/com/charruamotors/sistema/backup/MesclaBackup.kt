package com.charruamotors.sistema.backup

import com.charruamotors.carros.MigracaoPerfisCarro
import com.charruamotors.carros.PreferenciasLegadas

/** Como aplicar um backup já validado (escolhido na confirmação). */
enum class ModoRestauracao {
    /** Troca tudo (banco, perfis e preferências) pelo conteúdo do arquivo. */
    SUBSTITUIR,

    /** Mantém tudo o que existe e só acrescenta os veículos do arquivo que ainda não estão no app (pela placa). */
    MESCLAR,
}

/**
 * Regras puras da restauração (v2.3.0): os perfis de um backup antigo (formato 1, Painel global) e
 * a mesclagem sem conflito de IDs. O resultado é sempre um [DadosBackup] completo e consistente,
 * aplicado de uma vez pela mesma transação do "substituir".
 */
object MesclaBackup {

    /**
     * Perfis a gravar: os do arquivo (formato 2) ou, num backup v1, os montados como na migração da
     * v2.3.0 — o Painel/HUD global vai para o veículo padrão da caixa-preta do arquivo.
     */
    fun perfisDe(dados: DadosBackup): DadosBackup {
        if (dados.perfisCarros.isNotEmpty() || dados.veiculos.isEmpty()) return dados
        val favorito = dados.caixaPretaRestauravel().veiculoId
        val perfis = MigracaoPerfisCarro.migrar(dados.veiculos, favorito, PreferenciasLegadas(painel = dados.preferencias.painel))
        val ativo = MigracaoPerfisCarro.veiculoPadrao(dados.veiculos, favorito)?.id ?: 0
        return dados.copy(perfisCarros = perfis, veiculoAtivoId = if (dados.veiculoAtivoId > 0) dados.veiculoAtivoId else ativo)
    }

    /** Veículos do [importado] que entram numa mesclagem (placa ainda inexistente no [atual]). */
    fun veiculosNovos(atual: DadosBackup, importado: DadosBackup) =
        importado.veiculos.filter { novo -> atual.veiculos.none { it.placa.equals(novo.placa, ignoreCase = true) } }

    /**
     * Mesclagem: tudo do [atual] fica como está (inclusive as preferências globais e o carro ativo);
     * cada veículo novo do [importado] entra com um ID livre, junto com as manutenções (e peças),
     * abastecimentos, leituras, códigos de falha e o perfil dele, todos renumerados.
     */
    fun mesclar(atual: DadosBackup, importado: DadosBackup): DadosBackup {
        val origem = perfisDe(importado)
        val novos = veiculosNovos(atual, origem)
        if (novos.isEmpty()) return atual

        var proximoVeiculo = (atual.veiculos.maxOfOrNull { it.id } ?: 0) + 1
        val idVeiculo = novos.associate { it.id to proximoVeiculo++ }

        var proximaManutencao = (atual.manutencoes.maxOfOrNull { it.id } ?: 0) + 1
        val manutencoes = origem.manutencoes.filter { it.veiculoId in idVeiculo }
        val idManutencao = manutencoes.associate { it.id to proximaManutencao++ }

        var proximaPeca = (atual.pecas.maxOfOrNull { it.id } ?: 0) + 1
        var proximoAbastecimento = (atual.abastecimentos.maxOfOrNull { it.id } ?: 0) + 1
        var proximaLeitura = (atual.leituras.maxOfOrNull { it.id } ?: 0) + 1
        var proximoCodigo = (atual.codigosFalha.maxOfOrNull { it.id } ?: 0) + 1

        return atual.copy(
            veiculos = atual.veiculos + novos.map { it.copy(id = idVeiculo.getValue(it.id)) },
            manutencoes = atual.manutencoes + manutencoes.map { it.copy(id = idManutencao.getValue(it.id), veiculoId = idVeiculo.getValue(it.veiculoId)) },
            pecas = atual.pecas + origem.pecas.filter { it.manutencaoId in idManutencao }
                .map { it.copy(id = proximaPeca++, manutencaoId = idManutencao.getValue(it.manutencaoId)) },
            abastecimentos = atual.abastecimentos + origem.abastecimentos.filter { it.veiculoId in idVeiculo }
                .map { it.copy(id = proximoAbastecimento++, veiculoId = idVeiculo.getValue(it.veiculoId)) },
            leituras = atual.leituras + origem.leituras.filter { it.veiculoId in idVeiculo }
                .map { it.copy(id = proximaLeitura++, veiculoId = idVeiculo.getValue(it.veiculoId)) },
            codigosFalha = atual.codigosFalha + origem.codigosFalha.filter { it.veiculoId in idVeiculo }
                .map { it.copy(id = proximoCodigo++, veiculoId = idVeiculo.getValue(it.veiculoId)) },
            perfisCarros = atual.perfisCarros + origem.perfisCarros.filter { it.veiculoId in idVeiculo }
                .map { it.copy(veiculoId = idVeiculo.getValue(it.veiculoId)) },
        )
    }
}
