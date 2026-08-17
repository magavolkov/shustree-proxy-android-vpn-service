package ru.shustree.shustreeproxy

import android.content.Context
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import ru.shustree.shustreeproxy.data.ApiActivationRequest
import ru.shustree.shustreeproxy.data.ApiRequest
import ru.shustree.shustreeproxy.data.ApiResponse
import ru.shustree.shustreeproxy.data.DeviceIdManager
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.util.Locale
import java.util.concurrent.CancellationException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

/**
 * Repository for fetching VPN info data using native HttpsURLConnection.
 * Decoupled from Ktor/OkHttp connection pools to ensure instant bypass via socketProtector.
 */
class VpnInfoRepository(private val context: Context) {

    val deviceId: String by lazy {
        DeviceIdManager.getOrCreateDeviceId(context)
    }
    val userCountry: String? by lazy {
        detectUserCountry()
    }
    val systemLocale: String = Locale.getDefault().toLanguageTag()

    var socketProtector: ((Socket) -> Boolean)? = null

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Защищает создаваемый сокет вызовом VpnService.protect(socket), 
     * направляя его в обход VPN-туннеля.
     */
    private fun protectSocket(socket: Socket): Socket {
        val success = socketProtector?.invoke(socket)
        if (success != true) {
            Log.w("VpnInfoRepository", "Socket WAS NOT protected! protector is null or failed.")
        }
        try {
            socket.tcpNoDelay = true
        } catch (e: Exception) {
            Log.w("VpnInfoRepository", "Failed to set tcpNoDelay", e)
        }
        return socket
    }

    /**
     * Создает SSLSocketFactory, гарантирующий защиту (protect) для HTTPS-сокетов.
     */
    private fun createProtectedSSLSocketFactory(): SSLSocketFactory {
        val defaultFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
        return object : SSLSocketFactory() {
            override fun getDefaultCipherSuites(): Array<String> = defaultFactory.defaultCipherSuites
            override fun getSupportedCipherSuites(): Array<String> = defaultFactory.supportedCipherSuites

            override fun createSocket(): Socket = protectSocket(defaultFactory.createSocket())

            override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket {
                // Если базовый сокет уже создан, защищаем его перед оборачиванием в SSL
                s?.let { protectSocket(it) }
                return defaultFactory.createSocket(s, host, port, autoClose)
            }

            override fun createSocket(host: String?, port: Int): Socket =
                protectSocket(defaultFactory.createSocket(host, port))

            override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
                protectSocket(defaultFactory.createSocket(host, port, localHost, localPort))

            override fun createSocket(host: InetAddress?, port: Int): Socket =
                protectSocket(defaultFactory.createSocket(host, port))

            override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
                protectSocket(defaultFactory.createSocket(address, port, localAddress, localPort))
        }
    }

    suspend fun fetchApiData(): Result<ApiResponse> = withContext(Dispatchers.IO) {
        val maxRetries = 17
        val currentDelay = 762L

        for (attempt in 1..maxRetries) {
            var connection: HttpsURLConnection? = null
            try {
                Log.d("VpnInfoRepository", "[Attempt $attempt/$maxRetries] Fetching API data...")

                val url = URL("https://shustree.ru:17762/shustreeappapi/")
                connection = (url.openConnection() as HttpsURLConnection).apply {
                    sslSocketFactory = createProtectedSSLSocketFactory()
                    requestMethod = "POST"
                    connectTimeout = 5000
                    readTimeout = 10000
                    doOutput = true
                    doInput = true

                    // Важно: закрываем сокет сразу после ответа, не сохраняя его в застрявший пул
                    setRequestProperty("Connection", "close")
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }

                val requestBody = json.encodeToString(
                    ApiRequest(
                        deviceId = deviceId,
                        locale = systemLocale,
                        country = userCountry,
                        application = "android140"
                    )
                )

                OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                    writer.write(requestBody)
                    writer.flush()
                }

                val responseCode = connection.responseCode
                if (responseCode == HttpsURLConnection.HTTP_OK) {
                    val responseText = InputStreamReader(connection.inputStream, "UTF-8").use { it.readText() }
                    val response = json.decodeFromString<ApiResponse>(responseText)

                    Log.d("VpnInfoRepository", "API data fetched successfully: $response")
                    return@withContext Result.success(response)
                } else {
                    Log.w("VpnInfoRepository", "HTTP Error $responseCode on attempt $attempt")
                }

            } catch (e: Exception) {
                if (e is CancellationException) {
                    Log.w("VpnInfoRepository", "API call was cancelled.")
                    throw e
                }
                Log.e("VpnInfoRepository", "API call failed on attempt $attempt", e)
            } finally {
                connection?.disconnect()
            }

            if (attempt < maxRetries) {
                delay(currentDelay)
            }
        }

        Result.failure(Exception("Failed to fetch API data after $maxRetries attempts."))
    }

    suspend fun activatePaidId(request: ApiActivationRequest): Result<Boolean> = withContext(Dispatchers.IO) {
        val maxRetries = 17
        val retryDelay = 762L

        for (attempt in 1..maxRetries) {
            var connection: HttpsURLConnection? = null
            try {
                Log.d("VpnInfoRepository", "[Attempt $attempt/$maxRetries] Activating ID...")

                val url = URL("https://shustree.ru:17762/shustreeappapi/app_recharge")
                connection = (url.openConnection() as HttpsURLConnection).apply {
                    sslSocketFactory = createProtectedSSLSocketFactory()
                    requestMethod = "POST"
                    connectTimeout = 5000
                    readTimeout = 10000
                    doOutput = true
                    doInput = true

                    setRequestProperty("Connection", "close")
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }

                val requestBody = json.encodeToString(request)

                OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                    writer.write(requestBody)
                    writer.flush()
                }

                val responseCode = connection.responseCode
                if (responseCode == HttpsURLConnection.HTTP_OK) {
                    val responseText = InputStreamReader(connection.inputStream, "UTF-8").use { it.readText() }
                    val jsonResponse = json.decodeFromString<JsonObject>(responseText)
                    val activationStatus = jsonResponse["activationStatus"]?.jsonPrimitive?.booleanOrNull ?: false

                    return@withContext Result.success(activationStatus)
                } else {
                    Log.w("VpnInfoRepository", "Activation attempt $attempt failed with HTTP status: $responseCode")
                }

            } catch (e: Exception) {
                if (e is CancellationException) {
                    Log.w("VpnInfoRepository", "Activation call was cancelled.")
                    throw e
                }
                Log.e("VpnInfoRepository", "Activation attempt $attempt failed with exception.", e)
            } finally {
                connection?.disconnect()
            }

            if (attempt < maxRetries) {
                delay(retryDelay)
            }
        }

        Result.failure(Exception("Failed to activate after $maxRetries attempts."))
    }

    fun detectUserCountry(): String? {
        try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val simCountry = telephonyManager.simCountryIso
            if (!simCountry.isNullOrEmpty() && simCountry.length == 2) {
                return simCountry.uppercase(Locale.ROOT)
            }
            val localeCountry = Locale.getDefault().country
            if (!localeCountry.isNullOrEmpty() && localeCountry.length == 2) {
                return localeCountry.uppercase(Locale.ROOT)
            }
        } catch (e: Exception) {
            Log.e("VpnInfoRepository", "Could not determine user country.", e)
        }
        return null
    }
}

