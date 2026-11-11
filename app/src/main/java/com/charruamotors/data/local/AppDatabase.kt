package com.charruamotors.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.charruamotors.data.local.dao.UsuarioDao
import com.charruamotors.domain.model.Usuario

/**
 * Banco de dados local do app (Room sobre SQLite).
 * Singleton: existe uma única instância durante toda a vida do processo.
 */
@Database(entities = [Usuario::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {

    abstract fun usuarioDao(): UsuarioDao

    companion object {
        private const val NOME_BANCO = "charruamotors.db"

        @Volatile
        private var instancia: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instancia ?: synchronized(this) {
                instancia ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    NOME_BANCO,
                ).build().also { instancia = it }
            }
    }
}
