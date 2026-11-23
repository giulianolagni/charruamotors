package com.charruamotors.sistema.preferencias

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import com.charruamotors.sistema.unidades.ConversorUnidades
import com.charruamotors.sistema.unidades.SistemaUnidades
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
) {
    /** Conversor de exibição: no imperial a pressão é sempre psi; no métrico, a [unidadePressao]. */
    val conversor: ConversorUnidades get() = ConversorUnidades(sistemaUnidades, unidadePressao)
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

    /** O assistente deve abrir sozinho (primeira abertura após o login)? */
    val deveMostrarAssistente: Boolean get() = !_config.value.assistenteConcluido

    fun definirTema(tema: TemaApp) = atualizar { it.copy(tema = tema) }

    fun definirUnidadePressao(unidade: UnidadePressao) = atualizar { it.copy(unidadePressao = unidade) }

    fun definirSistemaUnidades(sistema: SistemaUnidades) = atualizar { it.copy(sistemaUnidades = sistema) }

    fun marcarAssistenteConcluido() = atualizar { it.copy(assistenteConcluido = true) }

    /** "Refazer Assistente Inicial" nas Configurações. */
    fun reiniciarAssistente() = atualizar { it.copy(assistenteConcluido = false) }

    /** Restauração do backup: tema e unidade vêm do arquivo; a flag do assistente fica como está. */
    fun restaurar(tema: TemaApp, unidade: UnidadePressao) = atualizar { it.copy(tema = tema, unidadePressao = unidade) }

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
            ),
        )
        _config.value = nova
    }

    private fun ler() = ConfigApp(
        tema = TemaApp.deCodigo(armazenamento.lerTexto(TEMA)),
        unidadePressao = UnidadePressao.deCodigo(armazenamento.lerTexto(UNIDADE_PRESSAO)),
        sistemaUnidades = SistemaUnidades.deCodigo(armazenamento.lerTexto(SISTEMA_UNIDADES)),
        assistenteConcluido = armazenamento.lerBooleano(ASSISTENTE_CONCLUIDO, false),
    )

    companion object {
        const val ARQUIVO = "app"
        const val TEMA = "tema"
        const val UNIDADE_PRESSAO = "unidade_pressao"
        const val SISTEMA_UNIDADES = "sistema_unidades"
        const val ASSISTENTE_CONCLUIDO = "assistente_concluido"
    }
}
