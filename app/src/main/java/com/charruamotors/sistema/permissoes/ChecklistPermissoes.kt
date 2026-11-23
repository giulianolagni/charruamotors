package com.charruamotors.sistema.permissoes

/** Itens do Checklist de Permissões (e das etapas 1 e 4 do Assistente Inicial). */
enum class ItemPermissao(
    /** Essencial para o scanner e a caixa-preta; os demais são recursos de direção opcionais. */
    val essencial: Boolean,
) {
    BLUETOOTH(essencial = true),
    NOTIFICACOES(essencial = true),
    BATERIA_SEM_RESTRICOES(essencial = true),
    ACESSO_NOTIFICACOES(essencial = false),
    SOBREPOR_APPS(essencial = false),
    ;

    companion object {
        val ESSENCIAIS: List<ItemPermissao> = entries.filter { it.essencial }
        val DIRECAO: List<ItemPermissao> = entries.filter { !it.essencial }
    }
}

enum class SituacaoPermissao {
    /** Concedida (verde). */
    OK,

    /** Falta conceder (vermelho), com botão de ação. */
    PENDENTE,

    /** Não existe nesta versão do Android (ex.: notificações antes do 13): conta como OK. */
    NAO_APLICAVEL,
}

/**
 * Leitura crua do sistema, feita pelo `LeitorPermissoes` (Android). Os campos só valem nas
 * versões em que a permissão existe — o [ChecklistPermissoes] aplica as regras de versão.
 */
data class EstadoSistemaPermissoes(
    val sdk: Int,
    /** O aparelho tem Bluetooth (o emulador costuma não ter). */
    val bluetoothDisponivel: Boolean = true,
    /** `BLUETOOTH_CONNECT` + `BLUETOOTH_SCAN` (Android 12+); antes, concedida na instalação. */
    val bluetoothConcedido: Boolean,
    /** `POST_NOTIFICATIONS` (Android 13+). */
    val notificacoesConcedidas: Boolean,
    /** `PowerManager.isIgnoringBatteryOptimizations` ("Sem restrições" no Samsung). */
    val bateriaSemRestricoes: Boolean,
    /** Acesso a Notificações (mini-player do Spotify). */
    val acessoNotificacoes: Boolean,
    /** `Settings.canDrawOverlays` (HUD sobre o Waze). */
    val sobreporApps: Boolean,
)

data class StatusPermissao(val item: ItemPermissao, val situacao: SituacaoPermissao) {
    val ok: Boolean get() = situacao != SituacaoPermissao.PENDENTE
}

/** Regras do checklist: lógica pura, testável na JVM. */
object ChecklistPermissoes {

    /** Android 12 (S): permissões de Bluetooth em tempo de execução. */
    const val SDK_BLUETOOTH_RUNTIME = 31

    /** Android 13 (TIRAMISU): permissão de notificações. */
    const val SDK_NOTIFICACOES = 33

    fun avaliar(estado: EstadoSistemaPermissoes, itens: List<ItemPermissao> = ItemPermissao.entries): List<StatusPermissao> =
        itens.map { StatusPermissao(it, situacao(it, estado)) }

    fun situacao(item: ItemPermissao, e: EstadoSistemaPermissoes): SituacaoPermissao = when (item) {
        ItemPermissao.BLUETOOTH -> when {
            !e.bluetoothDisponivel || e.sdk < SDK_BLUETOOTH_RUNTIME -> SituacaoPermissao.NAO_APLICAVEL
            else -> ok(e.bluetoothConcedido)
        }
        ItemPermissao.NOTIFICACOES ->
            if (e.sdk < SDK_NOTIFICACOES) SituacaoPermissao.NAO_APLICAVEL else ok(e.notificacoesConcedidas)
        ItemPermissao.BATERIA_SEM_RESTRICOES -> ok(e.bateriaSemRestricoes)
        ItemPermissao.ACESSO_NOTIFICACOES -> ok(e.acessoNotificacoes)
        ItemPermissao.SOBREPOR_APPS -> ok(e.sobreporApps)
    }

    /** Quantos itens ainda estão pendentes (vermelhos). */
    fun pendentes(status: List<StatusPermissao>): Int = status.count { !it.ok }

    /** Tudo o que a caixa-preta precisa para rodar com o app fechado está liberado? */
    fun essenciaisOk(estado: EstadoSistemaPermissoes): Boolean =
        avaliar(estado, ItemPermissao.ESSENCIAIS).all { it.ok }

    private fun ok(concedido: Boolean) = if (concedido) SituacaoPermissao.OK else SituacaoPermissao.PENDENTE
}
