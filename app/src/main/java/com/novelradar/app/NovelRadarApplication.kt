package com.novelradar.app

import android.app.Application
import com.novelradar.app.app.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface SourceBootstrapState {
    data object Installing : SourceBootstrapState
    data object Ready : SourceBootstrapState
    data object Failed : SourceBootstrapState
}

class NovelRadarApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableBootstrapState = MutableStateFlow<SourceBootstrapState>(SourceBootstrapState.Installing)
    val bootstrapState: StateFlow<SourceBootstrapState> = mutableBootstrapState

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            try {
                container.builtInInstaller.run()
                mutableBootstrapState.value = SourceBootstrapState.Ready
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableBootstrapState.value = SourceBootstrapState.Failed
            }
        }
    }
}
