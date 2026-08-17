// VpnInfoState.kt
package ru.shustree.shustreeproxy.data





data class VpnInfoState(
    val humanizedBalance: String? = null,
    val shuAppId: String? = null,
    val currentIp: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val userCountry: String? = null,
    val systemLocale: String? = null,
    val balance: Long? = null,
    val tcpProxies: List<ProxyDetails>? = emptyList(),
    val udpProxies: List<ProxyDetails>? = emptyList(),
    val whatsappPrefixes: List<String> = emptyList(),
    val ruApps: List<String>? = emptyList()
)

// Holds the state for the activation process
data class ActivationState(
    val isActivating: Boolean = false,
    val activationSuccess: Boolean = false,
    val error: String? = null // In real app, use @StringRes Int?
)