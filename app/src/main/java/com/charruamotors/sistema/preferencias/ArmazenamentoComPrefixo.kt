package com.charruamotors.sistema.preferencias

/**
 * Visão de um [ArmazenamentoChaveValor] restrita às chaves que começam com [prefixo] (v2.3.0):
 * cada veículo grava o próprio Painel/HUD em `carro.<id>.<chave>`, sem enxergar as chaves dos outros.
 */
class ArmazenamentoComPrefixo(
    private val base: ArmazenamentoChaveValor,
    val prefixo: String,
) : ArmazenamentoChaveValor {

    override fun lerTexto(chave: String): String? = base.lerTexto(prefixo + chave)

    override fun lerBooleano(chave: String, padrao: Boolean): Boolean = base.lerBooleano(prefixo + chave, padrao)

    override fun gravar(valores: Map<String, Any?>) = base.gravar(valores.mapKeys { (chave, _) -> prefixo + chave })

    override fun equals(other: Any?): Boolean = other is ArmazenamentoComPrefixo && other.base === base && other.prefixo == prefixo

    override fun hashCode(): Int = System.identityHashCode(base) * 31 + prefixo.hashCode()
}
