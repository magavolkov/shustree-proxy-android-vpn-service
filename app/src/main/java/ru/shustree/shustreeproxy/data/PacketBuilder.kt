package ru.shustree.shustreeproxy.data

import ru.shustree.shustreeproxy.data.ip.IPP
import ru.shustree.shustreeproxy.data.ip.TCPP
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * A singleton object responsible for constructing raw IP and TCP packets.
 * This object acts as a "director" that uses the IPP and TCPP builder classes.
 * It contains one primary, universal build method.
 */
object PacketBuilder {

    /**
     * The single, universal method for building any TCP/IP packet.
     * All components are passed directly as parameters, eliminating the need
     * for parsing or state management within the builder.
     *
     * Note: The source/destination are from the perspective of the packet being built.
     * For a reply packet, the service's destination becomes the packet's source.
     */
    fun build(
        sourceAddress: InetAddress,
        sourcePort: Int,
        destinationAddress: InetAddress,
        destinationPort: Int,
        sequenceNumber: Long,
        acknowledgementNumber: Long,
        isSYN: Boolean = false,
        isACK: Boolean = false,
        isPSH: Boolean = false,
        isFIN: Boolean = false,
        isRST: Boolean = false,
        payload: ByteArray? = null, // Payload is optional
        mss: Int? = null
    ): ByteBuffer {

        // --- 1. Build the TCP Segment using TCPP with the new boolean flags ---
        val tcpSegment = TCPP(
            sourcePort = sourcePort,
            destinationPort = destinationPort,
            sourceAddress = sourceAddress.address,
            destinationAddress = destinationAddress.address,
            sequenceNumber = sequenceNumber,
            acknowledgementNumber = acknowledgementNumber,
            // Pass the boolean flags directly
            isSYN = isSYN,
            isACK = isACK,
            isPSH = isPSH,
            isFIN = isFIN,
            isRST = isRST,
            payload = payload,
            mss = mss,
            ipProtocol = if (sourceAddress is java.net.Inet4Address) 4 else 6
        ).build()

        // --- 2. Build the final IP Packet using IPP ---
        val finalPacket = IPP(
            sourceAddress = sourceAddress.address,
            destinationAddress = destinationAddress.address,
            tcpSegment = tcpSegment
        ).build()

        return finalPacket
    }
}