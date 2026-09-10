package ru.shustree.shustreeproxy.data


import UDPInIPPacketBuilder
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.*
import java.net.SocketException
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext



import java.util.concurrent.atomic.AtomicReference


class TcpProxyWorker(
    private val workerId: Int,
    private val transportType: TransportType, // НОВОЕ: TCP или UDP
    private val clientId: String,
    private val service: ShustreeVpnService,
    private val proxyHost: String,
    private val proxyPort: Int,
    private val isTunnelReady: AtomicBoolean,
) {

    enum class TransportType { TCP, UDP }
    private val TAG = "Worker-TLS-$transportType-$workerId"

    private val workerJob = Job()
    private val workerScope = CoroutineScope(Dispatchers.IO + workerJob)



    // SNI для мимикрии под сайт
    private val TLS_SNI_HOST = proxyHost

    private var isHandshakeDone = false


    private var retryDelayMs = 100L
    private val MAX_RETRY_DELAY_MS = 7762L






    /**
     * Call this when a connection fails. It increases the delay for the next attempt
     * and returns the current delay value.
     */
    private fun onConnectionFailure(): Long {
        val currentDelay = retryDelayMs
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS) // Double the delay, but don't exceed the max
        if (!isTunnelReady.get()) {
            isTunnelReady.set(false)
        }
        return currentDelay
    }




    /**
     * The main entry point for the worker.
     * It immediately delegates to the specific loop based on its role.
     */
    fun run() {
        Log.i(TAG, "Starting worker $workerId: Transport=$transportType")

        try {

            workerScope.launch {
                runFullDuplexTlsLoop()
            }


        } catch (e: Exception) {
            Log.e(TAG, "Fatal error in worker $workerId: ${e.message}", e)
        } finally {
            isTunnelReady.set(false)
            Log.i(TAG, "Worker $workerId stopped.")
        }
    }


    /**
     * Stops the worker by cancelling its CoroutineScope.
     * This causes all 'while(isActive)' loops within this worker to terminate.
     */
    fun stop() {
        Log.w(TAG, "Stopping worker...")
        // Cancel the master job for this worker. This propagates cancellation
        // to all coroutines launched in workerScope.
        workerScope.cancel()
        Log.i(TAG, "Worker stopped.")
    }



    private val tlsSocket = AtomicReference<Socket?>(null)

    private fun closeSocketQuietly(socketToClose: Socket? = null) {
        val target = socketToClose ?: tlsSocket.getAndSet(null)
        try {
            target?.close()
        } catch (_: Exception) {}
    }



    private suspend fun runFullDuplexTlsLoop() {
        Log.i(TAG, "Full-Duplex TLS loop manager starting.")

        while (workerScope.isActive) {
            // 1. Принудительно очищаем и закрываем старый сокет
            closeSocketQuietly()
            var activeSocket: Socket? = null

            try {

                // Обновляем последне время запроса сервера
                service.notifyTcpActivityTx()
                // Предполагаем, что connectAndTlsHandshake() возвращает Socket (не Socket?)
                // Если он возвращает null, бросаем exception или делаем проверку:
                val socket = connectAndTlsHandshake()
                    ?: throw IOException("Failed to establish TLS handshake (socket is null)")

                activeSocket = socket
                tlsSocket.set(socket)

                Log.i(TAG, "🔓 TLS session established with $TLS_SNI_HOST")
                isTunnelReady.set(true)
                //service.notifyTcpActivityRx()

                // 2. Запускаем изолированные Reader и Writer
                // Запускаем сопряженные лупы чтения и записи
                coroutineScope {
                    val outputStream = socket.getOutputStream()

                    // Reader Loop
                    launch(Dispatchers.IO) {
                        try {
                            readerLoop(socket)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.d(TAG, "Reader loop exited: ${e.message}")
                        } finally {
                            // Закрытие сокета разблокирует находящийся в write() writerLoop
                            closeSocketQuietly(socket)
                        }
                    }

                    // Writer Loop
                    launch(Dispatchers.IO) {
                        try {
                            writerLoop(outputStream)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.d(TAG, "Writer loop exited: ${e.message}")
                        } finally {
                            // Закрытие сокета разблокирует находящийся в read() readerLoop
                            closeSocketQuietly(socket)
                        }
                    }
                }

                // Добираемся сюда ТОЛЬКО когда ОБА лупа полностью завершились
                Log.i(TAG, "TLS loops finished cleanly. Reconnecting...")

                // Если coroutineScope завершился, значит корутины вышли из циклов
                //throw IOException("TLS loop finished unexpectedly (Connection closed or idle timeout)")

            } catch (e: CancellationException) {
                if (!workerScope.isActive) {
                    Log.i(TAG, "TLS loop intentionally cancelled by workerScope. Exiting.")
                    throw e
                } else {
                    Log.w(TAG, "Internal loop cancellation detected. Reconnecting...")
                }
            } catch (e: Exception) {
                Log.e(TAG, "🚨 TLS Tunnel crashed: ${e.message}. Reconnecting...", e)
            } finally {
                isTunnelReady.set(false)

                closeSocketQuietly(activeSocket)
                tlsSocket.compareAndSet(activeSocket, null)

                if (workerScope.isActive) {
                    delay(1762L)
                }
            }
        }
    }





    private suspend fun connectAndTlsHandshake(): Socket = withContext(Dispatchers.IO) {
        // 1. Создание базового TCP сокета
        val rawSocket = Socket()
        rawSocket.tcpNoDelay = true
        rawSocket.sendBufferSize = 1024 * 1024
        rawSocket.receiveBufferSize = 1024 * 1024
        rawSocket.keepAlive = true
        rawSocket.soTimeout = 60000

        if (!service.protect(rawSocket)) {
            throw IOException("Failed to protect raw TCP socket from VPN routing loops.")
        }

        rawSocket.connect(InetSocketAddress(proxyHost, proxyPort), 6000)

        // 2. Инициализация TLS через явный SSLContext (избегает внутренних legacy-рефлексий getDefault())
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, null, null)
        val sslFactory = sslContext.socketFactory

        val sslSocket = sslFactory.createSocket(rawSocket, TLS_SNI_HOST, proxyPort, true) as SSLSocket

        // 3. Установка SNI строго через публичный SSLParameters API
        val sslParams = sslSocket.sslParameters
        sslParams.serverNames = listOf(SNIHostName(TLS_SNI_HOST))

        // Включаем валидацию имени хоста на уровне системы, если необходимо
        sslParams.endpointIdentificationAlgorithm = "HTTPS"

        sslSocket.sslParameters = sslParams

        // 4. Запуск TLS Handshake
        sslSocket.startHandshake()

        // 5. Отправка прикладного хэндшейка Shustree
        val handshakeMsg = "CLIENT_ID:$clientId:FULL_DUPLEX_TLS"
        val handshakeBytes = handshakeMsg.toByteArray(Charsets.UTF_8)

        val output = DataOutputStream(sslSocket.getOutputStream())
        output.writeInt(handshakeBytes.size)
        output.write(handshakeBytes)
        output.flush()

        Log.i(TAG, "Internal application handshake sent securely over TLS.")
        sslSocket
    }



    private suspend fun writerLoop(outputStream: OutputStream) {
        try {
            while (workerScope.isActive) {
                // 1. Получаем пакет из нового канала
                val packet = service.masqueradingToNetworkChannel.receive()

                service.notifyTcpActivityTx()

                // 2. Гарантируем возврат массива в пул при любых ошибках ввода-вывода/отмены
                try {
                    // Защитная проверка длины пакета
                    if (packet.length > packet.bytes.size) {
                        Log.e(
                            TAG,
                            "[WRITER LOOP] Invalid packet length (${packet.length}) exceeds array size (${packet.bytes.size})"
                        )
                        continue
                    }

                    // 3. Синхронная запись в сокет на IO-диспатчере
                    withContext(Dispatchers.IO) {
                        outputStream.write(packet.bytes, 0, packet.length)
                        outputStream.flush()
                    }
                } finally {
                    // ОБЯЗАТЕЛЬНО: Возвращаем массив байт в пул!
                    // Теперь GC не касается этого массива, а тред TUN Reader сможет забрать его снова.
                    ShustreeVpnService.PacketArrayPool.recycle(packet.bytes)
                }
            }
            Log.d(TAG, "[WRITER LOOP] Loop finished naturally because workerScope.isActive = false")
        } catch (e: CancellationException) {
            Log.w(TAG, "[WRITER LOOP] Scope/Job was CANCELLED: ${e.message}", e)
            throw e
        } catch (e: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
            Log.w(TAG, "[WRITER LOOP] masqueradingToNetworkChannel was CLOSED: ${e.message}")
        } catch (e: java.net.SocketException) {
            Log.e(TAG, "[WRITER LOOP] Socket Exception: ${e.message}")
        } catch (e: java.io.IOException) {
            Log.e(TAG, "[WRITER LOOP] I/O Exception during write/flush: ${e.message}")
        } catch (e: Throwable) {
            Log.e(TAG, "[WRITER LOOP] Unexpected error: ${e.javaClass.simpleName} - ${e.message}", e)
        }
    }



    private suspend fun readerLoop(socket: Socket) {
        //val handler = NetworkToDeviceHandler()
        val stream = DataInputStream(socket.getInputStream())

        Log.d(TAG, "📥 [READER LOOP] Started with 5-byte framing")

        // 1. [BUFFER REUSE FOR GC] Фиксированный буфер для 5-байтового заголовка
        val headerBuffer = ByteArray(5)
        val headerByteBuffer = ByteBuffer.wrap(headerBuffer)

        // 2. [BUFFER REUSE FOR GC] Буфер под полезную нагрузку с запасом под MTU + Jitter Padding (например, 1500 байт)
        // MTU 1280 + запас под будущую мимикрию/джиттер до 1500 байт
        val maxExpectedPayloadSize = 1500
        val payloadBuffer = ByteArray(maxExpectedPayloadSize)

        while (workerScope.isActive && !socket.isClosed) {
            try {
                // Читаем 5 байт заголовка (EOFException при закрытии сокета)
                stream.readFully(headerBuffer)
                service.notifyTcpActivityRx()

                headerByteBuffer.clear()
                val packetType = headerByteBuffer.get().toInt() and 0xFF
                val payloadLength = headerByteBuffer.getInt()

                // Проверяем, укладывается ли размер в наш выделенный буфер
                if (payloadLength < 20 || payloadLength > maxExpectedPayloadSize) {
                    Log.e(TAG, "❌ Invalid or unexpected payload length: $payloadLength bytes (Max: $maxExpectedPayloadSize). Stream desync!")
                    break
                }

                Log.d("TLS_INBOUND_DEBUG", "📥 [FULL RAW IP PACKET] Type: $packetType | Len: $payloadLength")

                // Фильтрация спец-пакетов / служебных кадров
                if (packetType != 0x01) {
                    Log.w(TAG, "⚠️ Received non-data packet type: $packetType (Len: $payloadLength). Skipping...")
                    var bytesSkipped = 0
                    while (bytesSkipped < payloadLength) {
                        val skipped = stream.skipBytes(payloadLength - bytesSkipped)
                        if (skipped <= 0) break
                        bytesSkipped += skipped
                    }
                    continue
                }

                // Read exact payload directly into our pre-allocated payload buffer
                stream.readFully(payloadBuffer, 0, payloadLength)

                // Создаем точную копию байтов пакета для передачи в другой тред
                //val packetData = payloadBuffer.copyOfRange(0, payloadLength)
                //handler.processNewData(ByteBuffer.wrap(packetData))

                // Передаем ByteBuffer с ограничением (offset = 0, length = payloadLength)
                // Это избегает вызова copyOfRange и создания лишних ByteArray!


                try {
                    val outBuffer = ByteBuffer.wrap(payloadBuffer, 0, payloadLength)

                    // Цикл гарантирует, что пакет уйдет в TUN полностью, даже если запись разбилась
                    while (outBuffer.hasRemaining()) {
                        service.tunOutputChannel?.write(outBuffer)
                            ?: throw IOException("tunOutputChannel is null or closed")
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "CRASH during routing raw IP packet to TUN: ${e.message}")
                    // Если TUN отвалился, выходим из readerLoop для перезапуска VPN-сессии
                    if (e is IOException) break
                }

            } catch (e: EOFException) {
                Log.w(TAG, "🔌 Server closed the TLS connection cleanly (EOF).")
                delay(762)
                break
            } catch (e: SocketException) {
                Log.w(TAG, "🔌 TLS Socket disconnected/reset: ${e.message}")
                delay(762)
                break
            } catch (e: IOException) {
                Log.e(TAG, "💥 IOException in TLS reader loop: ${e.message}")
                delay(762)
                break
            } //catch (e: Exception) {
            //    Log.e(TAG, "❌ Unexpected error in TLS reader loop: ${e.message}")
            //    delay(762)
            //    break
            //}
        }
    }

}

