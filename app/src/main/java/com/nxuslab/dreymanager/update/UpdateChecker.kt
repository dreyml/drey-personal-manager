package com.nxuslab.dreymanager.update

class UpdateChecker(
    private val repository: UpdateRepository,
    private val installedVersionCode: Long,
) {
    suspend fun check(): UpdateState = runCatching {
        val manifest = repository.fetchManifest()
        if (manifest.versionCode > installedVersionCode) {
            UpdateState.Available(manifest)
        } else {
            UpdateState.UpToDate
        }
    }.getOrElse {
        // Falhas de rede não impedem o usuário de abrir ou usar o aplicativo.
        UpdateState.Unavailable
    }
}

