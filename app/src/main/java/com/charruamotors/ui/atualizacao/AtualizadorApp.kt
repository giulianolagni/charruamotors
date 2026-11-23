package com.charruamotors.ui.atualizacao

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.Formatter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import com.charruamotors.BuildConfig
import com.charruamotors.R
import com.charruamotors.sistema.atualizacao.InterpretadorReleaseGithub
import com.charruamotors.sistema.atualizacao.ReleaseGithub
import com.charruamotors.sistema.atualizacao.ResultadoVerificacao
import com.charruamotors.sistema.atualizacao.SessaoAtualizacao
import com.charruamotors.sistema.atualizacao.VerificadorAtualizacao
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * Atualizador pelo GitHub Releases, ligado a uma tela:
 * - [verificarAoAbrir]: na abertura da tela principal, consulta em segundo plano e só aparece se
 *   houver versão nova (sem internet, `404/401` ou já atualizado: silêncio). "Depois" vale para
 *   a sessão inteira;
 * - [verificarManualmente]: "Verificar atualizações" (Sobre o App e Configurações), com resposta
 *   sempre visível ("Você já está na última versão", ou "Abrir Releases no GitHub" se o
 *   repositório for privado);
 * - "Baixar e instalar": baixa o APK para `cacheDir/updates/` com barra de progresso, pede a
 *   permissão "Instalar apps desconhecidos" se faltar e abre o instalador nativo do Android.
 *
 * Crie como campo da Activity (registra o retorno da tela de permissão antes do `onStart`).
 */
class AtualizadorApp(private val activity: AppCompatActivity) {

    private val verificador = VerificadorAtualizacao(BuildConfig.VERSION_NAME)
    private var download: Job? = null

    /** APK baixado esperando a permissão de instalar apps desconhecidos. */
    private var apkPendente: File? = null

    private val pedirPermissaoInstalar = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val apk = apkPendente ?: return@registerForActivityResult
        if (activity.packageManager.canRequestPackageInstalls()) {
            apkPendente = null
            abrirInstalador(apk)
        } else {
            mensagem(R.string.atualizacao_permissao_negada)
        }
    }

    /** Cold start da tela principal: silencioso, só mostra o diálogo se houver versão nova. */
    fun verificarAoAbrir() {
        if (SessaoAtualizacao.adiadaNestaSessao) return
        activity.lifecycleScope.launch {
            val resultado = verificador.verificar()
            if (resultado !is ResultadoVerificacao.NovaVersao) return@launch
            activity.lifecycle.withStarted {
                if (!SessaoAtualizacao.adiadaNestaSessao && !SessaoAtualizacao.dialogoAberto) mostrarNovaVersao(resultado)
            }
        }
    }

    /** Botão "Verificar atualizações": sempre responde. */
    fun verificarManualmente() {
        val aguarde = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.atualizacao_verificar)
            .setMessage(R.string.atualizacao_verificando)
            .setCancelable(true)
            .show()
        activity.lifecycleScope.launch {
            val resultado = verificador.verificar()
            aguarde.dismiss()
            if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
            when (resultado) {
                is ResultadoVerificacao.NovaVersao -> mostrarNovaVersao(resultado)
                is ResultadoVerificacao.Atualizado -> MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.atualizacao_ultima_versao)
                    .setMessage(activity.getString(R.string.atualizacao_ultima_versao_detalhe, BuildConfig.VERSION_NAME, resultado.ultima.versaoTexto))
                    .setPositiveButton(R.string.botao_fechar, null)
                    .setNeutralButton(R.string.atualizacao_abrir_releases) { _, _ -> abrirReleases() }
                    .show()
                is ResultadoVerificacao.Indisponivel -> mostrarSemConsulta(
                    activity.getString(R.string.atualizacao_privado, resultado.codigoHttp),
                )
                ResultadoVerificacao.SemInternet -> mostrarSemConsulta(activity.getString(R.string.atualizacao_sem_internet))
                is ResultadoVerificacao.Falha -> mostrarSemConsulta(activity.getString(R.string.atualizacao_falha, resultado.mensagem))
            }
        }
    }

    private fun mostrarSemConsulta(texto: String) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.atualizacao_verificar)
            .setMessage(texto)
            .setPositiveButton(R.string.atualizacao_abrir_releases) { _, _ -> abrirReleases() }
            .setNegativeButton(R.string.botao_fechar, null)
            .show()
    }

    private fun mostrarNovaVersao(nova: ResultadoVerificacao.NovaVersao) {
        val release = nova.release
        val notas = InterpretadorReleaseGithub.resumirNotas(release.notas)
        val tamanho = release.apk?.tamanhoBytes?.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(activity, it) }
        val texto = buildString {
            append(activity.getString(R.string.atualizacao_nova_detalhe, BuildConfig.VERSION_NAME, release.versaoTexto))
            tamanho?.let { append(" · APK de $it") }
            append("\n\n")
            append(notas.ifEmpty { activity.getString(R.string.atualizacao_sem_notas) })
        }
        SessaoAtualizacao.dialogoAberto = true
        val construtor = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.atualizacao_nova_titulo, release.versaoTexto))
            .setMessage(texto)
            .setNegativeButton(R.string.atualizacao_depois) { _, _ -> SessaoAtualizacao.adiadaNestaSessao = true }
            .setOnCancelListener { SessaoAtualizacao.adiadaNestaSessao = true }
            .setOnDismissListener { SessaoAtualizacao.dialogoAberto = false }
        if (release.apk != null) {
            construtor.setPositiveButton(R.string.atualizacao_baixar_instalar) { _, _ -> baixarEInstalar(release) }
            construtor.setNeutralButton(R.string.atualizacao_ver_github) { _, _ -> abrirUrl(release.urlPagina) }
        } else {
            // Release sem APK anexado: a página dela é o caminho.
            construtor.setPositiveButton(R.string.atualizacao_abrir_releases) { _, _ -> abrirUrl(release.urlPagina) }
        }
        construtor.show()
    }

    // ------------------------------------------------------------------ download e instalação

    private fun baixarEInstalar(release: ReleaseGithub) {
        val apk = release.apk ?: return abrirUrl(release.urlPagina)
        if (download?.isActive == true) return
        val pasta = File(activity.cacheDir, PASTA_ATUALIZACOES)
        val destino = File(pasta, "CharruaOBDPro-${release.versao.curta}.apk")

        val densidade = activity.resources.displayMetrics.density
        val texto = TextView(activity).apply { text = activity.getString(R.string.atualizacao_baixando_inicio) }
        val barra = LinearProgressIndicator(activity).apply {
            isIndeterminate = true
            max = 1000
        }
        val conteudo = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = (24 * densidade).toInt()
            setPadding(p, (12 * densidade).toInt(), p, 0)
            addView(texto)
            addView(barra, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (16 * densidade).toInt()
            })
        }
        val dialogo: AlertDialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.atualizacao_baixando_titulo, release.versaoTexto))
            .setView(conteudo)
            .setCancelable(false)
            .setNegativeButton(R.string.botao_cancelar) { _, _ -> download?.cancel() }
            .show()

        download = activity.lifecycleScope.launch {
            try {
                pasta.listFiles()?.forEach { it.delete() } // APKs de atualizações anteriores
                var ultimoPermil = -1
                VerificadorAtualizacao.baixarApk(apk.url, destino) { baixados, total ->
                    val totalConhecido = if (total > 0) total else apk.tamanhoBytes
                    val permil = if (totalConhecido > 0) (baixados * 1000 / totalConhecido).toInt().coerceIn(0, 1000) else -1
                    if (permil == ultimoPermil) return@baixarApk
                    ultimoPermil = permil
                    activity.runOnUiThread {
                        if (permil >= 0) {
                            barra.isIndeterminate = false
                            barra.setProgressCompat(permil, true)
                            texto.text = activity.getString(
                                R.string.atualizacao_baixando_progresso,
                                permil / 10,
                                Formatter.formatShortFileSize(activity, baixados),
                                Formatter.formatShortFileSize(activity, totalConhecido),
                            )
                        } else {
                            texto.text = Formatter.formatShortFileSize(activity, baixados)
                        }
                    }
                }
                dialogo.dismiss()
                instalar(destino)
            } catch (e: CancellationException) {
                dialogo.dismiss()
                destino.delete()
                throw e
            } catch (e: IOException) {
                dialogo.dismiss()
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.atualizacao_erro_download)
                    .setMessage(e.message ?: "")
                    .setPositiveButton(R.string.atualizacao_tentar_novamente) { _, _ -> baixarEInstalar(release) }
                    .setNeutralButton(R.string.atualizacao_ver_github) { _, _ -> abrirUrl(release.urlPagina) }
                    .setNegativeButton(R.string.botao_fechar, null)
                    .show()
            }
        }
    }

    /** Instala o APK: pede "Instalar apps desconhecidos" se o Android ainda não liberou o app. */
    private fun instalar(apk: File) {
        if (activity.packageManager.canRequestPackageInstalls()) return abrirInstalador(apk)
        apkPendente = apk
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.atualizacao_permissao_titulo)
            .setMessage(R.string.atualizacao_permissao_mensagem)
            .setPositiveButton(R.string.atualizacao_permissao_abrir) { _, _ ->
                val tela = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                try {
                    pedirPermissaoInstalar.launch(tela)
                } catch (e: ActivityNotFoundException) {
                    mensagem(R.string.atualizacao_permissao_negada)
                }
            }
            .setNegativeButton(R.string.botao_cancelar) { _, _ -> apkPendente = null }
            .show()
    }

    /** Instalador nativo: `ACTION_VIEW` do APK pelo FileProvider (`cacheDir/updates/`). */
    private fun abrirInstalador(apk: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.arquivos", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, TIPO_APK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            mensagem(R.string.atualizacao_sem_instalador)
        }
    }

    private fun abrirReleases() = abrirUrl(InterpretadorReleaseGithub.URL_RELEASES)

    private fun abrirUrl(url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            mensagem(R.string.atualizacao_sem_navegador)
        }
    }

    private fun mensagem(resId: Int) {
        MaterialAlertDialogBuilder(activity).setMessage(resId).setPositiveButton(R.string.botao_fechar, null).show()
    }

    private companion object {
        const val PASTA_ATUALIZACOES = "updates"
        const val TIPO_APK = "application/vnd.android.package-archive"
    }
}
