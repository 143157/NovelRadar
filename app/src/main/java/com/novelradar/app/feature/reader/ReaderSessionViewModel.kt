package com.novelradar.app.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/** Owns reader models for one navigation session across Activity recreation. */
class ReaderSessionViewModel : ViewModel(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()

    override fun onCleared() {
        viewModelStore.clear()
    }
}
