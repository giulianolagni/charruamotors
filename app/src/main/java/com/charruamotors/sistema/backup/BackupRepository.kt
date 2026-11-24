package com.charruamotors.sistema.backup

import android.content.ContentResolver
import android.net.Uri
import com.charruamotors.data.local.dao.BackupDao
import com.charruamotors.direcao.painel.PreferenciasPainel
import com.charruamotors.obd.sessao.PreferenciasAutoConexao
import com.charruamotors.sistema.preferencias.PreferenciasApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Resumo mostrado na confirmação da restauração e no aviso de sucesso. */
data class ResumoBackup(
    val veiculos: Int,
    val manutencoes: Int,
    val abastecimentos: Int,
    val leituras: Int,
    val codigosFalha: Int,
    val geradoEm: String,
    val versaoApp: String?,
) {
    companion object {
        fun de(d: DadosBackup) = ResumoBackup(
            veiculos = d.veiculos.size,
            manutencoes = d.manutencoes.size,
            abastecimentos = d.abastecimentos.size,
            leituras = d.leituras.size,
            codigosFalha = d.codigosFalha.size,
            geradoEm = d.geradoEm,
            versaoApp = d.versaoApp,
        )
    }
}

/**
 * Backup e Restauração via Storage Access Framework: o usuário escolhe onde salvar
 * (`ACTION_CREATE_DOCUMENT`) ou qual arquivo abrir (`ACTION_OPEN_DOCUMENT`); aqui só se lê/escreve
 * o [Uri] recebido. Os logs `.txt`/`.csv` em Downloads não entram (já são arquivos soltos).
 */
class BackupRepository(
    private val dao: BackupDao,
    private val preferenciasCaixaPreta: PreferenciasAutoConexao,
    private val preferenciasApp: PreferenciasApp,
    private val preferenciasPainel: PreferenciasPainel,
    private val versaoApp: String,
) {

    suspend fun montar(): DadosBackup = DadosBackup(
        geradoEm = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
        versaoApp = versaoApp,
        veiculos = dao.veiculos(),
        manutencoes = dao.manutencoes(),
        pecas = dao.pecas(),
        abastecimentos = dao.abastecimentos(),
        leituras = dao.leituras(),
        codigosFalha = dao.codigosFalha(),
        preferencias = PreferenciasBackup(
            caixaPreta = preferenciasCaixaPreta.config.value,
            tema = preferenciasApp.tema,
            unidadePressao = preferenciasApp.unidadePressao,
            sistemaUnidades = preferenciasApp.sistemaUnidades,
            painel = preferenciasPainel.config.value,
        ),
    )

    /** Exporta para o documento criado pelo usuário. */
    suspend fun exportar(resolver: ContentResolver, destino: Uri): ResumoBackup {
        val dados = montar()
        val json = BackupJson.serializar(dados)
        withContext(Dispatchers.IO) {
            val saida = resolver.openOutputStream(destino, "wt") ?: error("Não foi possível abrir o arquivo de destino")
            saida.bufferedWriter(Charsets.UTF_8).use { it.write(json) }
        }
        return ResumoBackup.de(dados)
    }

    /** Lê e valida o arquivo escolhido, sem alterar nada (para a confirmação). */
    suspend fun ler(resolver: ContentResolver, origem: Uri): DadosBackup {
        val texto = withContext(Dispatchers.IO) {
            val entrada = resolver.openInputStream(origem) ?: throw BackupInvalidoException("Não foi possível abrir o arquivo")
            entrada.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        return withContext(Dispatchers.Default) { BackupJson.desserializar(texto) }
    }

    /** Substitui o banco e as preferências pelo conteúdo do backup (já confirmado pelo usuário). */
    suspend fun restaurar(dados: DadosBackup) {
        dao.substituirTudo(
            veiculos = dados.veiculos,
            manutencoes = dados.manutencoes,
            pecas = dados.pecas,
            abastecimentos = dados.abastecimentos,
            leituras = dados.leituras,
            codigos = dados.codigosFalha,
        )
        preferenciasCaixaPreta.restaurar(dados.caixaPretaRestauravel())
        val p = dados.preferencias
        preferenciasApp.restaurar(p.tema, p.unidadePressao, p.sistemaUnidades)
        p.painel?.let(preferenciasPainel::restaurar)
    }

    companion object {
        const val MIME_JSON = "application/json"

        /** `charrua-obd-pro-backup-2026-11-23.json`. */
        fun nomeSugerido(hoje: java.time.LocalDate = java.time.LocalDate.now()) = "charrua-obd-pro-backup-$hoje.json"
    }
}
