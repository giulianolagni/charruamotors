package com.charruamotors.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.charruamotors.data.local.dao.AbastecimentoDao
import com.charruamotors.data.local.dao.BackupDao
import com.charruamotors.data.local.dao.CodigoFalhaDao
import com.charruamotors.data.local.dao.LeituraObdDao
import com.charruamotors.data.local.dao.ManutencaoDao
import com.charruamotors.data.local.dao.UsuarioDao
import com.charruamotors.data.local.dao.VeiculoDao
import com.charruamotors.domain.model.Abastecimento
import com.charruamotors.domain.model.CodigoFalha
import com.charruamotors.domain.model.LeituraObd
import com.charruamotors.domain.model.Manutencao
import com.charruamotors.domain.model.PecaTrocada
import com.charruamotors.domain.model.Usuario
import com.charruamotors.domain.model.Veiculo

/**
 * Banco de dados local do app (Room sobre SQLite).
 * Singleton: existe uma única instância durante toda a vida do processo.
 */
@Database(
    entities = [
        Usuario::class,
        Veiculo::class,
        Manutencao::class,
        PecaTrocada::class,
        Abastecimento::class,
        LeituraObd::class,
        CodigoFalha::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun usuarioDao(): UsuarioDao

    abstract fun veiculoDao(): VeiculoDao

    abstract fun manutencaoDao(): ManutencaoDao

    abstract fun abastecimentoDao(): AbastecimentoDao

    abstract fun leituraObdDao(): LeituraObdDao

    abstract fun codigoFalhaDao(): CodigoFalhaDao

    abstract fun backupDao(): BackupDao

    companion object {
        private const val NOME_BANCO = "charruamotors.db"

        /** v1.7: limites de alerta (água, ATF, alternador) e calibração do consumo por veículo. */
        val MIGRACAO_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE veiculos ADD COLUMN tempMaxAguaC INTEGER NOT NULL DEFAULT 98")
                db.execSQL("ALTER TABLE veiculos ADD COLUMN tempMaxAtfC INTEGER NOT NULL DEFAULT 105")
                db.execSQL("ALTER TABLE veiculos ADD COLUMN tensaoMinAlternadorV REAL NOT NULL DEFAULT 13.2")
                db.execSQL("ALTER TABLE veiculos ADD COLUMN calibracaoConsumo REAL NOT NULL DEFAULT 1.0")
            }
        }

        @Volatile
        private var instancia: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instancia ?: synchronized(this) {
                instancia ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    NOME_BANCO,
                )
                    .addMigrations(MIGRACAO_3_4)
                    // Em desenvolvimento, uma mudança de versão recria o banco do zero.
                    // Antes de publicar, trocar por migrações (Migration) para não perder dados.
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instancia = it }
            }
    }
}
