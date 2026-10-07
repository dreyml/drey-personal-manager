package com.nxuslab.dreymanager.update

data class UpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val message: String,
    val downloadUrl: String,
)

sealed interface UpdateState {
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data object Unavailable : UpdateState
    data class Available(val manifest: UpdateManifest) : UpdateState
}

