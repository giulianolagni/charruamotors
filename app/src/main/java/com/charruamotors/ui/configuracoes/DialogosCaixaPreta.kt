package com.charruamotors.ui.configuracoes

import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.R
import com.charruamotors.domain.model.Veiculo
import com.charruamotors.obd.DispositivoObd
import com.charruamotors.obd.ModoObd
import com.charruamotors.obd.sessao.GatilhoBluetooth
import com.charruamotors.ui.util.PermissoesBluetooth
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Escolhas da caixa-preta (Bluetooth gatilho, scanner OBD2 e veículo padrão), gravadas direto
 * nas `PreferenciasAutoConexao`. Usado pelas Configurações e pela troca rápida de veículo do menu.
 */
class DialogosCaixaPreta(
    private val activity: AppCompatActivity,
    /** Chamado quando a lista de pareados não pôde ser lida por falta da permissão de Bluetooth. */
    private val pedirBluetooth: () -> Unit,
) {
    private val app get() = activity.application as CharruaMotorsApp
    private val prefs get() = app.preferenciasAutoConexao

    /** Aparelhos pareados (vazio sem permissão ou sem Bluetooth). */
    suspend fun pareados(): List<DispositivoObd> {
        if (!PermissoesBluetooth.disponivel(activity) || !PermissoesBluetooth.concedidas(activity)) return emptyList()
        return runCatching { app.obdClientDe(ModoObd.BLUETOOTH).listarDispositivos() }.getOrDefault(emptyList())
    }

    /** Padrão por nome + pareados + digitar outro nome. */
    fun escolherGatilho() {
        activity.lifecycleScope.launch {
            val permitido = PermissoesBluetooth.concedidas(activity)
            val pareados = pareados()
            val opcoes = listOf(activity.getString(R.string.caixa_gatilho_padrao, GatilhoBluetooth.NOME_PADRAO)) +
                pareados.map { activity.getString(R.string.caixa_gatilho_pareado, it.nome, it.endereco) } +
                activity.getString(R.string.caixa_gatilho_digitar)
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.caixa_gatilho_titulo)
                .setItems(opcoes.toTypedArray()) { _, i ->
                    when {
                        i == 0 -> prefs.definirGatilho(GatilhoBluetooth.NOME_PADRAO, null)
                        i <= pareados.size -> pareados[i - 1].let { prefs.definirGatilho(it.nome, it.endereco) }
                        else -> digitarGatilho()
                    }
                }
                .setNegativeButton(R.string.botao_cancelar, null)
                .show()
            // Sem a permissão, a lista de pareados não aparece: pede para a próxima vez.
            if (!permitido) pedirBluetooth()
        }
    }

    private fun digitarGatilho() {
        val campo = EditText(activity).apply {
            setSingleLine()
            setText(prefs.config.value.gatilhoNome)
            setSelection(text.length)
        }
        val margem = activity.resources.getDimensionPixelSize(R.dimen.espaco_grande)
        val moldura = FrameLayout(activity).apply {
            setPadding(margem, activity.resources.getDimensionPixelSize(R.dimen.espaco_pequeno), margem, 0)
            addView(campo)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.caixa_gatilho_digitar_titulo)
            .setMessage(R.string.caixa_gatilho_digitar_mensagem)
            .setView(moldura)
            .setNegativeButton(R.string.botao_cancelar, null)
            .setPositiveButton(R.string.caixa_salvar) { _, _ ->
                val nome = campo.text.toString()
                if (nome.isNotBlank()) prefs.definirGatilho(nome, null)
            }
            .show()
    }

    /** Lista de pareados com o vinculado marcado. */
    fun escolherScanner() {
        activity.lifecycleScope.launch {
            val pareados = pareados()
            if (pareados.isEmpty()) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.config_scanner_titulo)
                    .setMessage(R.string.config_scanner_sem_pareados)
                    .setPositiveButton(R.string.botao_fechar, null)
                    .show()
                if (!PermissoesBluetooth.concedidas(activity)) pedirBluetooth()
                return@launch
            }
            val atual = pareados.indexOfFirst { it.endereco == prefs.config.value.scanner?.endereco }
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.config_scanner_titulo)
                .setSingleChoiceItems(
                    pareados.map { activity.getString(R.string.caixa_gatilho_pareado, it.nome, it.endereco) }.toTypedArray(),
                    atual,
                ) { dialogo, i ->
                    prefs.vincularScanner(pareados[i])
                    dialogo.dismiss()
                }
                .setNegativeButton(R.string.botao_cancelar, null)
                .show()
        }
    }

    /** Veículos cadastrados com o padrão marcado. [aoEscolher] recebe o novo veículo ativo. */
    fun escolherVeiculo(aoEscolher: (Veiculo) -> Unit = {}, aoNenhum: () -> Unit = {}) {
        activity.lifecycleScope.launch {
            val veiculos = app.veiculoRepository.observarTodos().first()
            if (veiculos.isEmpty()) {
                aoNenhum()
                return@launch
            }
            val atual = veiculos.indexOfFirst { it.id == prefs.config.value.veiculoId }
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.config_veiculo_titulo)
                .setSingleChoiceItems(veiculos.map { "${it.descricao} (${it.placaFormatada})" }.toTypedArray(), atual) { dialogo, i ->
                    val escolhido = veiculos[i]
                    prefs.vincularVeiculo(escolhido.id, escolhido.descricao)
                    dialogo.dismiss()
                    aoEscolher(escolhido)
                }
                .setNegativeButton(R.string.botao_cancelar, null)
                .show()
        }
    }
}
