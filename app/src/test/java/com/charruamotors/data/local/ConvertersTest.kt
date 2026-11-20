package com.charruamotors.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ConvertersTest {

    private val conversores = Converters()

    @Test
    fun `data ida e volta preserva o valor`() {
        val data = LocalDate.of(2026, 2, 28)
        assertEquals(data, conversores.longParaData(conversores.dataParaLong(data)))
    }

    @Test
    fun `data e hora ida e volta preserva o valor`() {
        val dataHora = LocalDateTime.of(2026, 2, 28, 13, 45, 10)
        assertEquals(dataHora, conversores.longParaDataHora(conversores.dataHoraParaLong(dataHora)))
    }

    @Test
    fun `nulos continuam nulos`() {
        assertNull(conversores.dataParaLong(null))
        assertNull(conversores.longParaData(null))
        assertNull(conversores.dataHoraParaLong(null))
        assertNull(conversores.longParaDataHora(null))
    }
}
