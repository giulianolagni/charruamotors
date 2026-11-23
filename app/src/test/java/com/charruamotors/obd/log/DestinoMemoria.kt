package com.charruamotors.obd.log

import java.io.StringWriter
import java.io.Writer

/** [DestinoLogs] em memória para os testes JVM: guarda o conteúdo e conta as sincronizações. */
class DestinoMemoria : DestinoLogs {
    val arquivos = mutableListOf<ArquivoMemoria>()

    override fun criar(nome: String, mime: String): ArquivoLogAberto = ArquivoMemoria(nome, mime).also { arquivos += it }

    fun terminadoEm(sufixo: String): ArquivoMemoria = arquivos.single { it.nome.endsWith(sufixo) }

    fun algumTerminadoEm(sufixo: String): ArquivoMemoria? = arquivos.singleOrNull { it.nome.endsWith(sufixo) }
}

class ArquivoMemoria(override val nome: String, val mime: String) : ArquivoLogAberto {
    private val buffer = StringWriter()
    override val writer: Writer = buffer

    var sincronizacoes = 0
        private set
    var fechado = false
        private set

    /** O que já estaria no disco depois da última sincronização (o resto se perderia numa queda). */
    var conteudoSincronizado = ""
        private set

    val conteudo: String get() = buffer.toString()

    override fun sincronizar() {
        check(!fechado) { "sincronizar depois de fechar" }
        sincronizacoes++
        conteudoSincronizado = buffer.toString()
    }

    override fun fechar(): ArquivoLogSalvo {
        sincronizar()
        fechado = true
        return ArquivoLogSalvo(nome, "memória", "mem://$nome", mime)
    }

    override fun descartar() {
        fechado = true
    }
}
