// path: app/src/main/java/ru/shustree/safeproxy/data/ip/TCPHeader.kt
package ru.shustree.shustreeproxy.data.ip

import java.nio.ByteBuffer


class TCPHeader(private val buffer: ByteBuffer) {


    val sourcePort: Int get() = buffer.getShort(0).toInt() and 0xFFFF
    val destinationPort: Int get() = buffer.getShort(2).toInt() and 0xFFFF
    val sequenceNumber: Long get() = buffer.getInt(4).toLong() and 0xFFFFFFFFL
    val acknowledgementNumber: Long get() = buffer.getInt(8).toLong() and 0xFFFFFFFFL

    private val dataOffsetAndFlags: Int get() = buffer.getShort(12).toInt() and 0xFFFF

    val windowSize: Int get() = buffer.getShort(14).toInt() and 0xFFFF
    val checksum: Int get() = buffer.getShort(16).toInt() and 0xFFFF
    val urgentPointer: Int get() = buffer.getShort(18).toInt() and 0xFFFF

    /** The length of the TCP header in bytes. */
    val headerLength: Int get() = (dataOffsetAndFlags shr 12) * 4

    /** The raw integer value of the TCP flags. */
    val flags: Int get() = dataOffsetAndFlags and 0x01FF // Mask for the 9 flag bits

    /**
     * Returns a ByteBuffer slice representing the TCP payload.
     * This is a zero-copy operation.
     */
    val payload: ByteBuffer
        get() {
            val payloadView = buffer.duplicate()
            // The payload starts after the header.
            payloadView.position(headerLength)
            return payloadView.slice()
        }

    // Flag properties for convenience. These are already present in ShustreeVpnService,
    // but having them here makes the parser complete.
    val isFIN: Boolean get() = (flags and FIN) > 0
    val isSYN: Boolean get() = (flags and SYN) > 0
    val isRST: Boolean get() = (flags and RST) > 0
    val isPSH: Boolean get() = (flags and PSH) > 0
    val isACK: Boolean get() = (flags and ACK) > 0
    val isURG: Boolean get() = (flags and URG) > 0

    companion object {
        const val FIN = 0x01
        const val SYN = 0x02
        const val RST = 0x04
        const val PSH = 0x08
        const val ACK = 0x10
        const val URG = 0x20
    }
}
