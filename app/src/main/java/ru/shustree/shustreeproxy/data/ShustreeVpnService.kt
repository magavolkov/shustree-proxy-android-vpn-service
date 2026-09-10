package ru.shustree.shustreeproxy.data
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.shustree.shustreeproxy.MainActivity
import ru.shustree.shustreeproxy.R
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import android.os.Handler
import android.os.Looper
import ru.shustree.shustreeproxy.VpnInfoRepository
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicLong
import java.nio.charset.StandardCharsets



/***
ShustreeVpnService (ServiceScope / Main Thread)
│
├── BalanceChecker (Постоянно работает в Main Thread, не зависит от VPN-сессии)
│
└── SessionScope (Создается при запуске VPN, завершается/перезапускается целиком)
│
├── supervisorScope
│    ├── tcpWorkerDispatcher   [IO.limitedParallelism(1)] -> Worker 1 (Apples)
│    ├── tunReaderDispatcher   [IO.limitedParallelism(1)] -> TUN Reader
│    └── tunWriterDispatcher   [IO.limitedParallelism(1)] -> TUN Writer
│
└── Exception Handling / Crash Recovery
└── При невосстановимой ошибке -> Перезапуск всех 3 воркеров из ServiceScope
***/


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
    //private lateinit var tunWriterDispatcher: CoroutineDispatcher
    //private lateinit var balanceMonitorDispatcher: CoroutineDispatcher

    private lateinit var vpnInfoRepository: VpnInfoRepository

    private val isTunnelReady = AtomicBoolean(false)

    private var lastActiveNetwork: Network? = null


    // Класс-контейнер для синхронной пары (можно объявить внутри сервиса или в ApiModels)
    data class ProxyPair(val tcp: ProxyDetails)

    //lateinit var highPriorityToDeviceChannel: Channel<ByteBuffer>
    //lateinit var lowPriorityToDeviceChannel: Channel<ByteBuffer>
    //private val lastResponseTime = AtomicLong(System.currentTimeMillis())

    //lateinit var masqueradingToNetworkChannel: Channel<ByteBuffer>
    lateinit var masqueradingToNetworkChannel: Channel<PooledPacket>




    private var vpnInterface: ParcelFileDescriptor? = null


    // Потоки и каналы для прямой записи в TUN
    private var vpnOutputStream: FileOutputStream? = null
    var tunOutputChannel: FileChannel? = null
        private set






    private val isRunning = AtomicBoolean(false)

    private val tcpWorkers = mutableListOf<TcpProxyWorker>()

    private var userCountry: String? = null
    private lateinit var deviceId: String
    private lateinit var userLocale: String



    // A unique ID for the current VPN session, regenerated on each start.
    private var sessionId: String = ""
    // The final ID sent to the proxy, combining device and session.
    private var sharedClientId: String = ""
    private var availableTcpProxies: List<ProxyDetails> = emptyList()
    private var availableUdpProxies: List<ProxyDetails> = emptyList()

    private val proxySelectionCounter = AtomicInteger(1) // Counter for round-robin

    private var disallowedApps: List<String> = emptyList()

    private val NOTIFICATION_ID = 1
    private val NOTIFICATION_CHANNEL_ID = "ShustreeVpnServiceChannel"

    private var tunWriterRetryDelayMs = 20L
    private val MAX_TUN_WRITER_RETRY_DELAY_MS = 2762L // Cap at 30 seconds

    private var currentNetworks: Set<Network> = emptySet()
    private var networkReadyDeferred: CompletableDeferred<Unit>? = null

    private var statusListener: VpnStatusListener? = null
    private val mainThreadHandler = Handler(Looper.getMainLooper())

    private var isNetworkCallbackRegistered = false



    object PacketArrayPool {
        private val maxPoolSize = 100 // Запас на ~100 параллельных пакетов в канале
        private val pool = ArrayDeque<ByteArray>(maxPoolSize)

        @Synchronized
        fun obtain(): ByteArray {
            return if (pool.isNotEmpty()) {
                pool.removeLast()
            } else {
                ByteArray(1500) // Создается только при пиковых нагрузках
            }
        }

        @Synchronized
        fun recycle(array: ByteArray) {
            if (array.size == 1500 && pool.size < maxPoolSize) {
                pool.addLast(array)
            }
        }
    }

    class PooledPacket(
        val bytes: ByteArray,
        val length: Int
    )




    companion object {
        private var instance: ShustreeVpnService? = null
        fun getService(): ShustreeVpnService? = instance
    }



    inner class LocalBinder : Binder() {
        fun getService(): ShustreeVpnService = this@ShustreeVpnService

        // --- NEW: Methods to register and unregister the listener ---
        fun registerListener(listener: VpnStatusListener) {
            statusListener = listener
            mainThreadHandler.post {
                try {
                    statusListener?.onVpnStatusChanged(isRunning.get(), false)
                } catch (e: DeadObjectException) {
                    // The Activity is gone. The listener is invalid.
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



    // Выносим логику миграции в отдельный suspend метод для чистоты
    private suspend fun handleFullTransportMigration() {

        // Берем новые прокси
        val newProxyPair = getNextProxyPair()
        if (newProxyPair != null) {
            // Рестарты (внутри них cancel старых Job и очистка таблиц)
            restartTcpTransport(newProxyPair)
        }
    }



    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            Log.i(TAG, "[NetworkCallback] Network available: ${network}")
            val connManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val capabilities = connManager.getNetworkCapabilities(network)
            // Only consider networks that can actually reach the internet
            if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {

                // 1. Проверяем смену сети
                val isNetworkChanged = lastActiveNetwork != null && lastActiveNetwork != network

                // 2. Обновляем состояние
                lastActiveNetwork = network
                currentNetworks = currentNetworks + network

                // 3. Сначала уведомляем ОС о новых сетях
                updateUnderlyingNetworks()

                // 4. Только ЕСЛИ сеть реально изменилась, запускаем миграцию
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

            // Если потерянная сеть была активной, сбрасываем её
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

        // THE COOL BYPASS: Wire the protector here.
        // This stays active as long as the service lives.
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
        whatsappPrefixes: List<String>,
        ruApps: List<String>, // NEW parameter
    ) {
        // This is the same logic you already have in onStartCommand for ACTION_START.
        // We are just calling it from a direct function now.
        if (isRunning.compareAndSet(false, true)) {
            Log.i(TAG, "[Binder] Received START command.")

            Log.i(TAG, "[Binder] Received START command with ${whatsappPrefixes.size} WhatsApp prefixes.")

            // 1. UPDATE THE HELPER IMMEDIATELY

            // Log the received data for debugging
            Log.d(TAG, "Applied WhatsApp Prefixes: ${whatsappPrefixes.take(5)}...")


            availableTcpProxies = tcpProxies
            disallowedApps = ruApps
            updateSessionTime(balanceInSeconds)

            // Immediately notify the UI that we are in a connecting state.
            notifyStatusChanged(isConnected = false, isConnecting = true)

            sessionId = UUID.randomUUID().toString().substring(0, 6)
            sharedClientId = "$deviceId-$sessionId"
            Log.i(TAG, "New session started. Full ClientID: $sharedClientId")

            masterJob = SupervisorJob()

            // Create a new, fresh deferred "gate" for this specific VPN session.
            networkReadyDeferred = CompletableDeferred()


            tcpWorkers.clear()


            // --- ДИСПЕТЧЕРЫ ДЛЯ APPLES (TCP) ---
            tcpWorkerDispatcher = Dispatchers.IO.limitedParallelism(1)


            // --- СИСТЕМНЫЕ ДИСПЕТЧЕРЫ ---
            tunReaderDispatcher = Dispatchers.IO.limitedParallelism(1)
            //tunWriterDispatcher = Dispatchers.IO.limitedParallelism(1)



            //highPriorityToDeviceChannel = Channel(capacity = Channel.RENDEZVOUS)//Channel(16384)
            //lowPriorityToDeviceChannel = Channel(capacity = Channel.RENDEZVOUS)//Channel(16384)
            masqueradingToNetworkChannel = Channel(capacity = Channel.RENDEZVOUS)//Channel(16384)


            // Register network callback
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
            isNetworkCallbackRegistered = true
            Log.i(TAG, "Registered network callback.")

            // Launch the main VPN setup coroutines
            CoroutineScope(masterJob).launch {
                setupAndRunVpn()
            }


        } else {
            Log.w(TAG, "[Binder] Start command received, but VPN is already running.")
        }
    }


    /**
     * Commands the service to stop the VPN sequence.
     * Replaces the ACTION_STOP broadcast.
     */
    fun commandStopVpn() {
        Log.i(TAG, "[Binder] Received STOP command.")

        CoroutineScope(Dispatchers.IO).launch {
            stopVpn() // Your existing stopVpn() function is perfect here.
        }
    }

    /**
     * Reliably get the VPN's running status.
     * Replaces the ACTION_VPN_STATUS broadcast.
     */
    fun isVpnRunning(): Boolean {
        return isRunning.get()
    }



    interface VpnStatusListener {
        /**
         * Called when the VPN's connection state changes.
         * @param isConnected The final state of the VPN.
         * @param isConnecting True if the VPN is currently in the process of starting or stopping.
         */        // START_STICKY ensures the service will be restarted if killed.
        // When it restarts, MainActivity will re-bind to it and restore the state.
        fun onVpnStatusChanged(isConnected: Boolean, isConnecting: Boolean)
    }

    private val binder = LocalBinder()
    // 2. --- OVERRIDE onBind ---
    override fun onBind(intent: Intent): IBinder {
        Log.i(TAG, "Service is being bound.")
        return binder
    }



    // --- NEW METHOD 1: To receive proxy and balance updates ---
    fun updateVpnData(tcp: List<ProxyDetails>, udp: List<ProxyDetails>, balanceInSeconds: Long) {
        Log.i(TAG, "Received updated data: ${tcp.size} TCP proxies, ${udp.size} UDP proxies.")

        this.availableTcpProxies = tcp

        // Сбрасываем счетчик при обновлении списков, чтобы начать с 0-го прокси новой партии
        proxySelectionCounter.set(0)

        updateSessionTime(balanceInSeconds)
    }

    // --- NEW METHOD 2: The session timer logic ---
    private fun updateSessionTime(newBalanceInSeconds: Long) {
        // Cancel any existing timer job to prevent multiple timers running
        sessionTimerJob?.cancel()


        if (newBalanceInSeconds <= 0) {
            Log.w(TAG, "Balance is zero or less. Stopping VPN.")
            // Ensure stop command is run on the main thread if it involves UI/service lifecycle
            mainThreadHandler.post { commandStopVpn() }
            return
        }


        Log.d(TAG, "Starting new balance countdown: $newBalanceInSeconds seconds.")
        // Launch a new timer on the service's main coroutine scope
        sessionTimerJob = launch { // 'launch' is available because ShustreeVpnService implements CoroutineScope
            try {
                delay(newBalanceInSeconds * 1000)
                // If the delay completes without being cancelled, the time has expired.
                Log.w(TAG, "Balance expired. Stopping VPN service.")
                // Switch to main context to safely stop the service, as it can affect UI state
                withContext(Dispatchers.Main) {
                    commandStopVpn()
                }
            } catch (e: CancellationException) {
                // This is expected when a new balance arrives and we cancel the old timer.
                Log.d(TAG, "Balance timer was cancelled, likely due to an update.")
            }
        }
    }




    private fun notifyStatusChanged(isConnected: Boolean, isConnecting: Boolean) {
        //val isConnected = isRunning.get()
        Log.d(TAG, "Notifying listener of status change: isConnected=$isConnected, isConnecting=$isConnecting")
        // Call the listener's method on the main thread to ensure UI can be updated safely.
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


    private suspend fun restartTcpTransport(proxyPair: ProxyPair) {
        Log.i(TAG, "🔄 [TCP Transport] Restarting Apples...")


        // 1. Отменяем старые корутины воркеров
        tcpJob?.cancelAndJoin()


        // Сбрасываем таймеры
        val now = System.currentTimeMillis()
        //lastTcpResponseTime.set(now)
        lastTcpRequestTime.set(now)

        // 2. Воркер 1: TCP READ / WRITE
        tcpJob = CoroutineScope(masterJob + tcpWorkerDispatcher).launch {
            val tcpWorker = TcpProxyWorker(
                workerId = 1,
                transportType = TcpProxyWorker.TransportType.TCP,
                clientId = sharedClientId,
                service = this@ShustreeVpnService,
                proxyHost = proxyPair.tcp.proxyAddress, //"cdn-1.magavolkov.space", //"shustree.ru", //"wolfish-paradise-cdn-02.magavolkov.space",//
                proxyPort = proxyPair.tcp.proxyPort, //443, //1762, //
                isTunnelReady = isTunnelReady
            )
            // Важно: в твоем коде tcpWorkers — это список.
            // При рестарте нужно быть осторожным, чтобы не плодить объекты.
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



    // называется Pair, потому что тут когда-то был также udp proxy address
    @Synchronized
    fun getNextProxyPair(): ProxyPair? {
        if (availableTcpProxies.isEmpty()) {
            Log.e(TAG, "getNextProxyPair() called but one of the proxy lists is empty. " +
                    "TCP: ${availableTcpProxies.size}")
            return null
        }

        // Используем ОДИН счетчик для обоих списков
        val index = proxySelectionCounter.getAndIncrement()

        // Вычисляем индекс для каждого списка (на случай если их размер разный, хотя в твоем API он совпадает)
        val tcpIndex = index % availableTcpProxies.size

        val selectedTcp = availableTcpProxies[tcpIndex]

        Log.i(TAG, "Sync Proxy Selection [#$index]: TCP -> ${selectedTcp.proxyPort}")

        return ProxyPair(selectedTcp)
    }





    /**
     * Call this when the TUN writer's FileOutputStream is successfully created.
     */
    private fun onTunWriterSuccess() {
        tunWriterRetryDelayMs = 50L
    }




    private fun CoroutineScope.launchTunReader(establishedInterface: ParcelFileDescriptor) = launch(tunReaderDispatcher) {
        Log.i(TAG, "DEDICATED THREAD: TUN Reader started on ${Thread.currentThread().name}.")
        //performConnectivityCheck("Step 9: After launching TUN Reader")
        val setupTunStartTime = System.currentTimeMillis() // Assuming you want to measure from launch
        Log.d("DEBUG_VPN_SETUP", "🏁 SETUP COMPLETE - Total setup time: ${System.currentTimeMillis() - setupTunStartTime}ms.")


        try {
            FileInputStream(establishedInterface.fileDescriptor).channel.use { tunInput ->
                // --- THIS IS THE SAFEST PLACE TO INITIALIZE HTTPCLIENT, AS YOU SUGGESTED ---

                // Step 3: Set the running flag and broadcast status.
                // This is the moment the VPN is officially "online".
                isRunning.set(true)
                Log.i(
                    TAG,
                    "Workers are presumed connected. isRunning is now true. VPN is online."
                )


                // ******************************************************************************************
                // TUN READING START -------------------------
                // ******************************************************************************************
                // Step 5: Start the main TUN reading and dispatching loop
                val buffer = ByteBuffer.allocate(1500)
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

                        // --- START OF ROBUST GARBAGE-PROTECTED PARSING LOGIC ---
                        bufferLoop@ while (buffer.hasRemaining()) {
                            val startPosition = buffer.position()
                            if (buffer.remaining() < 20) break

                            // Быстрая фильтрация пакетов прямо на чтении из TUN (без парсинга всего пакета)
                            val srcIpByte1 = buffer[12]
                            val srcIpByte2 = buffer[13]

                            // Если Source IP != 10.8.0.x (0x0A 0x08), то это "фантомный" пакет от старого сокета
                            if (srcIpByte1 != 0x0A.toByte() || srcIpByte2 != 0x08.toByte()) {
                                // Просто игнорируем пакет, не передаем его в worker channel
                                break
                            }

                            val version = (buffer.get(startPosition).toInt() shr 4) and 0x0F
                            val totalLength: Int
                            val protocol: Int
                            val ipHeaderLength: Int

                            try {
                                // --- 1. PARSE PACKET HEADER (ONCE) ---
                                when (version) {
                                    4 -> {
                                        // Get IPv4-specific details
                                        ipHeaderLength =
                                            (buffer.get(startPosition).toInt() and 0x0F) * 4
                                        totalLength =
                                            buffer.getShort(startPosition + 2)
                                                .toInt() and 0xFFFF
                                        protocol =
                                            buffer.get(startPosition + 9).toInt() and 0xFF

                                        // --- КРИТИЧЕСКАЯ КОРРЕКЦИЯ ГРАНИЦ ---
                                        // Вычисляем, сколько реально байт осталось в буфере от физически прочитанных из ОС
                                        val physicalBytesAvailable = buffer.limit() - startPosition

                                        if (totalLength < ipHeaderLength || totalLength > physicalBytesAvailable) {
                                            Log.w(
                                                TAG,
                                                "Packet parsing mismatch! IP TotalLength: $totalLength, but physically available in buffer: $physicalBytesAvailable. Dropping packet."
                                            )
                                            // Обрезаем пакет по фактически доступному размеру, чтобы не захватывать нули из незаполненного буфера
                                            buffer.position(buffer.limit()) // Завершаем разбор этого чтения
                                            continue@bufferLoop
                                        }
                                    }

                                    6 -> {
                                        val payloadLength = buffer.getShort(startPosition + 4).toInt() and 0xFFFF
                                        val v6TotalLength = 40 + payloadLength // 40 is the fixed IPv6 header

                                        // Safety check: don't jump past the buffer limit
                                        val nextPosition = (startPosition + v6TotalLength).coerceAtMost(buffer.limit())
                                        buffer.position(nextPosition)

                                        // Log once in a while or use Verbose to avoid logcat spam
                                        // Log.v(TAG, "Skipping IPv6 packet ($totalLength bytes)")
                                        continue@bufferLoop
                                    }


                                    else -> {
                                        // This case is already handled by the logic above the try-catch,
                                        // but as a safeguard, we log and continue.
                                        Log.e(
                                            TAG,
                                            "Unknown IP Ver=$version in try-block. Dropping."
                                        )
                                        buffer.position(buffer.limit()) // Consume rest of buffer
                                        continue@bufferLoop
                                    }
                                }

                                if (protocol == 1) { // ICMP
                                    // Skip this packet. ICMP is system noise and doesn't need proxying
                                    // for WhatsApp/browsing to work.
                                    val totalLength = buffer.getShort(startPosition + 2).toInt() and 0xFFFF
                                    buffer.position(startPosition + totalLength)
                                    continue@bufferLoop
                                }


                                // --- 2. CREATE A SLICE ---
                                val tempSlice =
                                    buffer.slice().limit(totalLength) as ByteBuffer


                                // Allocate a new, independent buffer and copy the data from the slice into it.
                                //val packetSlice = ByteBuffer.allocate(totalLength).apply {
                                //    put(tempSlice)
                                //    flip() // Rewind the new buffer to be ready for reading
                                //}


                                // 1. Берем готовый массив из пула (0 B alocations для GC!)
                                val packetBytes = PacketArrayPool.obtain()

                                // 2. Копируем ровно totalLength байт прямо из общего ByteBuffer
                                buffer.position(startPosition)
                                buffer.get(packetBytes, 0, totalLength)

                                // 3. Отправляем в канал DTO-объект или просто пары (Array, Length)
                                masqueradingToNetworkChannel.send(PooledPacket(packetBytes, totalLength))

                                buffer.position(startPosition + totalLength)



                                //masqueradingToNetworkChannel.send(packetSlice)
                                //Log.d(
                                //    TAG,
                                //    "[Dispatched ${packetSlice.remaining()} bytes to worker channel."
                                //)

                                // --- 5. FINALLY, ADVANCE THE MAIN BUFFER ---

                            } catch (e: Exception) {
                                // --- HARDENED CATCH BLOCK 3 ---

                                when (e) {
                                    is IndexOutOfBoundsException,
                                    is BufferUnderflowException,
                                        -> {
                                        // These are the most common errors for malformed packets.
                                        Log.e(
                                            TAG,
                                            "[PACKET_PARSER] Malformed packet detected (bounds error). This is likely garbage on the wire or a logic bug. Dropping remaining buffer to recover.",
                                            e
                                        )
                                    }
                                    is IllegalArgumentException -> {
                                        // This can happen if, for example, a header value is invalid.
                                        Log.e(TAG, "[PACKET_PARSER] Invalid argument during packet parsing. The packet's values are likely corrupt. Dropping remaining buffer.", e)
                                    }
                                    is kotlinx.coroutines.CancellationException -> {
                                        // This is a normal shutdown. Re-throw to exit all loops.
                                        Log.i(TAG, "[PACKET_PARSER] Parsing cancelled.")
                                        throw e
                                    }
                                    else -> {
                                        // A catch-all for any other unexpected error during the processing of a single packet.
                                        Log.e(TAG, "[PACKET_PARSER] An unexpected error occurred while parsing a single packet. Dropping remaining buffer.", e)
                                    }
                                }

                                buffer.position(buffer.limit())

                            }

                        } // End of buffer processing loop
                    } catch (e: Exception) {
                        // --- HARDENED CATCH BLOCK 2 ---
                        // This is the safety net for the entire read-and-process cycle.

                        when (e) {
                            is java.io.IOException -> {
                                // This is a potentially recoverable I/O error on the read itself.
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
        val connectionKey = "6:10.8.0.1:54556-94.26.228.105:762"

        val connectionKeyBytes = connectionKey.toByteArray(StandardCharsets.UTF_8)
        val destHost = InetAddress.getByName("94.26.228.105")
        val srcHost = InetAddress.getByName("10.8.0.1")

        val destIpBytes = destHost.address // 4 байта
        val srcIpBytes = srcHost.address   // 4 байта

        // 1. Считаем точный размер кастомного пакета
        val totalSize = 1 + // version
                1 + // protocol
                2 + connectionKeyBytes.size +
                4 + pingSize +
                destIpBytes.size +
                2 + // destPort
                srcIpBytes.size +
                2 + // srcPort
                1 + // currentClientAck
                1   // isMasked

        // 2. Берем подготовленный ByteArray(1500) из пула
        val packetBytes = PacketArrayPool.obtain()

        try {
            if (totalSize > packetBytes.size) {
                Log.e(TAG, "❌ [Keep-Alive] Packet size ($totalSize) exceeds pooled array capacity (${packetBytes.size})")
                PacketArrayPool.recycle(packetBytes)
                return
            }

            // 3. Используем ByteBuffer.wrap(), чтобы красиво зашивать бинарные данные в наш pooled-массив
            val buffer = ByteBuffer.wrap(packetBytes)

            buffer.put(4.toByte()) // version
            buffer.put(6.toByte()) // protocol (TCP)

            buffer.putShort(connectionKeyBytes.size.toShort())
            buffer.put(connectionKeyBytes)

            buffer.putInt(pingSize)

            // Генерируем рандом прямо в срез буфера без дополнительных ByteArray(pingSize)
            val pingStartPos = buffer.position()
            java.security.SecureRandom().nextBytes(packetBytes.copyOfRange(pingStartPos, pingStartPos + pingSize))
            buffer.position(pingStartPos + pingSize)

            buffer.put(destIpBytes)
            buffer.putShort(762.toShort()) // destPort

            buffer.put(srcIpBytes)
            buffer.putShort(54556.toShort()) // srcPort

            buffer.put(0.toByte()) // currentClientAck = null
            buffer.put(0.toByte()) // isMasked = false

            // 4. Отправляем PooledPacket с точным количеством записанных байт (totalSize)
            masqueradingToNetworkChannel.send(PooledPacket(packetBytes, totalSize))

            Log.v(TAG, "🟢 [Keep-Alive] TCP Ping ($totalSize b) sent to channel as PooledPacket")
        } catch (e: Exception) {
            // Если при формировании пакета произошел сбой, обязательно возвращаем массив обратно
            PacketArrayPool.recycle(packetBytes)
            Log.e(TAG, "❌ [Keep-Alive] TCP Ping failed: ${e.message}", e)
        }
    }

    private suspend fun setupAndRunVpn() = coroutineScope {
        val setupStartTime = System.currentTimeMillis()
        Log.d("DEBUG_VPN_SETUP", "🚀 SETUP START - Beginning VPN setup sequence on thread: ${Thread.currentThread().name}")

        startInForeground()

        try {
            // --- ШАГ 1: Проверка доступных прокси ---
            if (availableTcpProxies.isEmpty()) {
                Log.e(TAG, "Critical error: Proxy lists are empty at launch. TCP size: ${availableTcpProxies.size}")
                withContext(Dispatchers.Main) { commandStopVpn() }
                return@coroutineScope
            }

            // --- ШАГ 2: Получение пары прокси ---
            val proxyPair = getNextProxyPair() ?: run {
                Log.e(TAG, "Failed to get proxy pair. Aborting.")
                withContext(Dispatchers.Main) { commandStopVpn() }
                return@coroutineScope
            }

            Log.i(TAG, "🔑 VPN Session Starting with Synced Proxies:")
            Log.i(TAG, "   TCP: ${proxyPair.tcp.proxyAddress}:${proxyPair.tcp.proxyPort}")

            // --- ШАГ 3: Ожидание готовности сети ---
            Log.d("DEBUG_VPN_SETUP", "Waiting for a valid network from NetworkCallback...")
            withTimeoutOrNull(5000) {
                networkReadyDeferred?.await()
            }

            if (currentNetworks.isEmpty()) {
                throw IOException("No active network with Internet capability found after 5 seconds. Cannot establish VPN.")
            }
            Log.d("DEBUG_VPN_SETUP", "Network is ready. Proceeding with setup on networks: $currentNetworks")

            // --- ШАГ 4: Построение VPN-интерфейса ---
            Log.d("DEBUG_VPN_SETUP", "[Step 3] -> Building VpnService builder with underlying networks: $currentNetworks")

            val builder = Builder().apply {
                addAddress("10.8.0.1", 32)
                addRoute("0.0.0.0", 0)
                addAddress("fd00:10:8::1", 128)
                addRoute("::", 0)

                setBlocking(true)
                setMtu(1280)
                setSession(getString(R.string.app_name))
                addDnsServer("8.8.8.8")
                addDnsServer("1.1.1.1")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setMetered(false)
                    setUnderlyingNetworks(currentNetworks.toTypedArray())
                }

                for (packageName in disallowedApps) {
                    runCatching {
                        addDisallowedApplication(packageName)
                        Log.d("DEBUG_VPN_SETUP", "✔ App excluded from VPN: $packageName")
                    }.onFailure { e ->
                        Log.w("DEBUG_VPN_SETUP", "⚠️ App exclusion skipped/failed for $packageName: ${e.message}")
                    }
                }
            }

            if (prepare(this@ShustreeVpnService) != null) { // Укажите имя вашего VpnService класса
                Log.e(TAG, "VPN not prepared! Requesting UI permission.")
                return@coroutineScope
            }

            // --- ШАГ 5: Создание TUN-интерфейса ---
            Log.d("DEBUG_VPN_SETUP", "[Step 4] -> Calling builder.establish()...")

            val establishedInterface = try {
                builder.establish()
            } catch (e: SecurityException) {
                Log.e(TAG, "System denied establishment. UID mismatch?", e)
                null
            } ?: throw IOException("System refused to establish TUN. Check Always-on VPN settings.")

            vpnInterface = establishedInterface
            Log.d("DEBUG_VPN_SETUP", "[Step 4] <- TUN Interface established successfully.")

            // --- ИНИЦИАЛИЗАЦИЯ TUN OUTPUT CHANNEL ---
            // Создаем поток записи и канал строго ОДИН раз за сессию VPN
            val vpnOutputStream = FileOutputStream(establishedInterface.fileDescriptor)
            tunOutputChannel = vpnOutputStream.channel
            Log.i(TAG, "✅ TUN Output Channel ready for TCP workers.")


            // Перезапуск транспортного слоя
            restartTcpTransport(proxyPair)

            // Подтверждение TCP Handshake
            val tcpConfirmed = withTimeoutOrNull(10_000) {
                while (!isTunnelReady.get() && isActive) {
                    delay(200)
                }
                isTunnelReady.get()
            }

            if (tcpConfirmed != true) {
                Log.w(TAG, "❌ TCP Handshake slow or pending, STOP VPN.")
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        applicationContext,
                        getString(R.string.network_error_message),
                        Toast.LENGTH_LONG
                    ).show()
                    commandStopVpn()
                }
                return@coroutineScope
            }

            Log.d("DEBUG_VPN_SETUP", "[Step 5] <- TCP Handshake OK. Starting TUN Readers/Writers...")

            // Запуск рабочих корутин чтения и записи TUN
            //launch { launchTunWriter() }
            launch { launchTunReader(establishedInterface) }

            // --- ШАГ 6: Верификация работоспособности туннеля ---
            val isHealthy = withTimeoutOrNull(18_000) {
                while (!isTunnelReady.get() && isActive) {
                    delay(762)
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
                return@coroutineScope
            }

            val totalTime = System.currentTimeMillis() - setupStartTime
            Log.d("DEBUG_VPN_SETUP", "🎉 SETUP COMPLETE - Total setup time: ${totalTime}ms.")

            // --- ШАГ 7: Запуск фоновых мониторов ---
            // 1. Мониторинг состояния подключения (Health Check / Stall Monitor)
            launch(Dispatchers.Default) {
                startHealthCheckLoop(proxyPair)
            }

            // 2. Генератор Keep-Alive пакетов
            launch(Dispatchers.IO) {
                startKeepAliveLoop()
            }

            // Удерживаем Orchestrator активным до тех пор, пока корутина не будет отменена
            awaitCancellation()

        } catch (e: CancellationException) {
            Log.i(TAG, "Orchestrator job was cancelled. Shutting down gracefully.")


            throw e // Пробрасываем CancellationException обязательным образом для coroutineScope
        } catch (e: Exception) {
            Log.e(TAG, "Fatal exception in VPN orchestrator, forcing shutdown.", e)
        } finally {
            Log.i(TAG, "Orchestrator ending. Ensuring graceful cleanup.")
            withContext(NonCancellable) {
                stopVpn()
            }
        }
    }



    /**
     * Цикл проверки здоровья TCP-соединения (зависание, idle, stalls).
     */
    private suspend fun startHealthCheckLoop(proxyPair: ProxyPair) {
        val STALL_TIMEOUT = 34_762L   // ~35 сек (активный Tx, но мертвый Rx)
        val IDLE_KEEPALIVE = 420_000L // 7 минут
        val WORKER_IDLE = 420_000L

        while (coroutineContext.isActive) {
            delay(10_000L + (1000..3000).random()) // Проверка раз в ~12 сек

            val now = System.currentTimeMillis()
            val rxDelta = now - lastTcpResponseTime.get()
            val txDelta = now - lastTcpRequestTime.get()

            Log.w(TAG, "🔍 HEALTH CHECK ITERATION | now: $now | rxDelta: $rxDelta | txDelta: $txDelta")

            // 1. Проверка на зависание приема при активной отправке (DPI / обрыв)
            if (rxDelta > STALL_TIMEOUT && (rxDelta - txDelta) > STALL_TIMEOUT) {
                Log.w(TAG, "⚠️ TCP STALL detected (Tx active, Rx dead).")
                // restartTcpTransport(proxyPair) // раскомментируйте при необходимости
            }

            // 2. Проверка на полный застой свыше 7 минут
            if ((rxDelta - txDelta) > IDLE_KEEPALIVE) {
                Log.w(TAG, "❌ 7 MIN TCP STALL detected. Stopping VPN...")
                stopVpn()
                break
            }
            // 3. Проверка на долгий IDLE по обоим каналам
            else if (rxDelta > WORKER_IDLE && txDelta > WORKER_IDLE) {
                Log.d(TAG, "⚠️ TCP IDLE timeout reached. Stopping VPN...")
                stopVpn()
                break
            }
        }
    }



    /**
     * Цикл периодической отправки Keep-Alive пингов для поддержки NAT/состояния туннеля.
     */
    private suspend fun startKeepAliveLoop() {
        Log.i(TAG, "🔄 Keep-Alive Generator started")
        var counter = 0

        while (coroutineContext.isActive) {
            try {
                // Рандомизация задержки (15-30 сек) для обхода систем обнаружения DPI
                val nextDelay = 15_762L + (545..15_762).random().toLong()
                delay(nextDelay)

                if (isTunnelReady.get()) {
                    counter++
                    delay(500)
                    try {
                        sendTcpKeepAlive()
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ TCP Ping send error: ${e.message}")
                    }

                    if (counter % 10 == 0) {
                        Log.d(TAG, "🔄 Keep-Alive cycle #$counter completed")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "⚠️ Error in Keep-Alive loop: ${e.message}")
                delay(2000)
            }
        }
    }


    private suspend fun stopVpn() {
        // Use compareAndSet to ensure this entire shutdown sequence only runs once.
        if (isRunning.compareAndSet(true, false)) {
            // This specific block MUST finish to prevent the "Internet Hang"
            withContext(NonCancellable) {
                Log.i(TAG, "[STOP] --- Critical Shutdown Initiated ---")
                notifyStatusChanged(isConnected = false, isConnecting = false)

                runCatching {
                    vpnInterface?.close()
                    vpnInterface = null
                    Log.i(TAG, "[STOP] Step 0: TUN closed. Internet restored to System.")
                }.onFailure { e -> Log.e(TAG, "[STOP] Emergency: TUN close failed", e) }
            }

            // --- 1. CANCEL THE TIMER ---
            sessionTimerJob?.cancel()
            sessionTimerJob = null

            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (isNetworkCallbackRegistered) {
                //TODO решить, где еще почистить в коде
                runCatching {
                    connectivityManager.unregisterNetworkCallback(networkCallback)
                }.onFailure { e ->
                    Log.w(TAG, "[STOP] Failed to unregister network callback: ${e.message}")
                }
                isNetworkCallbackRegistered = false
            }


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


            // 5. Очищаем список воркеров
            tcpWorkers.clear()

            runCatching {
                closeChannels()
            }.onSuccess { Log.i(TAG, "[STOP] All communication channels closed.") }
                .onFailure { e -> Log.e(TAG, "[STOP] Exception while closing communication channels.", e) }


            Log.i(TAG, "[STOP] Pausing for 186ms to allow system to settle...")
            delay(200)

            // --- Step 7: Finalize and Stop the Service ---
            Log.i(TAG, "[STOP] Finalizing stop sequence...")

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()

            Log.i(TAG, "[STOP] --- stopVpn sequence complete. Service will now be destroyed. ---")

        } else {
            Log.w(TAG, "[STOP] Stop command received, but VPN is already stopped or stopping. Ignoring.")
        }
    }

    // You'll need a helper to close your channels
    private fun closeChannels() {
        try {
            tunOutputChannel?.close()
            tunOutputChannel = null
            vpnOutputStream?.close()
            vpnOutputStream = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing TUN output channel", e)
        }
        //highPriorityToDeviceChannel.close()
        //lowPriorityToDeviceChannel.close()
        masqueradingToNetworkChannel.close()
    }






    private fun startInForeground() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title)) // Локализованный заголовок
            .setContentText(getString(R.string.notification_text))   // Локализованный текст
            //.setSmallIcon(R.drawable.ic_launcher_foreground)
            // 1. Указываем новую монохромную иконку для Status Bar и уведомления
            .setSmallIcon(R.drawable.ic_notification)
            // 2. (Опционально) Задаем акцентный цвет фона для иконки в шторке (Android 5.0+)
            .setColor(ContextCompat.getColor(this, R.color.notification_accent))

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
        // If the service is being destroyed unexpectedly, we must ensure all resources are released.
        // The isRunning check prevents this from running if stopVpn() was already called cleanly.
        sessionTimerJob?.cancel()
        if (isRunning.get()) {
            Log.e(TAG, "Service is being destroyed while still running! Forcing a blocking stopVpn().")
            // Only in this emergency "system killed my service" scenario is runBlocking acceptable.
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



