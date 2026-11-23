package com.charruamotors.sistema.preferencias

import androidx.appcompat.app.AppCompatDelegate
import com.charruamotors.sistema.unidades.UnidadePressao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Persistência do Assistente Inicial, do tema e da unidade de pressão. */
class PreferenciasAppTest {

    /** SharedPreferences em memória (sobrevive entre instâncias, como o arquivo real). */
    private class ArmazenamentoMemoria : ArmazenamentoChaveValor {
        val valores = mutableMapOf<String, Any?>()
        var gravacoes = 0
        override fun lerTexto(chave: String): String? = valores[chave] as? String
        override fun lerBooleano(chave: String, padrao: Boolean): Boolean = valores[chave] as? Boolean ?: padrao
        override fun gravar(valores: Map<String, Any?>) {
            gravacoes++
            this.valores.putAll(valores)
        }
    }

    private val armazenamento = ArmazenamentoMemoria()

    @Test
    fun `primeira abertura mostra o assistente com tema do sistema e kPa`() {
        val prefs = PreferenciasApp(armazenamento)
        assertTrue(prefs.deveMostrarAssistente)
        assertEquals(TemaApp.SISTEMA, prefs.tema)
        assertEquals(UnidadePressao.KPA, prefs.unidadePressao)
    }

    @Test
    fun `assistente concluido nao volta na proxima abertura`() {
        PreferenciasApp(armazenamento).marcarAssistenteConcluido()
        val reaberto = PreferenciasApp(armazenamento)
        assertFalse(reaberto.deveMostrarAssistente)
    }

    @Test
    fun `refazer assistente nas configuracoes volta a mostrar`() {
        val prefs = PreferenciasApp(armazenamento)
        prefs.marcarAssistenteConcluido()
        prefs.reiniciarAssistente()
        assertTrue(prefs.deveMostrarAssistente)
        assertTrue(PreferenciasApp(armazenamento).deveMostrarAssistente)
    }

    @Test
    fun `tema e unidade persistem e o StateFlow acompanha`() {
        val prefs = PreferenciasApp(armazenamento)
        prefs.definirTema(TemaApp.ESCURO)
        prefs.definirUnidadePressao(UnidadePressao.BAR)
        assertEquals(TemaApp.ESCURO, prefs.config.value.tema)
        assertEquals(UnidadePressao.BAR, prefs.config.value.unidadePressao)

        val reaberto = PreferenciasApp(armazenamento)
        assertEquals(TemaApp.ESCURO, reaberto.tema)
        assertEquals(UnidadePressao.BAR, reaberto.unidadePressao)
    }

    @Test
    fun `mudar tema nao mexe na flag do assistente`() {
        val prefs = PreferenciasApp(armazenamento)
        prefs.marcarAssistenteConcluido()
        prefs.definirTema(TemaApp.CLARO)
        prefs.restaurar(TemaApp.ESCURO, UnidadePressao.PSI)
        assertFalse(PreferenciasApp(armazenamento).deveMostrarAssistente)
        assertEquals(TemaApp.ESCURO, PreferenciasApp(armazenamento).tema)
    }

    @Test
    fun `valor igual ao atual nao regrava`() {
        val prefs = PreferenciasApp(armazenamento)
        prefs.definirTema(TemaApp.CLARO)
        val antes = armazenamento.gravacoes
        prefs.definirTema(TemaApp.CLARO)
        assertEquals(antes, armazenamento.gravacoes)
    }

    @Test
    fun `valor corrompido no arquivo volta ao padrao`() {
        armazenamento.valores[PreferenciasApp.TEMA] = "ROXO"
        armazenamento.valores[PreferenciasApp.UNIDADE_PRESSAO] = "atm"
        val prefs = PreferenciasApp(armazenamento)
        assertEquals(TemaApp.SISTEMA, prefs.tema)
        assertEquals(UnidadePressao.KPA, prefs.unidadePressao)
    }

    @Test
    fun `cada tema aplica o modo noturno do AppCompat`() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, TemaApp.SISTEMA.modoNoturno)
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, TemaApp.CLARO.modoNoturno)
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, TemaApp.ESCURO.modoNoturno)
    }
}
