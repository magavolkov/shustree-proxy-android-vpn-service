package ru.shustree.safeproxy.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SharedViewModel : ViewModel() {

    // Private mutable flow that only the ViewModel can edit
    private val _country = MutableStateFlow<String?>(null)
    // Public immutable flow for the UI to observe
    val country: StateFlow<String?> = _country

    fun updateCountry(newCountry: String?) {
        _country.value = newCountry
    }
}
