package com.charruamotors.ui.carros

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.R
import com.charruamotors.carros.CamadaPreferida
import com.charruamotors.carros.PerfilCarro
import com.charruamotors.domain.faixas.PresetVeiculo
import com.charruamotors.domain.model.Veiculo
import com.charruamotors.domain.perfis.CarregadorPerfis
import com.charruamotors.obd.DispositivoObd
import com.charruamotors.obd.ModoObd
import com.charruamotors.ui.diagnostico.DiagnosticoActivity
import com.charruamotors.ui.util.PermissoesBluetooth
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/**
 * Peças de UI dos perfis isolados (v2.3.0): a tag do seletor do topo, o editor do perfil técnico
 * (faixas, câmbio, camada e Bluetooth do carro) e o atalho da Sessão de Mapeamento (Log Bruto).
 */
object PerfisCarroUi {

    /** `Modo 21`, `CAN OBD2`, `OBD2 Genérico`... (ver [PerfilCarro.tag]). */
    fun tag(app: CharruaMotorsApp, veiculo: Veiculo): String = runCatching {
        val perfil = app.perfisCarro.ler(veiculo.id)
        perfil.tag(perfil.resolver(veiculo))
    }.getOrDefault(PerfilCarro.TAG_OBD2)

    /**
     * "Sessão de Mapeamento (Log Bruto)" — 1 toque para o primeiro log de um carro novo: liga os
     * logs automáticos e o Log Bruto, seleciona o carro e abre o Painel dele já conectando no
     * scanner salvo (Bluetooth).
     */
    fun iniciarMapeamento(activity: AppCompatActivity, veiculo: Veiculo) {
        val app = activity.application as CharruaMotorsApp
        app.preferenciasAutoConexao.definirGravarLogs(true)
        app.preferenciasAutoConexao.definirLogCompleto(true)
        app.ativarVeiculo(veiculo.id, veiculo.descricao)
        activity.startActivity(
            DiagnosticoActivity.intent(activity, veiculo.id)
                .putExtra(DiagnosticoActivity.EXTRA_MAPEAMENTO, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
    }

    // ------------------------------------------------------------------ editor do perfil técnico

    /** Lista com as 4 escolhas do perfil técnico; cada toque abre a escolha e volta para a lista. */
    fun editarPerfilTecnico(activity: AppCompatActivity, veiculo: Veiculo) {
        val app = activity.application as CharruaMotorsApp
        val perfil = app.perfisCarro.ler(veiculo.id)
        val resolucao = perfil.resolver(veiculo)
        val faixas = when {
            perfil.obd2Generico -> activity.getString(R.string.perfil_tecnico_generico)
            perfil.perfilFaixasId == null -> activity.getString(
                R.string.perfil_tecnico_auto,
                resolucao.preset?.titulo ?: activity.getString(R.string.perfil_tecnico_generico),
            )
            else -> resolucao.preset?.titulo ?: perfil.perfilFaixasId
        }
        val cambio = perfil.perfilCambioId?.let { CarregadorPerfis.catalogo.cambios[it]?.nome ?: it }
            ?: activity.getString(
                R.string.perfil_tecnico_cambio_auto,
                perfil.cambio(resolucao)?.nome ?: activity.getString(R.string.perfil_tecnico_cambio_nenhum),
            )
        val bluetooth = perfil.gatilhoNome?.let { nome -> perfil.gatilhoEndereco?.let { "$nome ($it)" } ?: nome }
            ?: activity.getString(R.string.perfil_tecnico_bluetooth_nenhum)
        val linhas = arrayOf(
            activity.getString(R.string.perfil_tecnico_faixas, faixas),
            activity.getString(R.string.perfil_tecnico_cambio, cambio),
            activity.getString(R.string.perfil_tecnico_camada, perfil.camada.titulo),
            activity.getString(R.string.perfil_tecnico_bluetooth, bluetooth),
        )
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.perfil_tecnico_titulo, veiculo.descricao))
            .setItems(linhas) { _, i ->
                when (i) {
                    0 -> escolherFaixas(activity, veiculo, perfil)
                    1 -> escolherCambio(activity, veiculo, perfil)
                    2 -> escolherCamada(activity, veiculo, perfil)
                    else -> escolherBluetooth(activity, veiculo, perfil)
                }
            }
            .setNegativeButton(R.string.botao_fechar, null)
            .show()
    }

    private fun escolherFaixas(activity: AppCompatActivity, veiculo: Veiculo, perfil: PerfilCarro) {
        val presets = PresetVeiculo.entries
        val opcoes = listOf(
            activity.getString(R.string.perfil_tecnico_auto, PerfilCarro(veiculo.id).resolverTitulo(veiculo, activity)),
            activity.getString(R.string.perfil_tecnico_generico),
        ) + presets.map { it.titulo }
        val atual = when {
            perfil.perfilFaixasId == null -> 0
            perfil.obd2Generico -> 1
            else -> presets.indexOfFirst { it.id == perfil.perfilFaixasId }.takeIf { it >= 0 }?.plus(2) ?: 0
        }
        escolher(activity, R.string.perfil_tecnico_faixas, opcoes, atual) { i ->
            val id = when (i) {
                0 -> null
                1 -> PerfilCarro.OBD2_GENERICO
                else -> presets[i - 2].id
            }
            // O OBD2 Genérico nunca usa a camada da montadora; sair dele volta ao automático.
            val camada = when {
                id == PerfilCarro.OBD2_GENERICO -> CamadaPreferida.OBD2
                perfil.obd2Generico -> CamadaPreferida.AUTO
                else -> perfil.camada
            }
            salvar(activity, veiculo) { it.copy(perfilFaixasId = id, camada = camada) }
        }
    }

    private fun escolherCambio(activity: AppCompatActivity, veiculo: Veiculo, perfil: PerfilCarro) {
        val cambios = CarregadorPerfis.catalogo.cambios.values.sortedBy { it.nome }
        val opcoes = listOf(activity.getString(R.string.perfil_tecnico_cambio_auto, "")) + cambios.map { it.nome }
        val atual = cambios.indexOfFirst { it.id == perfil.perfilCambioId }.takeIf { it >= 0 }?.plus(1) ?: 0
        escolher(activity, R.string.perfil_tecnico_cambio, opcoes.map { it.replace(" ()", "") }, atual) { i ->
            salvar(activity, veiculo) { it.copy(perfilCambioId = if (i == 0) null else cambios[i - 1].id) }
        }
    }

    private fun escolherCamada(activity: AppCompatActivity, veiculo: Veiculo, perfil: PerfilCarro) {
        val camadas = CamadaPreferida.entries
        val opcoes = camadas.map { "${it.titulo}\n${it.descricao}" }
        escolher(activity, R.string.perfil_tecnico_camada, opcoes, camadas.indexOf(perfil.camada)) { i ->
            val camada = camadas[i]
            // Escolher uma camada de montadora tira o carro do OBD2 Genérico (volta ao automático pelo cadastro).
            salvar(activity, veiculo) {
                it.copy(camada = camada, perfilFaixasId = if (it.obd2Generico && camada != CamadaPreferida.OBD2) null else it.perfilFaixasId)
            }
        }
    }

    private fun escolherBluetooth(activity: AppCompatActivity, veiculo: Veiculo, perfil: PerfilCarro) {
        val app = activity.application as CharruaMotorsApp
        activity.lifecycleScope.launch {
            val pareados: List<DispositivoObd> = if (PermissoesBluetooth.disponivel(activity) && PermissoesBluetooth.concedidas(activity)) {
                runCatching { app.obdClientDe(ModoObd.BLUETOOTH).listarDispositivos() }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            val opcoes = pareados.map { activity.getString(R.string.caixa_gatilho_pareado, it.nome, it.endereco) } +
                activity.getString(R.string.perfil_tecnico_bluetooth_remover)
            val atual = pareados.indexOfFirst { it.endereco.equals(perfil.gatilhoEndereco, ignoreCase = true) }
            escolher(activity, R.string.perfil_tecnico_bluetooth, opcoes, atual) { i ->
                val escolhido = pareados.getOrNull(i)
                salvar(activity, veiculo) { it.copy(gatilhoNome = escolhido?.nome, gatilhoEndereco = escolhido?.endereco) }
                // Carro ativo: o gatilho da caixa-preta passa a ser este Bluetooth.
                if (escolhido != null && app.perfisCarro.veiculoAtivo.value == veiculo.id) {
                    app.preferenciasAutoConexao.definirGatilho(escolhido.nome, escolhido.endereco)
                }
            }
        }
    }

    private fun escolher(activity: AppCompatActivity, titulo: Int, opcoes: List<String>, atual: Int, aoEscolher: (Int) -> Unit) {
        val rotulo = activity.getString(titulo, "").trimEnd(' ', ':')
        MaterialAlertDialogBuilder(activity)
            .setTitle(rotulo)
            .setSingleChoiceItems(opcoes.toTypedArray(), atual) { dialogo, i ->
                dialogo.dismiss()
                aoEscolher(i)
            }
            .setNegativeButton(R.string.botao_cancelar, null)
            .show()
    }

    private fun salvar(activity: AppCompatActivity, veiculo: Veiculo, transformar: (PerfilCarro) -> PerfilCarro) {
        val app = activity.application as CharruaMotorsApp
        app.perfisCarro.atualizar(veiculo.id, transformar)
        val emSessao = app.sessaoObd.estadoSessao.value.ativa
        val texto = activity.getString(R.string.perfil_tecnico_salvo, veiculo.descricao) +
            if (emSessao) activity.getString(R.string.perfil_tecnico_sessao_ativa) else ""
        Snackbar.make(activity.findViewById(android.R.id.content), texto.trim(), Snackbar.LENGTH_LONG).show()
        // Reabre a lista para seguir ajustando.
        editarPerfilTecnico(activity, veiculo)
    }

    /** Título do perfil reconhecido pelo cadastro (o que o "Automático" usaria). */
    private fun PerfilCarro.resolverTitulo(veiculo: Veiculo, activity: AppCompatActivity): String =
        resolver(veiculo).preset?.titulo ?: activity.getString(R.string.perfil_tecnico_generico)
}
