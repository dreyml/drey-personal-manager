package com.nxuslab.dreymanager.update

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    private val manifest = UpdateManifest(
        versionCode = 2,
        versionName = "0.2.0",
        message = "Nova versão",
        downloadUrl = "https://updateappdrey.nxuslab.com/download/app.apk",
    )

    @Test
    fun `informa atualizacao quando servidor tem versao maior`() = runBlocking {
        val checker = UpdateChecker(UpdateRepository { manifest }, installedVersionCode = 1)

        assertTrue(checker.check() is UpdateState.Available)
    }

    @Test
    fun `considera atualizado quando versoes sao iguais`() = runBlocking {
        val checker = UpdateChecker(UpdateRepository { manifest }, installedVersionCode = 2)

        assertEquals(UpdateState.UpToDate, checker.check())
    }

    @Test
    fun `falha de rede nao interrompe o aplicativo`() = runBlocking {
        val checker = UpdateChecker(
            repository = UpdateRepository { error("offline") },
            installedVersionCode = 1,
        )

        assertEquals(UpdateState.Unavailable, checker.check())
    }
}

