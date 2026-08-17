package ru.shustree.shustreeproxy.data.ip

import java.nio.ByteBuffer

/**
 * PARSER for a UDP packet.
 * Assumes the buffer passed to it is a UDP segment (header + payload).
 */
class UDPPacket(val buffer: ByteBuffer) {
    val sourcePort: Int
    val destinationPort: Int
    val length: Int
    val checksum: Int
    val payload: ByteBuffer

    init {
        // Duplicate to avoid modifying the original buffer's position
        val header = buffer.duplicate()
        sourcePort = header.getShort().toInt() and 0xFFFF
        destinationPort = header.getShort().toInt() and 0xFFFF
        length = header.getShort().toInt() and 0xFFFF
        checksum = header.getShort().toInt() and 0xFFFF
        payload = header // The rest of the buffer is the payload
    }
}


class UDPP(
    val sourcePort: Int,
    val destinationPort: Int,
    val payload: ByteBuffer
) {
    fun build(): ByteBuffer {
        val udpHeaderSize = 8
        val payloadSize = payload.remaining()
        val totalSize = udpHeaderSize + payloadSize
        val buffer = ByteBuffer.allocate(totalSize)

        buffer.putShort(sourcePort.toShort())
        buffer.putShort(destinationPort.toShort())
        buffer.putShort(totalSize.toShort()) // UDP Length
        buffer.putShort(0) // Checksum placeholder (optional for UDP)

        // UDP checksum is optional and often set to 0 in VPNs for performance.
        buffer.put(payload)
        buffer.flip()
        return buffer
    }
}