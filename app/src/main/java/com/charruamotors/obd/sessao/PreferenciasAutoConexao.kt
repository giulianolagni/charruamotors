package com.charruamotors.obd.sessao

import android.content.Context
import androidx.core.content.edit
import com.charruamotors.obd.DispositivoObd
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [ConfigAutoConexao] persistida em SharedPreferences: o `GatilhoBluetoothReceiver` lê com o app
 * fechado (processo recém-criado pelo broadcast), e a aba OBD2 observa [config] para o card.
 */
class PreferenciasAutoConexao(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(ler())
    val config: StateFlow<ConfigAutoConexao> = _config.asStateFlow()

    fun definirAutoConexao(ativa: Boolean) = atualizar { it.copy(autoConexaoAtiva = ativa) }

    fun definirGravarLogs(ativo: Boolean) = atualizar { it.copy(gravarLogsAutomaticos = ativo) }

    fun definirLogCompleto(ativo: Boolean) = atualizar { it.copy(logCompletoTrafego = ativo) }

    /** Bluetooth do carro: [endereco] null = reconhecer só pelo nome. */
    fun definirGatilho(nome: String, endereco: String?) = atualizar { it.copy(gatilhoNome = nome.trim(), gatilhoEndereco = endereco) }

    fun vincularScanner(dispositivo: DispositivoObd) {
        if (_config.value.scanner == dispositivo) return
        atualizar { it.copy(scanner = dispositivo) }
    }

    fun vincularVeiculo(id: Long, descricao: String?) {
        if (id <= 0) return
        val atual = _config.value
        if (atual.veiculoId == id && atual.veiculoDescricao == descricao) return
        atualizar { it.copy(veiculoId = id, veiculoDescricao = descricao) }
    }

    /**
     * Abrir um veículo no Painel: vira o favorito só se ainda não houver um (ou se for o mesmo,
     * para atualizar a descrição). Trocar de favorito é pela estrela em Meus Veículos.
     */
    fun vincularVeiculoSeLivre(id: Long, descricao: String?) {
        val atual = _config.value.veiculoId
        if (atual == 0L || atual == id) vincularVeiculo(id, descricao)
    }

    /** Restauração do backup: troca a configuração inteira pela do arquivo. */
    fun restaurar(config: ConfigAutoConexao) = atualizar { config }

    @Synchronized
    private fun atualizar(transformar: (ConfigAutoConexao) -> ConfigAutoConexao) {
        val nova = transformar(_config.value)
        prefs.edit {
            putBoolean(AUTO, nova.autoConexaoAtiva)
            putBoolean(LOGS, nova.gravarLogsAutomaticos)
            putBoolean(LOG_COMPLETO, nova.logCompletoTrafego)
            putString(GATILHO_NOME, nova.gatilhoNome)
            putString(GATILHO_ENDERECO, nova.gatilhoEndereco)
            putString(SCANNER_NOME, nova.scanner?.nome)
            putString(SCANNER_ENDERECO, nova.scanner?.endereco)
            putLong(VEICULO_ID, nova.veiculoId)
            putString(VEICULO_DESCRICAO, nova.veiculoDescricao)
        }
        _config.value = nova
    }

    private fun ler(): ConfigAutoConexao {
        val padrao = ConfigAutoConexao()
        val scannerEndereco = prefs.getString(SCANNER_ENDERECO, null)
        return ConfigAutoConexao(
            autoConexaoAtiva = prefs.getBoolean(AUTO, padrao.autoConexaoAtiva),
            gravarLogsAutomaticos = prefs.getBoolean(LOGS, padrao.gravarLogsAutomaticos),
            logCompletoTrafego = prefs.getBoolean(LOG_COMPLETO, padrao.logCompletoTrafego),
            gatilhoNome = prefs.getString(GATILHO_NOME, null)?.takeIf { it.isNotBlank() } ?: padrao.gatilhoNome,
            gatilhoEndereco = prefs.getString(GATILHO_ENDERECO, null),
            scanner = scannerEndereco?.let { DispositivoObd(prefs.getString(SCANNER_NOME, null) ?: it, it) },
            veiculoId = prefs.getLong(VEICULO_ID, 0),
            veiculoDescricao = prefs.getString(VEICULO_DESCRICAO, null),
        )
    }

    private companion object {
        const val ARQUIVO = "auto_conexao"
        const val AUTO = "auto_conexao_ativa"
        const val LOGS = "gravar_logs_automaticos"
        /** Chave nova na v2.2.5: o log bruto passou a vir desligado, mesmo para quem tinha ligado na fase de testes. */
        const val LOG_COMPLETO = "log_bruto_v225"
        const val GATILHO_NOME = "gatilho_nome"
        const val GATILHO_ENDERECO = "gatilho_endereco"
        const val SCANNER_NOME = "scanner_nome"
        const val SCANNER_ENDERECO = "scanner_endereco"
        const val VEICULO_ID = "veiculo_id"
        const val VEICULO_DESCRICAO = "veiculo_descricao"
    }
}
