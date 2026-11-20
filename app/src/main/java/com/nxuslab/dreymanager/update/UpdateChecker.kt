package com.nxuslab.dreymanager.update

class UpdateChecker(
    private val repository: UpdateRepository,
    private val installedVersionCode: Long,
) {
    suspend fun check(): UpdateState = try {
        val manifest = repository.fetchManifest()
        if (manifest.versionCode > installedVersionCode) {
            UpdateState.Available(manifest)
        } else {
            UpdateState.UpToDate
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e // cancelamento não é falha de rede
    } catch (e: Exception) {
        // Falhas de rede não impedem o usuário de abrir ou usar o aplicativo; a aba Mais avisa.
        UpdateState.Unavailable
    }
}

