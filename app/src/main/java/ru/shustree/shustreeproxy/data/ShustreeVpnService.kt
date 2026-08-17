package ru.shustree.shustreeproxy.data
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.DeadObjectException
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.shustree.shustreeproxy.MainActivity
import ru.shustree.shustreeproxy.R
import ru.shustree.shustreeproxy.data.ip.IPP
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import android.os.Handler
import android.os.Looper
import ru.shustree.shustreeproxy.VpnInfoRepository
import android.widget.Toast
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import java.util.concurrent.atomic.AtomicLong
import java.nio.charset.StandardCharsets





class ShustreeVpnService : VpnService(), CoroutineScope {
    private var sessionTimerJob: Job? = null
    private val TAG = "ShustreeVpnService"
    private var masterJob: Job = SupervisorJob()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + masterJob



    private val lastTcpResponseTime = AtomicLong(System.currentTimeMillis())
    private val lastTcpRequestTime = AtomicLong(System.currentTimeMillis())
    private var tcpJob: Job? = null
    private lateinit var tcpWorkerDispatcher: CoroutineDispatcher
    private lateinit var tunReaderDispatcher: CoroutineDispatcher
    private lateinit var tunWriterDispatcher: CoroutineDispatcher
    private lateinit var balanceMonitorDispatcher: CoroutineDispatcher
    private lateinit var vpnInfoRepository: VpnInfoRepository
    private val isTunnelReady = AtomicBoolean(false)
    private var lastActiveNetwork: Network? = null
    data class ProxyPair(val tcp: ProxyDetails)




    lateinit var highPriorityToDeviceChannel: Channel<ByteBuffer>
    lateinit var lowPriorityToDeviceChannel: Channel<ByteBuffer>
    lateinit var deviceToNetworkChannel: Channel<ByteBuffer>
    private var vpnInterface: ParcelFileDescriptor? = null
    private val isRunning = AtomicBoolean(false)
    private val tcpWorkers = mutableListOf<TcpProxyWorker>()
    private var userCountry: String? = null
    private lateinit var deviceId: String
    private lateinit var userLocale: String



    private var sessionId: String = ""
    private var sharedClientId: String = ""
    private var availableTcpProxies: List<ProxyDetails> = emptyList()
    private val proxySelectionCounter = AtomicInteger(1) // Counter for round-robin
    private var disallowedApps: List<String> = emptyList()
    private val NOTIFICATION_ID = 1
    private val NOTIFICATION_CHANNEL_ID = "ShustreeVpnServiceChannel"
    internal val tmpSeq = ConcurrentHashMap<String, Long>()
    internal val tunAck = ConcurrentHashMap<String, Long>()
    private var tunWriterRetryDelayMs = 20L
    private val MAX_TUN_WRITER_RETRY_DELAY_MS = 2762L // Cap at 30 seconds
    private var currentNetworks: Set<Network> = emptySet()
    private var networkReadyDeferred: CompletableDeferred<Unit>? = null
    private var statusListener: VpnStatusListener? = null
    private val mainThreadHandler = Handler(Looper.getMainLooper())


    companion object {
        private var instance: ShustreeVpnService? = null
    }



    inner class LocalBinder : Binder() {
        fun getService(): ShustreeVpnService = this@ShustreeVpnService

        fun registerListener(listener: VpnStatusListener) {
            statusListener = listener
            mainThreadHandler.post {
                try {
                    statusListener?.onVpnStatusChanged(isRunning.get(), false)
                } catch (e: DeadObjectException) {
                    Log.w(TAG, "Listener was dead. Unregistering it.")
                    statusListener = null
                }
            }
        }
    }



    fun notifyTcpActivityRx() {
        lastTcpResponseTime.set(System.currentTimeMillis())
    }

    fun notifyTcpActivityTx() {
        lastTcpRequestTime.set(System.currentTimeMillis())
    }


    fun unregisterClientListener() {
        Log.d(TAG, "Unregistering client status listener.")
        this.statusListener = null
    }



    private suspend fun handleFullTransportMigration() {
        sendRstToAllConnections()
        val newProxyPair = getNextProxyPair()
        if (newProxyPair != null) {
            restartTcpTransport(newProxyPair)
        }
    }



    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            Log.i(TAG, "[NetworkCallback] Network available: ${network}")
            val connManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val capabilities = connManager.getNetworkCapabilities(network)
            if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
                val isNetworkChanged = lastActiveNetwork != null && lastActiveNetwork != network
                lastActiveNetwork = network
                currentNetworks = currentNetworks + network
                updateUnderlyingNetworks()
                if (isNetworkChanged) {
                    Log.w(TAG, "🌐 [Network Change] Switching to $network. Triggering migration...")
                    CoroutineScope(masterJob).launch {
                        handleFullTransportMigration()
                    }
                }
                networkReadyDeferred?.complete(Unit)
            }
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            Log.i(TAG, "[NetworkCallback] Network lost: ${network}")
            currentNetworks = currentNetworks - network
            if (lastActiveNetwork == network) {
                lastActiveNetwork = null
            }

            updateUnderlyingNetworks()
        }
    }




    override fun onCreate() {
        super.onCreate()
        instance = this
        // Initialize with the service context
        vpnInfoRepository = VpnInfoRepository(this)
        vpnInfoRepository.socketProtector = { socket ->
            this.protect(socket)
        }
        isRunning.set(false)
        Log.i(TAG, "VPN Service onCreate")
        deviceId = DeviceIdManager.getOrCreateDeviceId(applicationContext)
        Log.i(TAG, "Persistent Device ID loaded: $deviceId")
        userCountry = getUserCountry()
        Log.i(TAG, "User country detected as: $userCountry")
        userLocale = Locale.getDefault().toLanguageTag()
        Log.i(TAG, "User locale detected as: $userLocale")
    }






    fun commandStartVpn(
        tcpProxies: List<ProxyDetails>,
        udpProxies: List<ProxyDetails>,
        balanceInSeconds: Long,
        wPrefixes: List<String>,
        ruApps: List<String>, // NEW parameter
    ) {
        if (isRunning.compareAndSet(false, true)) {

            availableTcpProxies = tcpProxies
            disallowedApps = ruApps
            updateSessionTime(balanceInSeconds)
            notifyStatusChanged(isConnected = false, isConnecting = true)
            sessionId = UUID.randomUUID().toString().substring(0, 6)
            sharedClientId = "$deviceId-$sessionId"
            Log.i(TAG, "New session started. Full ClientID: $sharedClientId")
            masterJob = SupervisorJob()
            networkReadyDeferred = CompletableDeferred()
            tcpWorkers.clear()
            tcpWorkerDispatcher = Dispatchers.IO.limitedParallelism(1)
            tunReaderDispatcher = Dispatchers.IO.limitedParallelism(1)
            tunWriterDispatcher = Dispatchers.IO.limitedParallelism(1)
            balanceMonitorDispatcher = Dispatchers.IO.limitedParallelism(1)
            highPriorityToDeviceChannel = Channel(16384)
            lowPriorityToDeviceChannel = Channel(16384)
            deviceToNetworkChannel = Channel(16384)

            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
            Log.i(TAG, "Registered network callback.")

            CoroutineScope(masterJob).launch {
                setupAndRunVpn()
            }


        } else {
            Log.w(TAG, "[Binder] Start command received, but VPN is already running.")
        }
    }


    fun commandStopVpn() {
        Log.i(TAG, "[Binder] Received STOP command.")
        CoroutineScope(Dispatchers.IO).launch {
            stopVpn() // Your existing stopVpn() function is perfect here.
        }
    }

    fun isVpnRunning(): Boolean {
        return isRunning.get()
    }




    interface VpnStatusListener {
        fun onVpnStatusChanged(isConnected: Boolean, isConnecting: Boolean)
    }

    private val binder = LocalBinder()
    override fun onBind(intent: Intent): IBinder {
        Log.i(TAG, "Service is being bound.")
        return binder
    }



    private fun updateSessionTime(newBalanceInSeconds: Long) {
        sessionTimerJob?.cancel()
        if (newBalanceInSeconds <= 0) {
            Log.w(TAG, "Balance is zero or less. Stopping VPN.")
            mainThreadHandler.post { commandStopVpn() }
            return
        }


        Log.d(TAG, "Starting new balance countdown: $newBalanceInSeconds seconds.")
        sessionTimerJob = launch { // 'launch' is available because ShustreeVpnService implements CoroutineScope
            try {
                delay(newBalanceInSeconds * 1000)
                Log.w(TAG, "Balance expired. Stopping VPN service.")
                withContext(Dispatchers.Main) {
                    commandStopVpn()
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Balance timer was cancelled, likely due to an update.")
            }
        }
    }



    private fun notifyStatusChanged(isConnected: Boolean, isConnecting: Boolean) {
        Log.d(TAG, "Notifying listener of status change: isConnected=$isConnected, isConnecting=$isConnecting")
        CoroutineScope(Dispatchers.Main).launch {
            statusListener?.onVpnStatusChanged(isConnected, isConnecting)
        }
    }





    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand received action: ${intent?.action}. The service will remain alive.")
        vpnInfoRepository.socketProtector = { socket ->
            this.protect(socket)
        }
        return START_STICKY
    }


    private fun sendRstToAllConnections() {
        val activeKeys = tunAck.keys.toList()
        if (activeKeys.isEmpty()) return

        Log.w(TAG, "📤 [TCP Restart] Sending RST to ${activeKeys.size} active connections...")

        for (connectionKey in activeKeys) {
            try {
                val parts = connectionKey.split(":")
                if (parts.size < 3 || parts[0] != "6") continue
                val sourceIpString = parts[1]
                val bodyParts = parts[2].split("-")
                if (bodyParts.size < 2) continue
                val sourcePort = bodyParts[0].toInt()
                val destParts = bodyParts[1].split(":")
                if (destParts.size < 2) continue
                val destIpString = destParts[0]
                val destPort = destParts[1].toInt()
                val lastTunAck = 0L
                val lastTmpSeq = 0L
                val srcAddr = InetAddress.getByName(sourceIpString)
                val destAddr = InetAddress.getByName(destIpString)
                val rstPacket = PacketBuilder.build(
                    sourceAddress = destAddr,      // InetAddress
                    sourcePort = destPort,
                    destinationAddress = srcAddr,  // InetAddress
                    destinationPort = sourcePort,
                    sequenceNumber = lastTunAck,
                    acknowledgementNumber = lastTmpSeq,
                    isRST = true,
                    isACK = true
                )

                val sendResult = highPriorityToDeviceChannel.trySend(rstPacket)
                if (!sendResult.isSuccess) {
                    Log.w(TAG, "[$connectionKey] Failed to queue RST (channel full).")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error sending RST for $connectionKey: ${e.message}")
            }
        }
    }



    private suspend fun restartTcpTransport(proxyPair: ProxyPair) {
        Log.i(TAG, "🔄 [TCP Transport] Restarting Apples...")
        tcpJob?.cancelAndJoin()
        tmpSeq.clear()
        tunAck.clear()
        val now = System.currentTimeMillis()
        lastTcpRequestTime.set(now)
        tcpJob = CoroutineScope(masterJob + tcpWorkerDispatcher).launch {
            val tcpWorker = TcpProxyWorker(
                workerId = 1,
                transportType = TcpProxyWorker.TransportType.TCP,
                clientId = sharedClientId,
                service = this@ShustreeVpnService,
                proxyHost = proxyPair.tcp.proxyAddress,
                proxyPort = proxyPair.tcp.proxyPort, //443, //1762, //
                isTunnelReady = isTunnelReady
            )
            tcpWorkers.add(tcpWorker)
            tcpWorker.run()
        }

        Log.i(TAG, "✅ [TCP Transport] Apples restarted on: ${proxyPair.tcp.proxyAddress}:${proxyPair.tcp.proxyPort}")
    }





    private fun updateUnderlyingNetworks() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val success = setUnderlyingNetworks(currentNetworks.toTypedArray())
            Log.i(TAG, "Updating underlying networks. Success: $success. Networks: $currentNetworks")
        }
    }



    @Synchronized
    fun getNextProxyPair(): ProxyPair? {
        if (availableTcpProxies.isEmpty()) {
            Log.e(TAG, "getNextProxyPair() called but one of the proxy lists is empty. " +
                    "TCP: ${availableTcpProxies.size}")
            return null
        }

        val index = proxySelectionCounter.getAndIncrement()
        val tcpIndex = index % availableTcpProxies.size
        val selectedTcp = availableTcpProxies[tcpIndex]
        Log.i(TAG, "Sync Proxy Selection [#$index]: TCP -> ${selectedTcp.proxyPort}")

        return ProxyPair(selectedTcp)
    }



    private fun onTunWriterSuccess() {
        tunWriterRetryDelayMs = 50L
    }




    private fun ByteBuffer.toHexString(): String {
        val tempBuffer = this.duplicate()
        val bytes = ByteArray(tempBuffer.remaining())
        tempBuffer.get(bytes)
        return bytes.joinToString(" ") { "%02x".format(it) }
    }



    private fun CoroutineScope.launchTunWriter() = launch(tunWriterDispatcher) {
        Log.i(TAG, "DEDICATED THREAD: TUN Writer and Watchdog Supervisor started.")
        while (isActive) {
            try {
                vpnInterface?.let { vpnFileDescriptor ->
                    FileOutputStream(vpnFileDescriptor.fileDescriptor).use { vpnOutput ->
                        val tunOutputChannel = vpnOutput.channel
                        onTunWriterSuccess()
                        while (isActive) {
                            try {
                                var highPrioPacket = highPriorityToDeviceChannel.tryReceive().getOrNull()
                                if (highPrioPacket != null) {
                                    try {
                                        while (highPrioPacket.hasRemaining()) {
                                            tunOutputChannel.write(highPrioPacket)
                                        }
                                    } catch (e: IOException) {
                                        Log.e(TAG, "TUN_OUT: High-prio write failed fatally.", e)
                                        break // Exit inner while loop
                                    }
                                    continue
                                }
                                val lowPrioPacket = lowPriorityToDeviceChannel.tryReceive().getOrNull()
                                if (lowPrioPacket != null) {
                                    try {
                                        while (lowPrioPacket.hasRemaining()) {
                                            tunOutputChannel.write(lowPrioPacket)
                                        }
                                    } catch (e: IOException) {
                                        Log.e(TAG, "TUN_OUT: Low-prio write failed fatally.", e)
                                        break // Exit inner while loop
                                    }
                                    continue
                                }

                                try {
                                    select<Unit> {
                                        highPriorityToDeviceChannel.onReceive { packet ->
                                            while (packet.hasRemaining()) {
                                                tunOutputChannel.write(packet)
                                            }
                                        }
                                        lowPriorityToDeviceChannel.onReceive { packet ->
                                            while (packet.hasRemaining()) {
                                                tunOutputChannel.write(packet)
                                            }
                                        }
                                    }
                                } catch (e: IOException) {
                                    Log.e(TAG, "TUN_OUT: Write (from select) failed fatally.", e)
                                    break // Exit inner while loop
                                } catch (e: ClosedReceiveChannelException) {
                                    Log.i(TAG, "A writer channel was closed, shutting down writer.")
                                    delay(762)
                                    return@launch
                                }
                            } catch (e: Exception) {

                                when (e) {
                                    is IOException -> {
                                        Log.e(TAG, "[TUN_WRITER] Catastrophic IOException. Breaking inner loop to trigger self-healing.", e)
                                        break
                                    }
                                    is ClosedReceiveChannelException -> {
                                        if (isActive) {
                                            Log.e(TAG, "[TUN_WRITER] A channel closed unexpectedly while service is active. Breaking loop.", e)
                                        } else {
                                            Log.i(TAG, "[TUN_WRITER] A channel closed as part of a planned shutdown.")
                                        }
                                        break
                                    }
                                    is CancellationException -> {
                                        Log.i(TAG, "[TUN_WRITER] Write loop cancelled.")
                                        throw e
                                    }
                                    else -> {
                                        Log.e(TAG, "[TUN_WRITER] An unexpected, recoverable error occurred in the write loop. Continuing.", e)
                                        delay(87)
                                    }
                                }
                            }
                        }
                    }
                }

                if (vpnInterface == null && isActive) {
                    throw IOException("vpnInterface is null, cannot create TUN writer.")
                }

            } catch (e: ClosedReceiveChannelException) {
                Log.w(TAG, "[TUN_WRITER] A channel was closed. The service is likely shutting down. Exiting loop.")
                if (!isActive) {
                    Log.i(TAG, "A writer channel was closed as part of a planned shutdown.")
                    return@launch
                } else {
                    Log.e(TAG, "TUN_OUT: A writer channel closed unexpectedly while service is active. Breaking to trigger self-healing.", e)
                    delay(1762)
                    break
                }
            } catch (e: IOException) {
                Log.e(TAG, "[TUN_WRITER] IOException during write, possibly TUN is closed. Retrying after delay.", e)
                delay(tunWriterRetryDelayMs)
                tunWriterRetryDelayMs = (tunWriterRetryDelayMs * 2).coerceAtMost(MAX_TUN_WRITER_RETRY_DELAY_MS)
            } catch (e: Exception) {
                Log.e(TAG, "[TUN_WRITER] An unexpected exception occurred. This should not happen. Continuing after a short delay.", e)
                delay(762) // A short, fixed delay before trying again.
            }
        }
        Log.w(TAG, "DEDICATED THREAD: TUN Writer has completely stopped.")
    }



    private fun CoroutineScope.launchTunReader(establishedInterface: ParcelFileDescriptor) = launch(tunReaderDispatcher) {
        Log.i(TAG, "DEDICATED THREAD: TUN Reader started on ${Thread.currentThread().name}.")
        val setupTunStartTime = System.currentTimeMillis() // Assuming you want to measure from launch
        Log.d("DEBUG_VPN_SETUP", "🏁 SETUP COMPLETE - Total setup time: ${System.currentTimeMillis() - setupTunStartTime}ms.")


        try {
            FileInputStream(establishedInterface.fileDescriptor).channel.use { tunInput ->
                isRunning.set(true)
                Log.i(
                    TAG,
                    "Workers are presumed connected. isRunning is now true. VPN is online."
                )
                val buffer = ByteBuffer.allocate(65534)
                while (isRunning.get() && isActive) { // Correctly use 'isActive' from the coroutine scope
                    try {
                        buffer.clear()
                        val bytesRead = tunInput.read(buffer)
                        if (bytesRead <= 0) {
                            if (bytesRead == -1) {
                                Log.w(TAG, "TUN interface closed by OS.")
                                break
                            }
                            delay(1)
                            continue
                        }

                        buffer.flip()

                        bufferLoop@ while (buffer.hasRemaining()) {
                            val startPosition = buffer.position()
                            if (buffer.remaining() < 20) break

                            val srcIpByte1 = buffer[12]
                            val srcIpByte2 = buffer[13]

                            if (srcIpByte1 != 0x0A.toByte() || srcIpByte2 != 0x08.toByte()) {
                                break
                            }

                            val version = (buffer.get(startPosition).toInt() shr 4) and 0x0F
                            val totalLength: Int
                            val protocol: Int
                            val ipHeaderLength: Int

                            try {
                                when (version) {
                                    4 -> {
                                        ipHeaderLength =
                                            (buffer.get(startPosition).toInt() and 0x0F) * 4
                                        totalLength =
                                            buffer.getShort(startPosition + 2)
                                                .toInt() and 0xFFFF
                                        protocol =
                                            buffer.get(startPosition + 9).toInt() and 0xFF

                                        val physicalBytesAvailable = buffer.limit() - startPosition

                                        if (totalLength < ipHeaderLength || totalLength > physicalBytesAvailable) {
                                            Log.w(
                                                TAG,
                                                "Packet parsing mismatch! IP TotalLength: $totalLength, but physically available in buffer: $physicalBytesAvailable. Dropping packet."
                                            )
                                            buffer.position(buffer.limit()) // Завершаем разбор этого чтения
                                            continue@bufferLoop
                                        }
                                    }

                                    6 -> {
                                        val payloadLength = buffer.getShort(startPosition + 4).toInt() and 0xFFFF
                                        val v6TotalLength = 40 + payloadLength // 40 is the fixed IPv6 header

                                        val nextPosition = (startPosition + v6TotalLength).coerceAtMost(buffer.limit())
                                        buffer.position(nextPosition)

                                        continue@bufferLoop
                                    }


                                    else -> {
                                        Log.e(
                                            TAG,
                                            "Unknown IP Ver=$version in try-block. Dropping."
                                        )
                                        buffer.position(buffer.limit()) // Consume rest of buffer
                                        continue@bufferLoop
                                    }
                                }

                                if (protocol == 1) { // ICMP
                                    val totalLength = buffer.getShort(startPosition + 2).toInt() and 0xFFFF
                                    buffer.position(startPosition + totalLength)
                                    continue@bufferLoop
                                }

                                val tempSlice =
                                    buffer.slice().limit(totalLength) as ByteBuffer

                                val packetSlice = ByteBuffer.allocate(totalLength).apply {
                                    put(tempSlice)
                                    flip() // Rewind the new buffer to be ready for reading
                                }

                                val connectionInfo = IPP.generateConnectionKey(packetSlice)
                                if (connectionInfo == null) {
                                    Log.w(
                                        TAG,
                                        "IPP failed to generate connection key. Skipping packet: ${packetSlice.toHexString()}"
                                    )
                                    buffer.position(startPosition + totalLength) // Consume and continue
                                    continue@bufferLoop
                                }

                                val connectionKey = connectionInfo.keyString
                                deviceToNetworkChannel.send(packetSlice)
                                                                buffer.position(startPosition + totalLength)

                            } catch (e: Exception) {
                                when (e) {
                                    is IndexOutOfBoundsException,
                                    is BufferUnderflowException,
                                        -> {
                                        Log.e(
                                            TAG,
                                            "[PACKET_PARSER] Malformed packet detected (bounds error). This is likely garbage on the wire or a logic bug. Dropping remaining buffer to recover.",
                                            e
                                        )
                                    }
                                    is IllegalArgumentException -> {
                                        Log.e(TAG, "[PACKET_PARSER] Invalid argument during packet parsing. The packet's values are likely corrupt. Dropping remaining buffer.", e)
                                    }
                                    is kotlinx.coroutines.CancellationException -> {
                                        // This is a normal shutdown. Re-throw to exit all loops.
                                        Log.i(TAG, "[PACKET_PARSER] Parsing cancelled.")
                                        throw e
                                    }
                                    else -> {
                                        Log.e(TAG, "[PACKET_PARSER] An unexpected error occurred while parsing a single packet. Dropping remaining buffer.", e)
                                    }
                                }
                                buffer.position(buffer.limit())

                            }

                        } // End of buffer processing loop
                    } catch (e: Exception) {
                        when (e) {
                            is java.io.IOException -> {
                                Log.e(TAG, "[TUN_READER] IOException in main read loop. Retrying after delay.", e)
                                delay(200) // Brief pause before trying to read again.
                            }
                            is kotlinx.coroutines.CancellationException -> {
                                // This is a normal shutdown signal. Re-throw it to stop the loop.
                                Log.i(TAG, "[TUN_READER] Main read loop cancelled.")
                                throw e
                            }
                            else -> {
                                Log.e(TAG, "[TUN_READER] UNHANDLED EXCEPTION in packet processing cycle. This indicates a severe bug. The buffer has been dropped to prevent a crash. Continuing loop.", e)
                                delay(545)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.channels.ClosedSendChannelException) {
                Log.i(TAG, "TUN Reader channel closed, shutting down.")
            } else {
                Log.e(TAG, "TUN Reader loop broke due to a fatal exception.", e)
            }
        } finally {
            Log.w(TAG, "DEDICATED THREAD: TUN Reader has completely stopped.")
        }
    }





    // TODO
    private suspend fun sendTcpKeepAlive() {
        val pingSize = 762
        val pingPayload = ByteArray(pingSize)
        java.security.SecureRandom().nextBytes(pingPayload)
        val connectionKey = "6:10.8.0.1:54556-94.26.228.105:762"
        val destHost = InetAddress.getByName("94.26.228.105")
        val srcHost = InetAddress.getByName("10.8.0.1")

        try {

            val connectionKeyBytes = connectionKey.toByteArray(StandardCharsets.UTF_8)
            val destIpBytes = destHost.address // 4 байта для IPv4
            val srcIpBytes = srcHost.address   // 4 байта для IPv4
            val totalSize = 1 + // version (1 байт)
                    1 + // protocol (1 байт)
                    2 + connectionKeyBytes.size + // длина строки (2) + сама строка
                    4 + pingPayload.size +        // длина payload (4) + сам payload
                    destIpBytes.size +            // IP назначения (4)
                    2 +                           // destPort (2)
                    srcIpBytes.size +             // IP источника (4)
                    2 +                           // srcPort (2)
                    1 +                           // currentClientAck маркер (1)
                    1                             // isMasked (1)

            val buffer = ByteBuffer.allocate(totalSize)

            // Заполняем буфер данными
            buffer.put(4.toByte()) // version
            buffer.put(6.toByte()) // protocol (TCP)

            buffer.putShort(connectionKeyBytes.size.toShort())
            buffer.put(connectionKeyBytes)

            buffer.putInt(pingPayload.size)
            buffer.put(pingPayload)

            buffer.put(destIpBytes)
            buffer.putShort(762.toShort()) // destPort

            buffer.put(srcIpBytes)
            buffer.putShort(54556.toShort()) // srcPort

            buffer.put(0.toByte()) // currentClientAck = null (0 означает отсутствие значения)
            buffer.put(if (false) 1.toByte() else 0.toByte()) // isMasked = false

            // Готовим буфер к чтению/отправке
            buffer.flip()

            // --- ОТПРАВКА ---
            // Теперь отправляем именно подготовленный ByteBuffer
            deviceToNetworkChannel.send(buffer)

            Log.v(TAG, "🚀 [Keep-Alive] TCP Ping (762b) sent to channel as ByteBuffer")
        } catch (e: Exception) {
            Log.e(TAG, "❌ [Keep-Alive] TCP Ping failed: ${e.message}")
        }
    }






    private suspend fun setupAndRunVpn() {
        val setupStartTime = System.currentTimeMillis()
        Log.d("DEBUG_VPN_SETUP", "🚀 SETUP START - Beginning VPN setup sequence on thread: ${Thread.currentThread().name}")
        startInForeground()
        try {
            if (availableTcpProxies.isEmpty()) {
                Log.e(TAG, "Critical error: Proxy lists are empty at launch. TCP: ${availableTcpProxies.size}")
                withContext(Dispatchers.Main) { commandStopVpn() }
                return
            }
            val proxyPair = getNextProxyPair() ?: run {
                Log.e(TAG, "Failed to get proxy pair. Aborting.")
                withContext(Dispatchers.Main) { commandStopVpn() }
                return
            }
            Log.i(TAG, "🚀 VPN Session Starting with Synced Proxies:")
            Log.i(TAG, "   TCP (Apples): ${proxyPair.tcp.proxyAddress}:${proxyPair.tcp.proxyPort}")
            Log.d("DEBUG_VPN_SETUP", "Waiting for a valid network from NetworkCallback...")
            withTimeoutOrNull(5000) { // 5-second timeout
                networkReadyDeferred?.await()
            }
            if (currentNetworks.isEmpty()) {
                throw IOException("No active network with Internet capability found after 5 seconds. Cannot establish VPN.")
            }

            Log.d("DEBUG_VPN_SETUP", "Network is ready. Proceeding with setup on networks: $currentNetworks")
            delay(200)
            val builder = Builder()
                .addAddress("10.8.0.1", 32)
                .addRoute("0.0.0.0", 0)
                .addAddress("fd00:10:8::1", 128)
                .addRoute("::", 0)
                .setBlocking(true)
                .setMtu(1280)
                .setSession(getString(R.string.app_name))
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            for (packageName in disallowedApps) {
                try {
                    builder.addDisallowedApplication(packageName)
                    Log.d("DEBUG_VPN_SETUP", "✅ App excluded from VPN: $packageName")
                } catch (e: PackageManager.NameNotFoundException) {
                    // Это нормально: если приложение не установлено, просто идем дальше
                    Log.w("DEBUG_VPN_SETUP", "ℹ️ App not installed, skipping exclusion: $packageName")
                } catch (e: Exception) {
                    Log.e("DEBUG_VPN_SETUP", "❌ Failed to exclude $packageName: ${e.message}")
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setUnderlyingNetworks(currentNetworks.toTypedArray())
            }

            delay(762)

            val prepareIntent = prepare(this)
            if (prepareIntent != null) {
                Log.e(TAG, "VPN not prepared! Sending intent to UI.")
                return
            }

            delay(45) // Small breather for the system AppOps service
            val establishedInterface = try {
                builder.establish()
            } catch (e: SecurityException) {
                Log.e(TAG, "System denied establishment. UID mismatch?", e)
                null
            }

            if (establishedInterface == null) {
                throw IOException("System refused to establish TUN. Check Always-on VPN settings.")
            }

            vpnInterface = establishedInterface
            delay(762)
            restartTcpTransport(proxyPair)
            delay(321)
            val tcpConfirmed = withTimeoutOrNull(10_000) { // Ждем максимум 10 секунд
                while (!isTunnelReady.get() && isActive) {
                    delay(200) // Проверяем каждые 200 мс
                }
                isTunnelReady.get()
            }
            if (tcpConfirmed == true) {
                Log.i(TAG, "✅ TCP Handshake confirmed. Now starting")
            } else {
                Log.w(TAG, "⚠️ TCP Handshake slow or pending, STOP VPN.")
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        applicationContext,
                        getString(R.string.network_error_message),
                        Toast.LENGTH_LONG
                    ).show()
                    commandStopVpn()
                }
                return
            }
            delay(1762)
            CoroutineScope(masterJob).launchTunWriter() // This now matches the new signature
            delay(1762)
            CoroutineScope(masterJob).launchTunReader(establishedInterface)
            delay(762)
            val isHealthy = withTimeoutOrNull(17_762) { // 18 second timeout
                while (!isTunnelReady.get() && isActive) {
                    delay(200) // Poll every 100ms
                }
                isTunnelReady.get()
            }

            if (isHealthy == true) {
                Log.i(TAG, "✅ VPN PATH VERIFIED. Opening gates.")
                withContext(Dispatchers.Main) {
                    notifyStatusChanged(isConnected = true, isConnecting = false)
                }

            } else {
                Log.e(TAG, "❌ FATAL: VPN Path failed verification. Shutting down.")
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        applicationContext,
                        getString(R.string.network_error_message),
                        Toast.LENGTH_LONG
                    ).show()
                    commandStopVpn()
                }
                return
            }

            val totalTime = System.currentTimeMillis() - setupStartTime
            val keepAliveScope = CoroutineScope(masterJob + Dispatchers.IO)

            CoroutineScope(masterJob + Dispatchers.Default).launch {
                val STALL_TIMEOUT   = 34_762L   // Затык (шлем, но не получаем)
                val IDLE_KEEPALIVE  = 420_000L // Простой (ждем почту/push) - 7 минут
                val WORKER_IDLE     = 420_000L

                while (isActive) {
                    delay(10_000L + (1000..3000).random()) // Проверка каждые ~12 сек
                    val now = System.currentTimeMillis()
                    val rxDelta = now - lastTcpResponseTime.get()
                    val txDelta = now - lastTcpRequestTime.get()
                    Log.w(TAG, "🚨 HEALTH CHECK COROUTINE'S STARTED ANOTHER ITERATION | now: $now | rxDelta: $rxDelta | txDelta: $txDelta ")
                    // 1.1 ЛОГИКА ЗАТЫКА (DPI или Сетевой лаг)
                    if (rxDelta > STALL_TIMEOUT && ( rxDelta - txDelta ) > STALL_TIMEOUT) {
                            Log.w(TAG, "🚨 TCP STALL detected (Tx active, Rx dead).")
                    }

                    // 1.2 ЛОГИКА ПОТЕРИ СВЯЗИ (5 минут без входяших пакетов при постоянном запросе)
                    if ( ( rxDelta - txDelta ) > IDLE_KEEPALIVE) {
                            Log.w(TAG, "🚨 7 MIN TCP STALL detected. Stopping VPN...")
                            stopVpn()
                    }
                    // 2. ЛОГИКА ПРОСТОЯ (Держим сокет для Push-уведомлений)
                    else if ( rxDelta > WORKER_IDLE && txDelta > WORKER_IDLE ) {
                        // Вместо тяжелого рестарта, просто "пнем" прокси, если ничего не происходит
                        // Или, если ты доверяешь рестарту, вызови его здесь, но с большим таймаутом.
                        Log.d(TAG, "🍃 TCP IDLE. Keeping NAT alive or refreshing...")
                        stopVpn()
                    }

                }
            }

            // 2. Генератор Пингов (UDP + TCP)
            keepAliveScope.launch {
                Log.i(TAG, "📡 Keep-Alive Generator started")
                var counter = 0

                while (isActive) {
                    try {
                        // Рандомный интервал 10-12 секунд для обхода DPI
                        val nextDelay = 15_762L + (545..15762).random().toLong()
                        delay(nextDelay)

                        if (isTunnelReady.get()) {
                            counter++
                            delay(500)
                            try {
                                sendTcpKeepAlive()
                            } catch (e: Exception) {
                                Log.e(TAG, "📡 TCP Ping send error: ${e.message}")
                            }
                            if (counter % 10 == 0) {
                                Log.d(TAG, "📡 Keep-Alive cycle #$counter completed")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "⚠️ Error in Keep-Alive loop: ${e.message}")
                        delay(2000)
                    }
                }
            }

            delay(Long.MAX_VALUE)

        } catch (e: Exception) {
            if (e is CancellationException) {
                Log.i(TAG, "Orchestrator job was cancelled. Shutting down gracefully.")
            } else {
                Log.e(TAG, "Fatal exception in VPN orchestrator, forcing shutdown.", e)
            }
            Log.i(TAG, "Orchestrator failed or was cancelled. Ensuring graceful shutdown.")
            withContext(NonCancellable) {
                stopVpn()
            }
        }
    }




    private suspend fun stopVpn() {
        if (isRunning.compareAndSet(true, false)) {
            withContext(NonCancellable) {
                Log.i(TAG, "[STOP] --- Critical Shutdown Initiated ---")
                notifyStatusChanged(isConnected = false, isConnecting = false)
                runCatching {
                    vpnInterface?.close()
                    vpnInterface = null
                    Log.i(TAG, "[STOP] Step 0: TUN closed. Internet restored to System.")
                }.onFailure { e -> Log.e(TAG, "[STOP] Emergency: TUN close failed", e) }
            }
            sessionTimerJob?.cancel()
            sessionTimerJob = null
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            runCatching {
                connectivityManager.unregisterNetworkCallback(networkCallback)
                Log.i(TAG, "[STOP] Unregistered network callback.")
            }.onFailure { e -> Log.e(TAG, "Failed to unregister network callback", e) }


            // --- Step 1: Stop Workers From Accepting New Connections ---
            if (tcpWorkers.isNotEmpty()) {
                Log.i(TAG, "[STOP] Issuing stop command to ${tcpWorkers.size} TCP workers...")
                runCatching {
                    tcpWorkers.toList().forEach { it.stop() }
                    tcpWorkers.clear()
                }.onSuccess { Log.i(TAG, "[STOP] All workers have been issued a stop command.") }
                    .onFailure { e -> Log.e(TAG, "[STOP] Exception while stopping TCP workers.", e) }
            }
            delay(200)

            Log.i(TAG, "[STOP] Sending cancellation signal to all service coroutines (masterJob)...")
            masterJob.cancel()
            tcpWorkers.clear()
            runCatching {
                closeChannels()
            }.onSuccess { Log.i(TAG, "[STOP] All communication channels closed.") }
                .onFailure { e -> Log.e(TAG, "[STOP] Exception while closing communication channels.", e) }
            runCatching {
                tmpSeq.clear(); tunAck.clear()
            }.onSuccess { Log.i(TAG, "[STOP] All TCP session states have been forcefully cleared.") }

            Log.i(TAG, "[STOP] Pausing for 186ms to allow system to settle...")
            delay(200)
            Log.i(TAG, "[STOP] Finalizing stop sequence...")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            Log.i(TAG, "[STOP] --- stopVpn sequence complete. Service will now be destroyed. ---")

        } else {
            Log.w(TAG, "[STOP] Stop command received, but VPN is already stopped or stopping. Ignoring.")
        }
    }

    private fun closeChannels() {
        highPriorityToDeviceChannel.close()
        lowPriorityToDeviceChannel.close()
        deviceToNetworkChannel.close()
    }



    private fun startInForeground() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "VPN Service",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Shustree Proxy Activated")
            .setContentText("Your connection is active.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14+
                // Идеальное решение для Android 14: говорим системе взять тип прямо из манифеста
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
                )
                Log.d("VPN_START", "Successfully called startForeground for Android 14+")
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // Android 10 - 13
                // На Android 10-13 типа specialUse еще не существовало.
                // Но в манифесте у нас прописан foregroundServiceType="specialUse".
                // Старые версии Android просто проигнорируют неизвестный им тип в манифесте,
                // поэтому здесь мы передаем 0 (дефолтный тип), что полностью легитимно для Android 10-13.
                startForeground(NOTIFICATION_ID, notification, 0)
                Log.d("VPN_START", "Successfully called startForeground for Android 10-13")
            } else {
                // Android 9 и ниже
                startForeground(NOTIFICATION_ID, notification)
                Log.d("VPN_START", "Successfully called legacy startForeground")
            }
        } catch (e: Exception) {
            Log.e("VPN_START", "Основной запуск Foreground Service не удался", e)
            try {
                // Отчаянный фолбэк для кастомных прошивок: пробуем передать явный флаг specialUse напрямую
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } catch (criticalException: Exception) {
                Log.e("VPN_START", "Критический сбой: система заблокировала Foreground режим", criticalException)
            }
        }
    }


    override fun onDestroy() {
        Log.w(TAG, "onDestroy() called. This implies an UNEXPECTED shutdown by the Android system.")
        sessionTimerJob?.cancel()
        if (isRunning.get()) {
            Log.e(TAG, "Service is being destroyed while still running! Forcing a blocking stopVpn().")
            runBlocking {
                stopVpn()
            }
        }
        Log.i(TAG, "onDestroy() has finished its work.")
        instance = null
        super.onDestroy()
    }


    private fun getUserCountry(): String? {
        try {

            val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val simCountry = telephonyManager.simCountryIso
            if (simCountry != null && simCountry.length == 2) { // Validate the country code
                Log.d(TAG, "Detected user country from SIM: $simCountry")
                return simCountry.lowercase() // Return "ru", "us", etc.
            }

            // Fallback to network country if SIM is not available or invalid
            val networkCountry = telephonyManager.networkCountryIso
            if (networkCountry != null && networkCountry.length == 2) {
                Log.d(TAG, "Detected user country from network: $networkCountry")
                return networkCountry.lowercase()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Could not determine user country", e)
            return null
        }

        val localeCountry = resources.configuration.locales.get(0).country
        if (localeCountry != null && localeCountry.length == 2) {
            Log.d(TAG, "Detected user country from locale as a fallback: $localeCountry")
            return localeCountry.lowercase()
        }

        return null
    }

}
