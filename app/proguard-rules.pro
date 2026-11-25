# Regras do R8 (minify + shrink de recursos) do Charrua OBD Pro — v2.2.1.
#
# Contexto: o app NÃO usa reflexão própria. Os JSONs de assets/ (pids_padrao, pids_estendidos,
# cascata_conexao, dtc_catalogo e o catálogo modular de assets/perfis/) e o backup são lidos à mão
# com org.json (do próprio Android); o Room gera código (AppDatabase_Impl, *_Dao_Impl) e o View
# Binding também. As regras abaixo são blindagem: protegem o que o Android ou o Room instanciam
# por nome e o que o app persiste pelo nome (enums em SharedPreferences, banco, backup e CSV).

# ---------------------------------------------------------------- diagnóstico de falhas
# Stack traces legíveis no Play Console (o mapping.txt vai junto com o .aab).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
# Anotações e assinaturas genéricas (Room, coroutines e Kotlin metadata dependem delas).
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

# ---------------------------------------------------------------- Room
# O Room já traz regras de consumidor; estas garantem a criação do banco por nome (AppDatabase_Impl)
# e mantêm entidades, DAOs e conversores intactos para a leitura de schemas e migrações.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class com.charruamotors.data.local.AppDatabase_Impl { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep @androidx.room.Dao class * { *; }
-keep class * { @androidx.room.TypeConverter <methods>; }
-keep class com.charruamotors.data.local.Converters { *; }
-dontwarn androidx.room.paging.**

# ---------------------------------------------------------------- modelos e enums persistidos por nome
# Enums do app são gravados pelo nome (Room, SharedPreferences, backup .json, CSV, perfis JSON:
# TipoCombustivel, TipoManutencao, StatusCodigoFalha, SensorPainel, ChaveSemantica...).
# valueOf()/entries/name precisam devolver exatamente os nomes do código-fonte.
-keep enum com.charruamotors.** { *; }
# Modelos de domínio (veículos, perfis, PIDs, DTCs) montados a partir dos JSONs de assets/ e do backup:
# sem reflexão hoje, mas mantidos inteiros para um eventual parser por reflexão não quebrar em release.
-keep class com.charruamotors.domain.model.** { *; }

# ---------------------------------------------------------------- componentes Android
# Activities, Services, Receivers e Providers declarados no manifesto já são mantidos pelas regras
# geradas pelo AAPT; estas cobrem as Views customizadas referenciadas nos layouts XML.
-keep class com.charruamotors.ui.componentes.** extends android.view.View { <init>(...); }
-keepclassmembers class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
# Parcelables (o app só lê BluetoothDevice do sistema, mas qualquer Parcelable próprio precisa do CREATOR).
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ---------------------------------------------------------------- bibliotecas de terceiros
# Coroutines, Lifecycle, Material, AppCompat e Room publicam regras de consumidor nos próprios AARs.
-dontwarn kotlinx.coroutines.debug.**
-dontwarn org.jetbrains.annotations.**
-dontwarn javax.annotation.**
