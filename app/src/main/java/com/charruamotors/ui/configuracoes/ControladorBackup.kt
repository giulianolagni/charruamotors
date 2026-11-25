package com.charruamotors.ui.configuracoes

import android.net.Uri
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import com.charruamotors.CharruaMotorsApp
import com.charruamotors.R
import com.charruamotors.sistema.backup.BackupInvalidoException
import com.charruamotors.sistema.backup.BackupRepository
import com.charruamotors.sistema.backup.DadosBackup
import com.charruamotors.sistema.backup.ModoRestauracao
import com.charruamotors.sistema.backup.ResumoBackup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/**
 * Cópia de segurança (v2.3.0) pelo Storage Access Framework, usada nas Configurações e no menu
 * lateral: **Exportar** cria um `.json` versionado (veículos, históricos, perfis isolados de cada
 * carro com Painel/HUD/Cluster, perfil técnico e recordes, configurações globais); **Importar** lê e
 * valida o arquivo (inválido = só uma mensagem, o app segue) e pede confirmação com a escolha
 * entre **Mesclar** (só acrescenta os carros novos) e **Substituir tudo**.
 *
 * Crie como propriedade da Activity (os seletores de arquivo são registrados antes do `onCreate`).
 */
class ControladorBackup(private val activity: AppCompatActivity, private val raiz: () -> View) {

    private val app get() = activity.application as CharruaMotorsApp

    private val criarBackup = activity.registerForActivityResult(ActivityResultContracts.CreateDocument(BackupRepository.MIME_JSON)) { uri ->
        uri?.let(::exportarPara)
    }
    private val abrirBackup = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::lerDe)
    }

    /** Diálogo do menu lateral: Exportar ou Importar. */
    fun escolher() {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.backup_titulo)
            .setMessage(R.string.backup_descricao)
            .setPositiveButton(R.string.backup_exportar) { _, _ -> exportar() }
            .setNeutralButton(R.string.backup_restaurar) { _, _ -> importar() }
            .setNegativeButton(R.string.botao_cancelar, null)
            .show()
    }

    fun exportar() {
        runCatching { criarBackup.launch(BackupRepository.nomeSugerido()) }.onFailure(::mostrarErro)
    }

    fun importar() {
        runCatching { abrirBackup.launch(arrayOf(BackupRepository.MIME_JSON, "text/plain", "application/octet-stream")) }
            .onFailure(::mostrarErro)
    }

    private fun exportarPara(destino: Uri) {
        activity.lifecycleScope.launch {
            runCatching { app.backupRepository.exportar(activity.contentResolver, destino) }
                .onSuccess { r -> mensagem(activity.getString(R.string.backup_exportado, r.veiculos, r.perfis, r.manutencoes, r.abastecimentos)) }
                .onFailure { e ->
                    Log.e(TAG, "Falha ao exportar o backup", e)
                    mensagem(activity.getString(R.string.backup_erro_exportar, e.message.orEmpty()))
                }
        }
    }

    /** Lê e valida o arquivo; só pede confirmação se ele for um backup válido (nunca derruba o app). */
    private fun lerDe(origem: Uri) {
        activity.lifecycleScope.launch {
            runCatching {
                val dados = app.backupRepository.ler(activity.contentResolver, origem)
                dados to app.backupRepository.veiculosNovosNaMescla(dados)
            }
                .onSuccess { (dados, novos) -> confirmar(dados, novos) }
                .onFailure { e ->
                    Log.w(TAG, "Backup recusado", e)
                    val motivo = (e as? BackupInvalidoException)?.message ?: e.message.orEmpty()
                    mensagem(activity.getString(R.string.backup_erro_ler, motivo))
                }
        }
    }

    private fun confirmar(dados: DadosBackup, veiculosNovos: Int) {
        val r = ResumoBackup.de(dados)
        val sessaoAtiva = app.sessaoObd.estadoSessao.value.ativa
        val texto = activity.getString(
            R.string.backup_confirmar_mensagem,
            r.geradoEm,
            r.versaoApp ?: activity.getString(R.string.backup_versao_desconhecida),
            r.veiculos, r.manutencoes, r.abastecimentos, r.leituras, r.codigosFalha,
        ) + activity.getString(
            if (r.perfis > 0) R.string.backup_confirmar_perfis else R.string.backup_confirmar_formato_antigo,
            r.perfis,
        ) + activity.getString(R.string.backup_confirmar_escolha, veiculosNovos) +
            if (sessaoAtiva) activity.getString(R.string.backup_confirmar_sessao) else ""
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.backup_confirmar_titulo)
            .setMessage(texto)
            .setNegativeButton(R.string.botao_cancelar, null)
            .setNeutralButton(R.string.backup_mesclar) { _, _ -> restaurar(dados, ModoRestauracao.MESCLAR) }
            .setPositiveButton(R.string.backup_substituir) { _, _ -> restaurar(dados, ModoRestauracao.SUBSTITUIR) }
            .show()
    }

    private fun restaurar(dados: DadosBackup, modo: ModoRestauracao) {
        // A sessão grava no veículo atual: encerra antes de trocar o banco (os logs são salvos).
        if (app.sessaoObd.estadoSessao.value.ativa) app.sessaoObd.desconectar("Restauração de backup")
        activity.lifecycleScope.launch {
            runCatching { app.backupRepository.restaurar(dados, modo) }
                .onSuccess { total ->
                    mensagem(
                        activity.getString(
                            if (modo == ModoRestauracao.MESCLAR) R.string.backup_mesclado else R.string.backup_restaurado,
                            total,
                        ),
                    )
                    AppCompatDelegate.setDefaultNightMode(app.preferenciasApp.tema.modoNoturno)
                }
                .onFailure { e ->
                    Log.e(TAG, "Falha ao restaurar o backup", e)
                    mensagem(activity.getString(R.string.backup_erro_restaurar, e.message.orEmpty()))
                }
        }
    }

    private fun mostrarErro(e: Throwable) = mensagem(e.message ?: activity.getString(R.string.permissao_erro_abrir))

    private fun mensagem(texto: String) = Snackbar.make(raiz(), texto, Snackbar.LENGTH_LONG).show()

    private companion object {
        const val TAG = "ControladorBackup"
    }
}
