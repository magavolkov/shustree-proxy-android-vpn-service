package ru.shustree.shustreeproxy.data

import android.annotation.SuppressLint
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class ApiRequest(
    val deviceId: String,
    val locale: String,
    val country: String?,
    val application: String?,
    )

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class ProxyDetails(
    @SerialName("proxyAdress")
    val proxyAddress: String,
    @SerialName("proxyPort")
    val proxyPort: Int
)





@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class ApiResponse(
    @SerialName("shustreeStatus")
    val shustreeStatus: String?,
    @SerialName("shuAppId")
    val shuAppId: String?,
    @SerialName("currentIp")
    val currentIp: String?,
    @SerialName("humanizedBalance")
    val humanizedBalance: String?,
    @SerialName("balance")
    val balance: Long?,
    @SerialName("expirationTimeSec")
    val expirationTimeSec: Double?,
    @SerialName("machineTime")
    val machineTime: Double?,
    @SerialName("proxyConfig")
    val proxyConfig: Map<String, ProxyDetails> = emptyMap(),
    @SerialName("quicConfig")
    val quicConfig: Map<String, ProxyDetails> = emptyMap(),
    @SerialName("mask_ranges")
    val whatsappPrefixes: List<String>? = null,
    @SerialName("ru_apps")
    val ruApps: List<String>? = emptyList()
)



@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class ApiActivationRequest(
    val deviceId: String,
    val locale: String?,
    val country: String?,
    val currentShuId: String,
    val providedShuId: String
)