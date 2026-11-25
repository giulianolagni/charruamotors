package com.charruamotors.obd

/** Situação de um monitor de prontidão do OBD2 (`0101`). */
enum class EstadoMonitor(val texto: String) {
    /** Suportado e com o teste concluído desde a última limpeza dos códigos. */
    PRONTO("pronto"),

    /** Suportado, mas o teste ainda não rodou (comum logo depois de apagar as falhas ou desligar a bateria). */
    PENDENTE("pendente"),

    /** O veículo não tem este monitor. */
    NAO_APLICAVEL("não aplicável"),
}

/** Um monitor de prontidão: nome em português e a [EstadoMonitor] lida no `0101`. */
data class MonitorProntidao(val nome: String, val estado: EstadoMonitor, val continuo: Boolean = false)

/**
 * Resposta ao `0101` (SAE J1979): `A` bit 7 = luz de injeção (MIL) acesa, bits 0–6 = quantidade de
 * DTCs confirmados; `B` bit 3 = ignição por compressão (diesel); `B` bits 0–2 = monitores contínuos
 * suportados (falha de ignição, combustível, componentes) e bits 4–6 = os mesmos incompletos; `C` = os
 * monitores não contínuos suportados e `D` = os incompletos (catalisador, EVAP, sonda O₂... na
 * gasolina/flex; NOx, filtro de partículas, turbo... no diesel).
 */
data class StatusProntidao(
    val milAcesa: Boolean,
    val quantidadeDtcs: Int,
    val diesel: Boolean,
    val monitores: List<MonitorProntidao>,
) {
    val suportados: List<MonitorProntidao> get() = monitores.filter { it.estado != EstadoMonitor.NAO_APLICAVEL }
    val pendentes: List<MonitorProntidao> get() = monitores.filter { it.estado == EstadoMonitor.PENDENTE }
    val prontos: List<MonitorProntidao> get() = monitores.filter { it.estado == EstadoMonitor.PRONTO }

    companion object {
        private val CONTINUOS = listOf("Falha de ignição (misfire)", "Sistema de combustível", "Componentes abrangentes")

        /** Bits 0–7 do byte C/D na ignição por centelha (gasolina, etanol, flex). */
        private val NAO_CONTINUOS_OTTO = listOf(
            "Catalisador", "Catalisador aquecido", "Sistema evaporativo (EVAP)", "Ar secundário",
            "Gás do ar-condicionado", "Sonda O₂", "Aquecedor da sonda O₂", "EGR / VVT",
        )

        /** Bits 0–7 do byte C/D na ignição por compressão (diesel); null = bit reservado. */
        private val NAO_CONTINUOS_DIESEL = listOf(
            "Catalisador NMHC", "NOx / SCR", null, "Pressão do turbo",
            null, "Sensor de gases de escape", "Filtro de partículas (DPF)", "EGR / VVT",
        )

        fun decodificar(a: Int, b: Int, c: Int, d: Int): StatusProntidao {
            val diesel = b and 0x08 != 0
            fun estado(suportado: Boolean, incompleto: Boolean) = when {
                !suportado -> EstadoMonitor.NAO_APLICAVEL
                incompleto -> EstadoMonitor.PENDENTE
                else -> EstadoMonitor.PRONTO
            }
            val continuos = CONTINUOS.mapIndexed { bit, nome ->
                MonitorProntidao(nome, estado(b and (1 shl bit) != 0, b and (1 shl (bit + 4)) != 0), continuo = true)
            }
            val nomes = if (diesel) NAO_CONTINUOS_DIESEL else NAO_CONTINUOS_OTTO
            val naoContinuos = nomes.mapIndexedNotNull { bit, nome ->
                nome?.let { MonitorProntidao(it, estado(c and (1 shl bit) != 0, d and (1 shl bit) != 0)) }
            }
            return StatusProntidao(
                milAcesa = a and 0x80 != 0,
                quantidadeDtcs = a and 0x7F,
                diesel = diesel,
                monitores = continuos + naoContinuos,
            )
        }
    }
}

/**
 * Um registro do Modo 06 no CAN: [tid] (`0B` = média EWMA de falhas de ignição, `0C` = contagem do
 * ciclo atual nos MIDs `A1`–`AD`), [uasid] (unidade/escala, `24` = contagem) e o valor com seus limites.
 */
data class TesteModo06(val mid: Int, val tid: Int, val uasid: Int, val valor: Int, val minimo: Int, val maximo: Int)
