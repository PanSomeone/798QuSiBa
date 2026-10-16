package com.killhuishenghuo.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.killhuishenghuo.data.repository.AuthRepository

/** Wires [AuthViewModel] by hand, like the rest of this project's graph. */
class AuthViewModelFactory(
    private val repository: AuthRepository,
    /**
     * Who the session says is signed in, at the moment the screen is opened.
     *
     * Baked into the factory rather than read by the ViewModel so that the one
     * source of truth for identity while settings is open is the ViewModel's own
     * state — see the note on [AuthViewModel].
     */
    private val initialAccountName: String = "",
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(AuthViewModel::class.java)) {
            "AuthViewModelFactory cannot create ${modelClass.name}"
        }
        return AuthViewModel(
            repository = repository,
            initialAccountName = initialAccountName,
        ) as T
    }
}
