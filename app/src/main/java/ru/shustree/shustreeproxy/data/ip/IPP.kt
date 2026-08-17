package ru.shustree.shustreeproxy.data.ip

import android.util.Log
import java.net.InetAddress
import java.nio.ByteBuffer



class IPP(
    private val sourceAddress: ByteArray,
    private val destinationAddress: ByteArray,
    private val tcpSegment: ByteBuffer
) {

    val l4Payload: ByteBuffer
        get() = tcpSegment

    fun build(): ByteBuffer {
        return if (sourceAddress.size == 4) {
            buildIpv4()
        } else {
            buildIpv6()
        }
    }

    private fun buildIpv4(): ByteBuffer {
        val tcpSize = l4Payload.limit()
        val ipHeaderSize = 20
        val totalPacketSize = ipHeaderSize + tcpSize
        val packet = ByteBuffer.allocate(totalPacketSize)
        packet.put((4 shl 4 or 5).toByte())
        packet.put(0) // DSCP/ECN
        packet.putShort(totalPacketSize.toShort())
        packet.putShort(0) // Identification
        packet.putShort(0x4000.toShort()) // Flags (DF bit set)
        packet.put(64.toByte()) // TTL
        packet.put(Protocol.TCP.number.toByte())
        packet.putShort(0) // Checksum placeholder
        packet.put(sourceAddress)
        packet.put(destinationAddress)
        packet.putShort(10, 0.toShort())
        var sum = 0
        for (i in 0 until ipHeaderSize step 2) {
            sum += packet.getShort(i).toInt() and 0xFFFF
        }
        while (sum shr 16 > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        val checksum = sum.inv().toShort()
        packet.putShort(10, checksum)
        packet.position(ipHeaderSize)
        l4Payload.rewind() // Ensure the TCP payload is read from its start.
        packet.put(l4Payload)
        packet.flip()
        return packet
    }

    private fun buildIpv6(): ByteBuffer {
        val ipHeaderSize = 40
        val finalPacket = ByteBuffer.allocate(ipHeaderSize + tcpSegment.remaining())
        val ipHeaderBuffer = ByteBuffer.allocate(ipHeaderSize)
        val versionAndTrafficClass = (6 shl 4) or (0 shr 4)

        ipHeaderBuffer.put(versionAndTrafficClass.toByte())

        val trafficClassAndFlowLabel = ((0 and 0x0F) shl 28) or 0

        ipHeaderBuffer.put((trafficClassAndFlowLabel shr 24).toByte())
        ipHeaderBuffer.putShort((trafficClassAndFlowLabel shr 8).toShort())
        ipHeaderBuffer.putShort(tcpSegment.remaining().toShort()) // Payload Length
        ipHeaderBuffer.put(Protocol.TCP.number.toByte()) // Next Header
        ipHeaderBuffer.put(64.toByte()) // Hop Limit
        ipHeaderBuffer.put(sourceAddress)
        ipHeaderBuffer.put(destinationAddress)
        ipHeaderBuffer.flip()

        finalPacket.put(ipHeaderBuffer)
        finalPacket.put(tcpSegment)
        finalPacket.flip()

        return finalPacket
    }


    companion object {

        val portMaskMap = mapOf(
            5222 to 17,
            3478 to 13
        )

        val portUnmaskMap = portMaskMap.entries.associate { (k, v) -> v to k }

        fun fromByteBuffer(fullPacketBuffer: ByteBuffer): IPP? { // Return nullable IPP
            if (fullPacketBuffer.remaining() < 1) {
                return null // Not enough data to even determine version
            }

            fullPacketBuffer.mark()
            try {
                val version = (fullPacketBuffer.get(0).toInt() and 0xF0) shr 4

                val source: ByteArray
                val destination: ByteArray
                val segment: ByteBuffer
                var currentOffset: Int

                when (version) {
                    4 -> {
                        if (fullPacketBuffer.remaining() < 20) return null // Not enough for IPv4 header
                        currentOffset = (fullPacketBuffer.get(0).toInt() and 0x0F) * 4


                        source = ByteArray(4).apply {
                            fullPacketBuffer.position(12)
                            fullPacketBuffer.get(this)
                        }
                        destination = ByteArray(4).apply {
                            fullPacketBuffer.position(16)
                            fullPacketBuffer.get(this)
                        }
                        segment = run {
                            val ipHeaderLength = (fullPacketBuffer.get(0).toInt() and 0x0F) * 4
                            fullPacketBuffer.position(ipHeaderLength)
                            fullPacketBuffer.slice()
                        }
                    }
                    6 -> {
                        if (fullPacketBuffer.remaining() < 40) return null // Not enough for IPv6 header

                        source = ByteArray(16).apply {
                            fullPacketBuffer.position(8)
                            fullPacketBuffer.get(this)
                        }
                        destination = ByteArray(16).apply {
                            fullPacketBuffer.position(24)
                            fullPacketBuffer.get(this)
                        }

                        // Determine where the payload starts by walking extension headers
                        var nextHeader = fullPacketBuffer.get(6).toInt() and 0xFF
                        currentOffset = 40

                        // Extension Headers to skip: 0 (Hop), 43 (Routing), 44 (Frag), 60 (Dest), 51 (AH)
                        while (nextHeader == 0 || nextHeader == 43 || nextHeader == 44 || nextHeader == 60 || nextHeader == 51) {
                            if (fullPacketBuffer.limit() < currentOffset + 8) return null

                            val extHeaderLen = if (nextHeader == 44) {
                                8 // Fragment header is fixed 8 bytes
                            } else {
                                // Other headers: length is in the 2nd byte, expressed in 8-byte units, excluding first 8
                                (fullPacketBuffer.get(currentOffset + 1).toInt() and 0xFF + 1) * 8
                            }

                            nextHeader = fullPacketBuffer.get(currentOffset).toInt() and 0xFF
                            currentOffset += extHeaderLen
                        }

                        if (fullPacketBuffer.limit() < currentOffset) return null

                        fullPacketBuffer.position(currentOffset)

                        segment = fullPacketBuffer.slice()
                    }
                    else -> {
                        return null
                    }
                }

                return IPP(
                    sourceAddress = source,
                    destinationAddress = destination,
                    tcpSegment = segment
                )
            } catch (e: Exception) {
                return null
            }
            finally {
                fullPacketBuffer.reset()
            }
        }


        private fun maskIp(ip: String): String {
            val digitMap = mapOf(
                '0' to '5', '1' to '4', '2' to '3', '3' to '2', '4' to '1',
                '5' to '0', '6' to '9', '7' to '8', '8' to '7', '9' to '6'
            )

            val masked = ip.map { char ->
                when {
                    char.isDigit() -> digitMap[char]
                    char == '.' -> '&'
                    else -> char
                }
            }.joinToString("")

            return "$$masked"
        }

        fun generateConnectionKey(buffer: ByteBuffer): ConnectionInfo? {
            if (buffer.remaining() < 4) {
                Log.d("IPP", "garbage caught")
                return null // Not enough data for version check
            }
            buffer.mark() // Save current position to restore it later

            try {
                val version = (buffer.get(0).toInt() and 0xF0) shr 4

                val protocol: Int
                val sourceIpStr: String
                val sourceIp: InetAddress
                val destIpStr: String
                var destIp: InetAddress
                val sourcePort: Int
                val destPort: Int
                var isMasked = false

                when (version) {
                    4 -> {
                        if (buffer.limit() < 20) return null // Not enough for a minimal IPv4 header

                        protocol = buffer.get(9).toInt() and 0xFF
                        if (protocol != 6 && protocol != 17) return null

                        val sourceIpBytes = ByteArray(4) { buffer.get(12 + it) }
                        val destIpBytes = ByteArray(4) { buffer.get(16 + it) }
                        sourceIp = InetAddress.getByAddress(sourceIpBytes)
                        destIp = InetAddress.getByAddress(destIpBytes)
                        sourceIpStr = sourceIp.hostAddress ?: return null
                        destIpStr = destIp.hostAddress ?: return null

                        isMasked = false

                        // TCP ports are after the IP header
                        val ipHeaderLength = (buffer.get(0).toInt() and 0x0F) * 4
                        if (buffer.limit() < ipHeaderLength + 4) return null // Not enough data for ports

                        sourcePort = buffer.getShort(ipHeaderLength).toInt() and 0xFFFF
                        destPort = buffer.getShort(ipHeaderLength + 2).toInt() and 0xFFFF

                    }
                    6 -> {
                        if (buffer.limit() < 40) return null

                        protocol = buffer.get(6).toInt() and 0xFF
                        if (protocol != 6 && protocol != 17) return null

                        val sourceIpBytes = ByteArray(16) { buffer.get(8 + it) }
                        val destIpBytes = ByteArray(16) { buffer.get(24 + it) }
                        sourceIp = InetAddress.getByAddress(sourceIpBytes)
                        destIp = InetAddress.getByAddress(destIpBytes)
                        sourceIpStr = sourceIp.hostAddress ?: return null
                        destIpStr = destIp.hostAddress ?: return null

                        val ipHeaderLength = 40
                        if (buffer.limit() < ipHeaderLength + 4) return null

                        sourcePort = buffer.getShort(ipHeaderLength).toInt() and 0xFFFF
                        destPort = buffer.getShort(ipHeaderLength + 2).toInt() and 0xFFFF
                    }
                    else -> {
                        return null
                    }
                }

                val keyString ="$protocol:$sourceIpStr:$sourcePort-$destIpStr:$destPort"

                return ConnectionInfo(
                    keyString = keyString,
                    protocol = protocol,
                    sourceAddress = sourceIp,
                    sourcePort = sourcePort,
                    destinationAddress = destIp,
                    destinationPort = destPort,
                    isMasked = isMasked
                )

            } catch (e: Exception) {
                Log.e("IPP", "Error parsing connection key", e)
                return null // Return null on any parsing error
            } finally {
                buffer.reset() // IMPORTANT: Restore buffer's original position
            }
        }


        fun maskPort(port: Int): Int {
            return portMaskMap[port] ?: port
        }

        fun unmaskPort(port: Int): Int {
            return portUnmaskMap[port] ?: port
        }

    }

}



