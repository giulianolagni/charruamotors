package com.charruamotors.sistema

import com.charruamotors.obd.InfoConexao
import com.charruamotors.obd.ModoComunicacaoEcu

/**
 * Barra compacta de status do Painel conectado: `Conectado · OBDII · 7E0`. Lógica pura.
 */
object StatusConexaoPainel {

    private val ENTRE_PARENTESES = Regex("""\(([^)]+)\)""")
    private val PARENTESES_NO_FIM = Regex("""\s*\([^)]*\)\s*$""")

    /**
     * `Conectado · <scanner> · <ECU>`, sem as partes desconhecidas. O detalhe entre parênteses do
     * nome do scanner (`OBDII Simulado (ELM327)`) sai, para a ECU caber na barra.
     */
    fun resumo(scanner: String?, info: InfoConexao): String {
        val nome = scanner?.replace(PARENTESES_NO_FIM, "")?.trim()?.takeIf { it.isNotEmpty() }
        return listOfNotNull("Conectado", nome, ecu(info)).joinToString(" · ")
    }

    /**
     * Quem responde: o cabeçalho físico (`7E0`) nos modos 21/22; no OBD2 padrão, o protocolo
     * curto (`CAN 11/500`, o que vem entre parênteses no `ATDP`); senão o nome da camada.
     */
    fun ecu(info: InfoConexao): String? {
        if (info.modo != ModoComunicacaoEcu.OBD2_PADRAO) {
            return info.espelhos.firstNotNullOfOrNull { it.estendida?.cabecalho } ?: info.nomeModo
        }
        val protocolo = info.protocolo?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return ENTRE_PARENTESES.find(protocolo)?.groupValues?.get(1)?.trim() ?: protocolo.removePrefix("AUTO,").trim()
    }
}
