package com.charruamotors.sistema.backup

import android.content.ContentResolver
import android.net.Uri
import com.charruamotors.carros.GerenciadorPerfisCarro
import com.charruamotors.data.local.dao.BackupDao
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
    /** v2.3.0: perfis isolados no arquivo (0 num backup v1) e o formato. */
    val perfis: Int = 0,
    val versaoFormato: Int = BackupJson.VERSAO_FORMATO,
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
            perfis = d.perfisCarros.size,
            versaoFormato = d.versaoFormato,
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
    private val perfisCarro: GerenciadorPerfisCarro,
    private val versaoApp: String,
    /** Depois de gravar os perfis: o app relê o Painel/HUD do carro ativo. */
    private val aoRestaurarPerfis: () -> Unit = {},
) {

    suspend fun montar(): DadosBackup {
        val veiculos = dao.veiculos()
        return DadosBackup(
            geradoEm = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
            versaoApp = versaoApp,
            veiculos = veiculos,
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
                unidadeConsumo = preferenciasApp.unidadeConsumo,
            ),
            // v2.3.0: o perfil isolado de cada veículo (Painel/HUD, Cluster, perfil técnico, recordes).
            perfisCarros = perfisCarro.lerTodos(veiculos.map { it.id }),
            veiculoAtivoId = perfisCarro.veiculoAtivo.value,
        )
    }

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
            val entrada = runCatching { resolver.openInputStream(origem) }.getOrNull()
                ?: throw BackupInvalidoException("Não foi possível abrir o arquivo")
            entrada.use { e ->
                // Lê no máximo o teto + 1 byte: um arquivo gigante (vídeo, APK) é recusado sem estourar a memória.
                val bytes = e.readNBytesCompat(BackupJson.TAMANHO_MAXIMO_BYTES + 1)
                if (bytes.size > BackupJson.TAMANHO_MAXIMO_BYTES) throw BackupInvalidoException("Arquivo grande demais para ser um backup do app")
                String(bytes, Charsets.UTF_8)
            }
        }
        return withContext(Dispatchers.Default) { BackupJson.desserializar(texto) }
    }

    /**
     * Aplica o backup já confirmado: [ModoRestauracao.SUBSTITUIR] troca banco, perfis e preferências;
     * [ModoRestauracao.MESCLAR] só acrescenta os veículos novos (com perfis e históricos), mantendo o resto.
     * Devolve quantos veículos ficaram no app.
     */
    suspend fun restaurar(dados: DadosBackup, modo: ModoRestauracao = ModoRestauracao.SUBSTITUIR): Int {
        if (modo == ModoRestauracao.MESCLAR) {
            val mesclado = MesclaBackup.mesclar(montar(), dados)
            gravarBanco(mesclado)
            perfisCarro.substituirTodos(mesclado.perfisCarros, mesclado.veiculoAtivoId)
            aoRestaurarPerfis()
            return mesclado.veiculos.size
        }
        val completo = MesclaBackup.perfisDe(dados)
        gravarBanco(completo)
        perfisCarro.substituirTodos(completo.perfisCarros, completo.veiculoAtivoId)
        preferenciasCaixaPreta.restaurar(completo.caixaPretaRestauravel())
        val p = completo.preferencias
        preferenciasApp.restaurar(p.tema, p.unidadePressao, p.sistemaUnidades, p.unidadeConsumo)
        aoRestaurarPerfis()
        return completo.veiculos.size
    }

    /** Quantos veículos do arquivo entrariam numa mesclagem (para a confirmação). */
    suspend fun veiculosNovosNaMescla(dados: DadosBackup): Int = MesclaBackup.veiculosNovos(montar(), dados).size

    private suspend fun gravarBanco(dados: DadosBackup) {
        dao.substituirTudo(
            veiculos = dados.veiculos,
            manutencoes = dados.manutencoes,
            pecas = dados.pecas,
            abastecimentos = dados.abastecimentos,
            leituras = dados.leituras,
            codigos = dados.codigosFalha,
        )
    }

    companion object {
        const val MIME_JSON = "application/json"

        /** `InputStream.readNBytes` só existe no Android 13+ (API 33); minSdk 26. */
        private fun java.io.InputStream.readNBytesCompat(limite: Long): ByteArray {
            val saida = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (total < limite) {
                val lidos = read(buffer, 0, minOf(buffer.size.toLong(), limite - total).toInt())
                if (lidos < 0) break
                saida.write(buffer, 0, lidos)
                total += lidos
            }
            return saida.toByteArray()
        }

        /** `charrua-obd-pro-backup-2026-11-23.json`. */
        fun nomeSugerido(hoje: java.time.LocalDate = java.time.LocalDate.now()) = "charrua-obd-pro-backup-$hoje.json"
    }
}
