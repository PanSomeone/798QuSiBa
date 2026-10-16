package com.killhuishenghuo.ui.hotwater

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.killhuishenghuo.data.local.PreferencesStore
import com.killhuishenghuo.data.repository.HuiShengHuoRepository

/**
 * Wires [HotWaterViewModel] by hand.
 *
 * A DI framework would be more machinery than this project has dependencies;
 * see [com.killhuishenghuo.core.net.NetworkModule] for the rest of the graph.
 */
class HotWaterViewModelFactory(
    private val repository: HuiShengHuoRepository,
    private val preferences: PreferencesStore,
    private val accountName: String?,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(HotWaterViewModel::class.java)) {
            "HotWaterViewModelFactory cannot create ${modelClass.name}"
        }
        return HotWaterViewModel(
            repository = repository,
            preferences = preferences,
            accountName = accountName,
        ) as T
    }
}
