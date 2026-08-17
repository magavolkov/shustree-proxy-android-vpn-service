package ru.shustree.shustreeproxy.data.ip

import java.nio.ByteBuffer

// A namespace object for TCP-related constants
object TCPFlag {
    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10
    const val URG = 0x20
}


class TCPParser(private val buffer: ByteBuffer) {
    // We use absolute get() calls to avoid any side effects on the buffer's position.
    val sourcePort: Int get() = buffer.getShort(0).toInt() and 0xFFFF
    val destinationPort: Int get() = buffer.getShort(2).toInt() and 0xFFFF
    val sequenceNumber: Long get() = buffer.getInt(4).toLong() and 0xFFFFFFFFL
    val acknowledgementNumber: Long get() = buffer.getInt(8).toLong() and 0xFFFFFFFFL
    private val dataOffsetAndFlags: Int get() = buffer.getShort(12).toInt() and 0xFFFF
    val headerLength: Int get() = (dataOffsetAndFlags shr 12) * 4
    val payload: ByteBuffer
        get() {
            val payloadView = buffer.duplicate()
            payloadView.position(headerLength)
            return payloadView.slice()
        }

    val flags: Int get() = dataOffsetAndFlags and 0x01FF
    val isFIN: Boolean get() = (flags and TCPFlag.FIN) != 0
    val isSYN: Boolean get() = (flags and TCPFlag.SYN) != 0
    val isRST: Boolean get() = (flags and TCPFlag.RST) != 0
    val isPSH: Boolean get() = (flags and TCPFlag.PSH) != 0
    val isACK: Boolean get() = (flags and TCPFlag.ACK) != 0
}
