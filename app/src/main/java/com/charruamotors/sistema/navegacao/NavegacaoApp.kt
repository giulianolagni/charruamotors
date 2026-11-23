package com.charruamotors.sistema.navegacao

/**
 * Veículo Favorito (v1.5): o veículo padrão salvo nas preferências da caixa-preta; se ele não
 * existir mais (ou nunca foi escolhido), o primeiro veículo cadastrado. Lógica pura.
 */
object VeiculoFavorito {

    /** Id do favorito efetivo entre os [ids] cadastrados (na ordem da lista); null sem veículos. */
    fun idEfetivo(ids: List<Long>, favoritoSalvo: Long): Long? =
        ids.firstOrNull { it == favoritoSalvo && it > 0 } ?: ids.firstOrNull()

    /** A estrela da lista Meus Veículos fica acesa neste? */
    fun ehFavorito(veiculoId: Long, ids: List<Long>, favoritoSalvo: Long): Boolean = idEfetivo(ids, favoritoSalvo) == veiculoId

    /** Para onde o app abre ao iniciar. */
    fun destinoInicial(logado: Boolean, ids: List<Long>, favoritoSalvo: Long): DestinoInicial {
        if (!logado) return DestinoInicial.Login
        val favorito = idEfetivo(ids, favoritoSalvo) ?: return DestinoInicial.MeusVeiculos
        return DestinoInicial.Painel(veiculoId = favorito, gravarFavorito = favorito != favoritoSalvo)
    }
}

/** Primeira tela do app. */
sealed interface DestinoInicial {
    /** Sem sessão de login. */
    data object Login : DestinoInicial

    /** Logado, mas sem nenhum veículo: lista/cadastro. */
    data object MeusVeiculos : DestinoInicial

    /**
     * Direto nas 4 abas do veículo favorito (aba Painel). [gravarFavorito] = o favorito salvo
     * estava vazio ou apontava para um veículo apagado: grava o efetivo (a caixa-preta usa o mesmo).
     */
    data class Painel(val veiculoId: Long, val gravarFavorito: Boolean) : DestinoInicial
}

/** Como uma ação do menu lateral mexe na pilha de telas. */
enum class TipoNavegacao(
    /** A nova tela fica por cima da atual: o Voltar (seta ou gesto) retorna exatamente para ela. */
    val empilha: Boolean,
    /** Encerra todas as telas abertas (só o logout). */
    val limpaPilha: Boolean,
) {
    EMPILHAR(empilha = true, limpaPilha = false),
    DIALOGO(empilha = false, limpaPilha = false),
    LIMPAR_PILHA(empilha = true, limpaPilha = true),
}

/**
 * Itens do menu lateral (3 riscos), só com ações globais do app — Logs e Oficina são abas fixas
 * da barra inferior e não se repetem aqui. A ordem é a do menu.
 */
enum class AcaoMenuLateral(val titulo: String, val navegacao: TipoNavegacao) {
    VEICULOS("Meus Veículos (Trocar / Favorito)", TipoNavegacao.EMPILHAR),
    CONFIGURACOES("Configurações", TipoNavegacao.EMPILHAR),
    CHECKLIST("Checklist de Permissões", TipoNavegacao.EMPILHAR),
    BACKUP("Backup e Restauração", TipoNavegacao.DIALOGO),
    SOBRE("Sobre o App", TipoNavegacao.DIALOGO),
    SAIR("Sair (Logout)", TipoNavegacao.LIMPAR_PILHA),
}
