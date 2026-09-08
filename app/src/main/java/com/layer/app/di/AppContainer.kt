package com.layer.app.di

import android.content.Context
import com.layer.app.data.AdBlockDownloader
import com.layer.app.data.LayerDataStore
import com.layer.app.data.LayerRepository
import com.layer.app.data.SecureCredentialsStore
import com.layer.app.data.UpdateChecker
import com.layer.app.diagnostics.DiagnosticLog
import com.layer.app.vpn.AutoServerSelector
import com.layer.app.vpn.ConnectionPing
import com.layer.app.vpn.VpnController

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val credentials = SecureCredentialsStore(appContext)
    val dataStore = LayerDataStore(appContext)
    val repository = LayerRepository(appContext, dataStore, credentials)
    val diagnostics = DiagnosticLog()
    val vpnController = VpnController(appContext, repository, diagnostics)
    val connectionPing = ConnectionPing(repository, vpnController, diagnostics)
    val autoServerSelector = AutoServerSelector(
        appContext,
        repository,
        vpnController,
        diagnostics,
        connectionPing,
    )
    val updateChecker = UpdateChecker(appContext)
    val adBlockDownloader = AdBlockDownloader(appContext)
}
