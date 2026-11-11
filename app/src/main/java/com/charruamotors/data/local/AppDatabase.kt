package com.charruamotors.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.charruamotors.data.local.dao.UsuarioDao
import com.charruamotors.data.local.dao.VeiculoDao
import com.charruamotors.domain.model.Usuario
import com.charruamotors.domain.model.Veiculo

/**
 * Banco de dados local do app (Room sobre SQLite).
 * Singleton: existe uma única instância durante toda a vida do processo.
 */
@Database(entities = [Usuario::class, Veiculo::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {

    abstract fun usuarioDao(): UsuarioDao

    abstract fun veiculoDao(): VeiculoDao

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
                )
                    // Em desenvolvimento, uma mudança de versão recria o banco do zero.
                    // Antes de publicar, trocar por migrações (Migration) para não perder dados.
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instancia = it }
            }
    }
}
