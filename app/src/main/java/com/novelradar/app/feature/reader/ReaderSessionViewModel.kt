package com.novelradar.app.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/** Reused Activity owner for the active reader session across configuration changes. */
class ReaderSessionViewModel : ViewModel(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()

    override fun onCleared() {
        viewModelStore.clear()
    }
}
