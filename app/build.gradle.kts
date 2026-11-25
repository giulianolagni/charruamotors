import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

/**
 * Assinatura do release lida de `keystore.properties` na raiz (local, fora do Git).
 * Sem o arquivo, o release é gerado sem assinatura (não instala) e o debug segue normal.
 */
val propriedadesAssinatura = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { arquivo ->
    Properties().apply { arquivo.inputStream().use { load(it) } }
}

android {
    namespace = "com.charruamotors"
    compileSdk = 37

    defaultConfig {
        // ID próprio: instala lado a lado com o app original (com.charruamotors).
        applicationId = "com.charruamotors.pro"
        minSdk = 26
        targetSdk = 37
        versionCode = 31
        versionName = "2.3.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (propriedadesAssinatura != null) {
            create("release") {
                storeFile = rootProject.file(propriedadesAssinatura.getProperty("storeFile"))
                storePassword = propriedadesAssinatura.getProperty("storePassword")
                keyAlias = propriedadesAssinatura.getProperty("keyAlias")
                keyPassword = propriedadesAssinatura.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // v2.2.1: R8 (ofuscação + remoção de código morto) e remoção de recursos não usados.
            // Regras em proguard-rules.pro; o mapping.txt sai em build/outputs/mapping/release/.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        // v2.2: os perfis de assets/perfis/ entram no classpath dos testes JVM (CarregadorPerfis sem Context).
        getByName("test").resources.srcDir("src/main/assets")
    }

    lint {
        // Textos do app em português: o corretor do lint é em inglês ("Comando" → "Commando").
        disable += "Typos"
    }

    buildFeatures {
        // View Binding: acesso tipado às views dos layouts XML (substitui findViewById).
        viewBinding = true
        // BuildConfig.VERSION_NAME: comparado com a tag da última GitHub Release pelo atualizador.
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

ksp {
    // Exporta o schema do banco Room (útil para acompanhar migrações).
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Android / UI com Views
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.viewpager2)
    implementation(libs.material)

    // Arquitetura: ViewModel e lifecycleScope
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Persistência local: Room (SQLite)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Testes
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // org.json real para os testes JVM (o android.jar dos testes unitários só tem stubs).
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}

/**
 * Publicação do APK de release: ao fim do `assembleRelease`, o APK assinado é copiado para
 * `dist/CharruaOBDPro-<versão>-release.apk` (fora do Git: vai para a GitHub Release pelo
 * `scripts/publicar-release.ps1`) e, se a pasta do Google Drive
 * existir na máquina (`G:\Meu Drive\CharruaOBD` ou `G:\My Drive\CharruaOBD`), também para lá.
 */
val nomeApkRelease = "CharruaOBDPro-${android.defaultConfig.versionName}-release.apk"
val apkRelease = layout.buildDirectory.file("outputs/apk/release/app-release.apk")
val pastasDrive = listOf("G:/Meu Drive/CharruaOBD", "G:/My Drive/CharruaOBD").map(::File)

val publicarApkDist = tasks.register<Copy>("publicarApkDist") {
    description = "Copia o APK de release assinado para dist/"
    from(apkRelease)
    into(rootProject.layout.projectDirectory.dir("dist"))
    rename(".*", nomeApkRelease)
}

val publicarApkDrive = pastasDrive.mapIndexed { i, pasta ->
    tasks.register<Copy>("publicarApkDrive$i") {
        description = "Copia o APK de release assinado para $pasta, se a pasta existir"
        val destino = pasta
        onlyIf("a pasta do Google Drive existe") { destino.isDirectory }
        from(apkRelease)
        into(pasta)
        rename(".*", nomeApkRelease)
    }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy(publicarApkDist)
    publicarApkDrive.forEach { finalizedBy(it) }
}

/** App Bundle (Google Play): `bundleRelease` gera `dist/CharruaOBDPro-<versão>-release.aab` (e cópia no Drive). */
val nomeAabRelease = "CharruaOBDPro-${android.defaultConfig.versionName}-release.aab"
val aabRelease = layout.buildDirectory.file("outputs/bundle/release/app-release.aab")

val publicarAabDist = tasks.register<Copy>("publicarAabDist") {
    description = "Copia o AAB de release assinado para dist/"
    from(aabRelease)
    into(rootProject.layout.projectDirectory.dir("dist"))
    rename(".*", nomeAabRelease)
}

val publicarAabDrive = pastasDrive.mapIndexed { i, pasta ->
    tasks.register<Copy>("publicarAabDrive$i") {
        description = "Copia o AAB de release assinado para $pasta, se a pasta existir"
        val destino = pasta
        onlyIf("a pasta do Google Drive existe") { destino.isDirectory }
        from(aabRelease)
        into(pasta)
        rename(".*", nomeAabRelease)
    }
}

tasks.matching { it.name == "bundleRelease" }.configureEach {
    finalizedBy(publicarAabDist)
    publicarAabDrive.forEach { finalizedBy(it) }
}
