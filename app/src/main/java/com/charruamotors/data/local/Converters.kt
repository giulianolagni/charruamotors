package com.charruamotors.data.local

import androidx.room.TypeConverter
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Conversores do Room para tipos que o SQLite não conhece.
 * Datas viram dias desde 1970 (Long) e data/hora vira segundos em UTC (Long).
 * Enums não precisam de conversor: o Room grava o nome da constante.
 */
class Converters {

    @TypeConverter
    fun dataParaLong(data: LocalDate?): Long? = data?.toEpochDay()

    @TypeConverter
    fun longParaData(valor: Long?): LocalDate? = valor?.let(LocalDate::ofEpochDay)

    @TypeConverter
    fun dataHoraParaLong(dataHora: LocalDateTime?): Long? = dataHora?.toEpochSecond(ZoneOffset.UTC)

    @TypeConverter
    fun longParaDataHora(valor: Long?): LocalDateTime? =
        valor?.let { LocalDateTime.ofEpochSecond(it, 0, ZoneOffset.UTC) }
}
