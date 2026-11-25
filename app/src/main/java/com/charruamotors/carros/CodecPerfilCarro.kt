package com.charruamotors.carros

import com.charruamotors.direcao.desempenho.RecordeDesempenho
import com.charruamotors.direcao.painel.ClusterPainel
import com.charruamotors.direcao.painel.CodecPainel
import com.charruamotors.direcao.painel.SensorPainel

/**
 * Chaves de um [PerfilCarro] dentro do escopo `carro.<id>.` (v2.3.0). O Painel/HUD usa as mesmas
 * chaves do [CodecPainel] (a [com.charruamotors.direcao.painel.PreferenciasPainel] do carro ativo
 * grava direto nelas); o resto do perfil fica nas chaves abaixo. Também é o formato do perfil no
 * backup `.json` (objeto `painel` + campos), então o round-trip é o mesmo nos dois lugares.
 */
object CodecPerfilCarro {

    /** Versão do esquema do perfil por veículo (gravada em [SCHEMA]); sobe quando um campo mudar de significado. */
    const val VERSAO_SCHEMA = 1

    const val SCHEMA = "schema"
    const val CLUSTER = "cluster"
    const val PERFIL_FAIXAS = "perfil_faixas"
    const val PERFIL_CAMBIO = "perfil_cambio"
    const val CAMADA = "camada"
    const val GATILHO_NOME = "gatilho_nome"
    const val GATILHO_ENDERECO = "gatilho_endereco"
    const val FAVORITOS_GRAFICOS = "favoritos_graficos"
    const val RECORDE_060 = "recorde_060"
    const val RECORDE_0100 = "recorde_0100"

    /** Bolinha do Cluster sem escolha (padrão/automático). */
    private const val BOLINHA_PADRAO = "-"
    private const val SEPARADOR = ","

    /** Chaves próprias do perfil (sem as do Painel). */
    val CHAVES_PERFIL = listOf(
        SCHEMA, CLUSTER, PERFIL_FAIXAS, PERFIL_CAMBIO, CAMADA, GATILHO_NOME, GATILHO_ENDERECO,
        FAVORITOS_GRAFICOS, RECORDE_060, RECORDE_0100,
    )

    /** Campos do perfil (sem o Painel); null = chave removida. */
    fun codificarCampos(p: PerfilCarro): Map<String, String?> = mapOf(
        SCHEMA to VERSAO_SCHEMA.toString(),
        CLUSTER to p.cluster.joinToString(SEPARADOR) { it?.name ?: BOLINHA_PADRAO },
        PERFIL_FAIXAS to p.perfilFaixasId?.takeIf { it.isNotBlank() },
        PERFIL_CAMBIO to p.perfilCambioId?.takeIf { it.isNotBlank() },
        CAMADA to p.camada.name,
        GATILHO_NOME to p.gatilhoNome?.trim()?.takeIf { it.isNotEmpty() },
        GATILHO_ENDERECO to p.gatilhoEndereco?.takeIf { it.isNotBlank() },
        FAVORITOS_GRAFICOS to p.favoritosGraficos.takeIf { it.isNotEmpty() }?.joinToString(SEPARADOR),
        RECORDE_060 to p.recorde.melhor060?.toString(),
        RECORDE_0100 to p.recorde.melhor0100?.toString(),
    )

    /** Perfil completo (Painel + campos), pronto para o [com.charruamotors.sistema.preferencias.ArmazenamentoChaveValor.gravar]. */
    fun codificar(p: PerfilCarro): Map<String, String?> = CodecPainel.codificar(p.painel) + codificarCampos(p)

    /** Todas as chaves que um perfil pode ocupar (para apagá-lo). */
    val TODAS_AS_CHAVES: Set<String> get() = codificar(PerfilCarro(0)).keys + CHAVES_PERFIL

    /** Lê o perfil de [veiculoId]; chaves ausentes ou inválidas voltam ao padrão (nunca lança). */
    fun decodificar(veiculoId: Long, ler: (String) -> String?): PerfilCarro = PerfilCarro(
        veiculoId = veiculoId,
        painel = CodecPainel.decodificar(ler),
        cluster = decodificarCluster(ler(CLUSTER)),
        perfilFaixasId = ler(PERFIL_FAIXAS)?.trim()?.takeIf { it.isNotEmpty() },
        perfilCambioId = ler(PERFIL_CAMBIO)?.trim()?.takeIf { it.isNotEmpty() },
        camada = CamadaPreferida.deCodigo(ler(CAMADA)),
        gatilhoNome = ler(GATILHO_NOME)?.trim()?.takeIf { it.isNotEmpty() },
        gatilhoEndereco = ler(GATILHO_ENDERECO)?.trim()?.takeIf { it.isNotEmpty() },
        favoritosGraficos = ler(FAVORITOS_GRAFICOS).orEmpty().split(SEPARADOR).map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        recorde = RecordeDesempenho(tempo(ler(RECORDE_060)), tempo(ler(RECORDE_0100))),
    )

    /** Sempre [ClusterPainel.BOLINHAS] posições; nomes desconhecidos viram o padrão. */
    fun decodificarCluster(texto: String?): List<SensorPainel?> {
        val partes = texto.orEmpty().split(SEPARADOR).map { it.trim() }
        return List(ClusterPainel.BOLINHAS) { i -> partes.getOrNull(i)?.takeIf { it != BOLINHA_PADRAO }?.let(SensorPainel::deCodigo) }
    }

    /** Tempo de recorde válido (positivo e finito); qualquer outra coisa = sem recorde. */
    private fun tempo(texto: String?): Double? = texto?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
}
