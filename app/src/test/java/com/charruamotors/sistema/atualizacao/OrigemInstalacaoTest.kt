package com.charruamotors.sistema.atualizacao

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrigemInstalacaoTest {
    @Test fun `instalado pela Play Store nao verifica`() =
        assertFalse(OrigemInstalacao.deveVerificarAtualizacao("com.android.vending"))

    @Test fun `apk manual ou adb sem instalador verifica`() =
        assertTrue(OrigemInstalacao.deveVerificarAtualizacao(null))

    @Test fun `outras lojas e instalador de pacotes verificam`() {
        assertTrue(OrigemInstalacao.deveVerificarAtualizacao("com.android.packageinstaller"))
        assertTrue(OrigemInstalacao.deveVerificarAtualizacao("com.sec.android.app.samsungapps"))
    }
}
