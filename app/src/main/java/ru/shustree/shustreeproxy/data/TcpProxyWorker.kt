package ru.shustree.shustreeproxy.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
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

    private val TLS_SNI_HOST = proxyHost

    private var retryDelayMs = 100L
    private val MAX_RETRY_DELAY_MS = 7762L





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


    fun stop() {
        Log.w(TAG, "Stopping worker...")
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

                service.notifyTcpActivityTx()
                val socket = connectAndTlsHandshake()
                    ?: throw IOException("Failed to establish TLS handshake (socket is null)")

                activeSocket = socket
                tlsSocket.set(socket)

                Log.i(TAG, "🔓 TLS session established with $TLS_SNI_HOST")
                isTunnelReady.set(true)
                coroutineScope {
                    val outputStream = socket.getOutputStream()

                    launch(Dispatchers.IO) {
                        try {
                            readerLoop(socket)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.d(TAG, "Reader loop exited: ${e.message}")
                        } finally {
                            closeSocketQuietly(socket)
                        }
                    }

                    launch(Dispatchers.IO) {
                        try {
                            writerLoop(outputStream)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.d(TAG, "Writer loop exited: ${e.message}")
                        } finally {
                            closeSocketQuietly(socket)
                        }
                    }
                }

                Log.i(TAG, "TLS loops finished cleanly. Reconnecting...")

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

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, null, null)
        val sslFactory = sslContext.socketFactory

        val sslSocket = sslFactory.createSocket(rawSocket, TLS_SNI_HOST, proxyPort, true) as SSLSocket
        val sslParams = sslSocket.sslParameters
        sslParams.serverNames = listOf(SNIHostName(TLS_SNI_HOST))
        sslParams.endpointIdentificationAlgorithm = "HTTPS"
        sslSocket.sslParameters = sslParams
        sslSocket.startHandshake()
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
                val packet = service.deviceToNetworkChannel.receive()

                service.notifyTcpActivityTx()

                val totalBytesToSend = packet.remaining()
                withContext(Dispatchers.IO) {
                    if (packet.hasArray()) {
                        outputStream.write(
                            packet.array(),
                            packet.arrayOffset() + packet.position(),
                            totalBytesToSend
                        )
                    } else {
                        val tempArray = ByteArray(totalBytesToSend)
                        packet.duplicate().get(tempArray)
                        outputStream.write(tempArray)
                    }
                    outputStream.flush()
                }
            }
            Log.d(TAG, "[WRITER LOOP] Loop finished naturally because workerScope.isActive = false")
        } catch (e: CancellationException) {
            Log.w(TAG, "[WRITER LOOP] Scope/Job was CANCELLED (Doze/Reconnect/User stop): ${e.message}", e)
            throw e // Обязательно пробрасываем для корректной отмены sibling-корутин
        } catch (e: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
            Log.w(TAG, "[WRITER LOOP] deviceToNetworkChannel was CLOSED: ${e.message}")
        } catch (e: java.net.SocketException) {
            Log.e(TAG, "[WRITER LOOP] Socket Exception (Peer reset / Closed): ${e.message}")
        } catch (e: java.io.IOException) {
            Log.e(TAG, "[WRITER LOOP] I/O Exception during write/flush: ${e.message}")
        } catch (e: Throwable) {
            Log.e(TAG, "[WRITER LOOP] Unexpected error: ${e.javaClass.simpleName} - ${e.message}", e)
        }
    }




    private suspend fun readerLoop(socket: Socket) {
        val handler = NetworkToDeviceHandler()
        val stream = DataInputStream(socket.getInputStream())

        Log.d(TAG, "📥 [READER LOOP] Started with 5-byte framing")

        val headerBuffer = ByteArray(5)
        val headerByteBuffer = ByteBuffer.wrap(headerBuffer)

        val maxExpectedPayloadSize = 1500
        val payloadBuffer = ByteArray(maxExpectedPayloadSize)

        while (workerScope.isActive && !socket.isClosed) {
            try {
                stream.readFully(headerBuffer)
                service.notifyTcpActivityRx()

                headerByteBuffer.clear()
                val packetType = headerByteBuffer.get().toInt() and 0xFF
                val payloadLength = headerByteBuffer.getInt()
                if (payloadLength < 20 || payloadLength > maxExpectedPayloadSize) {
                    Log.e(TAG, "❌ Invalid or unexpected payload length: $payloadLength bytes (Max: $maxExpectedPayloadSize). Stream desync!")
                    break
                }
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

                stream.readFully(payloadBuffer, 0, payloadLength)
                handler.processNewData(ByteBuffer.wrap(payloadBuffer, 0, payloadLength))

            } catch (e: EOFException) {
                Log.w(TAG, "🔌 Server closed the TLS connection cleanly (EOF).")
                break
            } catch (e: SocketException) {
                Log.w(TAG, "🔌 TLS Socket disconnected/reset: ${e.message}")
                delay(762)
                //break
            } catch (e: IOException) {
                Log.e(TAG, "💥 IOException in TLS reader loop: ${e.message}")
                delay(762)
                //break
            }
        }
    }





    // Упрощенный обработчик: просто пробрасывает сырой IP-пакет в устройства/TUN
    private inner class NetworkToDeviceHandler {
        suspend fun processNewData(packetBuffer: ByteBuffer) {
            try {
                val sendResult = service.highPriorityToDeviceChannel.trySend(packetBuffer)
                if (!sendResult.isSuccess) {
                    service.highPriorityToDeviceChannel.send(packetBuffer)
                }
            } catch (e: Exception) {
                Log.e(TAG, "CRASH during routing raw IP packet to TUN: ${e.message}")
            }
        }
    }
}

