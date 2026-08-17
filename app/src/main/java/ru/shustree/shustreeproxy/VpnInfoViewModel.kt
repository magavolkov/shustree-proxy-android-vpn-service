// VpnInfoViewModel.kt
package ru.shustree.shustreeproxy

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.size
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.shustree.shustreeproxy.data.ActivationState
import ru.shustree.shustreeproxy.data.ApiActivationRequest
import ru.shustree.shustreeproxy.data.DeviceIdManager
import ru.shustree.shustreeproxy.data.VpnInfoState

class VpnInfoViewModel(application: Application) : AndroidViewModel(application) {

    // The repository is created here, using the application context.
    val repository = VpnInfoRepository(application.applicationContext)

    // StateFlow to track if API data is ready
    private val _isApiDataReady = MutableStateFlow(false)
    val isApiDataReady = _isApiDataReady.asStateFlow()


    // Private mutable state that the ViewModel can edit.
    private val _vpnInfoState = MutableStateFlow(VpnInfoState())
    // Public, read-only state for the UI to observe.
    val vpnInfoState = _vpnInfoState.asStateFlow()

    private val _activationState = MutableStateFlow(ActivationState())
    val activationState = _activationState.asStateFlow()

    private val MAX_ID_LENGTH = 24

    val allowedChars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_:"


    init {
        val country = repository.detectUserCountry() // Fetch the country once.
        _vpnInfoState.update { it.copy(userCountry = country) } // And set it in the state.
    }




    /**
     * The single public function the UI calls to trigger a data fetch.
     * This is safe to call from anywhere in the UI (e.g., onResume, button click).
     * It handles loading states and error updates automatically.
     */
    fun refreshApiData() {
        // Prevent multiple simultaneous fetches if one is already running
        if (_vpnInfoState.value.isLoading) return

        viewModelScope.launch {
            // Reset API data readiness before starting network request
            _isApiDataReady.value = false

            // Set loading state to true
            _vpnInfoState.update { it.copy(isLoading = true) }

            val result = repository.fetchApiData()

            result.onSuccess { response ->
                // On success, update the state with new data
                _vpnInfoState.update {
                    it.copy(
                        isLoading = false,
                        humanizedBalance = response.humanizedBalance,
                        shuAppId = response.shuAppId,
                        currentIp = response.currentIp,
                        balance = response.balance ?: 0L,
                        tcpProxies = response.proxyConfig.values.toList(),
                        udpProxies = response.quicConfig.values.toList(),
                        whatsappPrefixes = response.whatsappPrefixes ?: emptyList(),
                        ruApps = response.ruApps ?: emptyList(),
                        error = null
                    )
                }

                // Set API data as ready
                _isApiDataReady.value = true

                Log.d("VpnInfoViewModel", "Fetched ${response.whatsappPrefixes?.size ?: 0} WhatsApp prefixes")
            }.onFailure { exception ->
                // On failure, update the state with an error message
                _vpnInfoState.update {
                    it.copy(
                        isLoading = false,
                        error = "Failed to load data: ${exception.message}"
                    )
                }

                // Ensure data is marked as not ready on failure
                _isApiDataReady.value = false
            }
        }
    }

    fun handleActivatePaidId(paidId: String) {
        if (_activationState.value.isActivating) {
            Log.d("VpnInfoViewModel", "Activation already in progress. Ignoring.")
            return
        }


        if (paidId.length > MAX_ID_LENGTH || !paidId.all { it in allowedChars }) {
            val formatErrorMessage = getApplication<Application>().getString(R.string.toast_activation_invalid_format)
            _activationState.update { it.copy(error = formatErrorMessage) }
            return
        }



        viewModelScope.launch {
            val activationMessage = getApplication<Application>().getString(R.string.toast_activating)
            _activationState.update { it.copy(isActivating = true, error = null) }


            Toast.makeText(getApplication(), activationMessage, Toast.LENGTH_SHORT).show()

            // Get required info from our clean state
            val currentState = _vpnInfoState.value
            val deviceId = DeviceIdManager.getOrCreateDeviceId(getApplication()) // This is correct to get here

            val activationRequest = ApiActivationRequest(
                deviceId = deviceId,
                locale = currentState.systemLocale,
                country = currentState.userCountry,
                currentShuId = currentState.shuAppId ?: "",
                providedShuId = paidId
            )

            val result = repository.activatePaidId(activationRequest)

            result.onSuccess { activationStatus ->
                if (activationStatus) {
                    // SUCCESS
                    _activationState.update { it.copy(isActivating = false, activationSuccess = true) }
                    // Refresh the main balance and user info after successful activation
                    refreshApiData()
                } else {
                    // API said OK, but the ID was invalid
                    val errorMessage = getApplication<Application>().getString(R.string.toast_activation_invalid_id)
                    _activationState.update { it.copy(isActivating = false, error = errorMessage) }
                }
            }.onFailure { exception ->
                // Network error or other failure
                val networkErrorMessage = getApplication<Application>().getString(R.string.toast_network_error) // Assuming you have this string
                _activationState.update { it.copy(isActivating = false, error = networkErrorMessage) }
            }
        }
    }

    /**
     * NEW: Action for the UI to call to clear the error message.
     */
    fun clearActivationError() {
        _activationState.update { it.copy(error = null) }
    }

    /**
     * NEW: Action for the UI to call after it has handled the success signal.
     */
    fun consumedActivationSuccess() {
        _activationState.update { it.copy(activationSuccess = false) }
    }
}
