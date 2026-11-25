package com.charruamotors.sistema.preferencias

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import com.charruamotors.direcao.desempenho.FonteVelocidade
import com.charruamotors.sistema.unidades.ConversorUnidades
import com.charruamotors.sistema.unidades.SistemaUnidades
import com.charruamotors.sistema.unidades.UnidadeConsumo
import com.charruamotors.sistema.unidades.UnidadePressao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Tema do app (Configurações → Aparência), aplicado com `AppCompatDelegate.setDefaultNightMode`. */
enum class TemaApp(val modoNoturno: Int) {
    SISTEMA(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    CLARO(AppCompatDelegate.MODE_NIGHT_NO),
    ESCURO(AppCompatDelegate.MODE_NIGHT_YES),
    ;

    companion object {
        val PADRAO = SISTEMA

        fun deCodigo(codigo: String?): TemaApp = entries.firstOrNull { it.name.equals(codigo, ignoreCase = true) } ?: PADRAO
    }
}

/** Preferências gerais do app (fora da caixa-preta). */
data class ConfigApp(
    val tema: TemaApp = TemaApp.PADRAO,
    val unidadePressao: UnidadePressao = UnidadePressao.PADRAO,
    /** Unidades globais (métrico ou imperial) de todo o app e do HUD. */
    val sistemaUnidades: SistemaUnidades = SistemaUnidades.PADRAO,
    /** O Assistente de Primeira Abertura já foi concluído (ou pulado até o fim)? */
    val assistenteConcluido: Boolean = false,
    /** Velocidade pelo GPS do celular (v2.0): sensor no Painel/Cluster/HUD e coluna nos CSVs. */
    val velocidadeGps: Boolean = false,
    /** Fonte da velocidade do cronômetro 0–60/0–100; GPS só vale com [velocidadeGps] ligada. */
    val fonteCronometro: FonteVelocidade = FonteVelocidade.PADRAO,
    /** v2.3.0: consumo em km/L ou L/100km no métrico (Painel, HUD e relatórios). */
    val unidadeConsumo: UnidadeConsumo = UnidadeConsumo.PADRAO,
) {
    /** Fonte que o cronômetro realmente usa: sem a velocidade GPS ligada, sempre a ECU. */
    val fonteCronometroEfetiva: FonteVelocidade
        get() = if (velocidadeGps) fonteCronometro else FonteVelocidade.OBD

    /** Conversor de exibição: no imperial a pressão é sempre psi; no métrico, a [unidadePressao]. */
    val conversor: ConversorUnidades get() = ConversorUnidades(sistemaUnidades, unidadePressao, unidadeConsumo)
}

/**
 * Armazenamento chave-valor mínimo: no app, SharedPreferences ([deSharedPreferences]); nos testes
 * JVM, um mapa em memória (o android.jar dos testes só tem stubs).
 */
interface ArmazenamentoChaveValor {
    fun lerTexto(chave: String): String?
    fun lerBooleano(chave: String, padrao: Boolean): Boolean
    fun gravar(valores: Map<String, Any?>)

    companion object {
        fun deSharedPreferences(prefs: SharedPreferences) = object : ArmazenamentoChaveValor {
            override fun lerTexto(chave: String): String? = prefs.getString(chave, null)
            override fun lerBooleano(chave: String, padrao: Boolean): Boolean = prefs.getBoolean(chave, padrao)
            override fun gravar(valores: Map<String, Any?>) = prefs.edit {
                valores.forEach { (chave, valor) ->
                    when (valor) {
                        null -> remove(chave)
                        is Boolean -> putBoolean(chave, valor)
                        else -> putString(chave, valor.toString())
                    }
                }
            }
        }
    }
}

/**
 * [ConfigApp] persistida: tema, unidade de pressão, unidades globais e a flag do assistente. Fica num arquivo
 * próprio (`app`), separado da sessão de login — o logout não reabre o assistente nem muda o tema.
 */
class PreferenciasApp(private val armazenamento: ArmazenamentoChaveValor) {

    constructor(context: Context) : this(
        ArmazenamentoChaveValor.deSharedPreferences(
            context.applicationContext.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE),
        ),
    )

    private val _config = MutableStateFlow(ler())
    val config: StateFlow<ConfigApp> = _config.asStateFlow()

    val tema: TemaApp get() = _config.value.tema
    val unidadePressao: UnidadePressao get() = _config.value.unidadePressao
    val sistemaUnidades: SistemaUnidades get() = _config.value.sistemaUnidades
    val unidadeConsumo: UnidadeConsumo get() = _config.value.unidadeConsumo

    /** O assistente deve abrir sozinho (primeira abertura após o login)? */
    val deveMostrarAssistente: Boolean get() = !_config.value.assistenteConcluido

    fun definirTema(tema: TemaApp) = atualizar { it.copy(tema = tema) }

    fun definirUnidadePressao(unidade: UnidadePressao) = atualizar { it.copy(unidadePressao = unidade) }

    fun definirSistemaUnidades(sistema: SistemaUnidades) = atualizar { it.copy(sistemaUnidades = sistema) }

    fun definirUnidadeConsumo(unidade: UnidadeConsumo) = atualizar { it.copy(unidadeConsumo = unidade) }

    fun marcarAssistenteConcluido() = atualizar { it.copy(assistenteConcluido = true) }

    fun definirVelocidadeGps(ativa: Boolean) = atualizar { it.copy(velocidadeGps = ativa) }

    fun definirFonteCronometro(fonte: FonteVelocidade) = atualizar { it.copy(fonteCronometro = fonte) }

    /** "Refazer Assistente Inicial" nas Configurações. */
    fun reiniciarAssistente() = atualizar { it.copy(assistenteConcluido = false) }

    /** Restauração do backup: tema e unidades vêm do arquivo; a flag do assistente fica como está. */
    fun restaurar(
        tema: TemaApp,
        unidade: UnidadePressao,
        sistema: SistemaUnidades = SistemaUnidades.PADRAO,
        consumo: UnidadeConsumo = UnidadeConsumo.PADRAO,
    ) = atualizar { it.copy(tema = tema, unidadePressao = unidade, sistemaUnidades = sistema, unidadeConsumo = consumo) }

    @Synchronized
    private fun atualizar(transformar: (ConfigApp) -> ConfigApp) {
        val nova = transformar(_config.value)
        if (nova == _config.value) return
        armazenamento.gravar(
            mapOf(
                TEMA to nova.tema.name,
                UNIDADE_PRESSAO to nova.unidadePressao.name,
                SISTEMA_UNIDADES to nova.sistemaUnidades.name,
                ASSISTENTE_CONCLUIDO to nova.assistenteConcluido,
                VELOCIDADE_GPS to nova.velocidadeGps,
                FONTE_CRONOMETRO to nova.fonteCronometro.name,
                UNIDADE_CONSUMO to nova.unidadeConsumo.name,
            ),
        )
        _config.value = nova
    }

    private fun ler() = ConfigApp(
        tema = TemaApp.deCodigo(armazenamento.lerTexto(TEMA)),
        unidadePressao = UnidadePressao.deCodigo(armazenamento.lerTexto(UNIDADE_PRESSAO)),
        sistemaUnidades = SistemaUnidades.deCodigo(armazenamento.lerTexto(SISTEMA_UNIDADES)),
        assistenteConcluido = armazenamento.lerBooleano(ASSISTENTE_CONCLUIDO, false),
        velocidadeGps = armazenamento.lerBooleano(VELOCIDADE_GPS, false),
        fonteCronometro = FonteVelocidade.deCodigo(armazenamento.lerTexto(FONTE_CRONOMETRO)),
        unidadeConsumo = UnidadeConsumo.deCodigo(armazenamento.lerTexto(UNIDADE_CONSUMO)),
    )

    companion object {
        const val ARQUIVO = "app"
        const val TEMA = "tema"
        const val UNIDADE_PRESSAO = "unidade_pressao"
        const val SISTEMA_UNIDADES = "sistema_unidades"
        const val ASSISTENTE_CONCLUIDO = "assistente_concluido"
        const val VELOCIDADE_GPS = "velocidade_gps"
        const val FONTE_CRONOMETRO = "fonte_cronometro"
        const val UNIDADE_CONSUMO = "unidade_consumo"
    }
}
