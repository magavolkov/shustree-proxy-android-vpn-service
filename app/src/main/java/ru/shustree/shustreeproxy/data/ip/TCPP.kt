package ru.shustree.shustreeproxy.data.ip

import java.nio.ByteBuffer


class TCPP(
    private val sourcePort: Int,
    private val destinationPort: Int,
    private val sequenceNumber: Long,
    private val acknowledgementNumber: Long,
    private val payload: ByteArray?,
    private val sourceAddress: ByteArray,
    private val destinationAddress: ByteArray,
    private val ipProtocol: Int,
    private val mss: Int? = null,
    private val isSYN: Boolean = false,
    private val isACK: Boolean = false,
    private val isPSH: Boolean = false,
    private val isFIN: Boolean = false,
    private val isRST: Boolean = false
) {
    fun build(): ByteBuffer {
        val hasMss = mss != null
        val tcpHeaderSize = if (hasMss) 24 else 20
        val dataOffset = tcpHeaderSize / 4

        val payloadSize = payload?.size ?: 0
        val totalTcpSegmentSize = tcpHeaderSize + payloadSize

        val packet = ByteBuffer.allocate(totalTcpSegmentSize)
        packet.putShort(sourcePort.toShort())
        packet.putShort(destinationPort.toShort())
        packet.putInt(sequenceNumber.toInt())
        packet.putInt(acknowledgementNumber.toInt())
        var flags = 0
        if (isSYN) flags = flags or TCPFlag.SYN
        if (isACK) flags = flags or TCPFlag.ACK
        if (isPSH) flags = flags or TCPFlag.PSH
        if (isFIN) flags = flags or TCPFlag.FIN
        if (isRST) flags = flags or TCPFlag.RST
        packet.putShort(((dataOffset shl 12) or flags).toShort())
        packet.putShort(65535.toShort()) // Window size
        packet.putShort(0) // Checksum placeholder, will be filled later
        packet.putShort(0) // Urgent pointer
        if (hasMss) {
            packet.put(2.toByte())          // Kind: MSS
            packet.put(4.toByte())          // Length: 4 bytes
            packet.putShort(mss!!.toShort()) // Value (e.g., 1240)
        }
        if (payload != null) {
            packet.put(payload)
        }
        packet.flip()
        val checksum = calculateChecksum(packet)
        packet.putShort(16, checksum.toShort())
        packet.rewind()
        return packet
    }


    private fun calculateChecksum(tcpSegment: ByteBuffer): Int {
        val tcpLength = tcpSegment.remaining()
        tcpSegment.putShort(16, 0)
        tcpSegment.rewind() // Start from the very beginning of the TCP segment
        val pseudoHeaderSize = if (ipProtocol == 4) 12 else 40 // IPv4 is 12, IPv6 is 40
        val pseudoHeader = ByteBuffer.allocate(pseudoHeaderSize)
        if (ipProtocol == 4) {
            pseudoHeader.put(sourceAddress)       // 4 bytes
            pseudoHeader.put(destinationAddress)  // 4 bytes
            pseudoHeader.put(0.toByte())          // 1 byte (reserved)
            pseudoHeader.put(Protocol.TCP.number.toByte()) // 1 byte (protocol)
            pseudoHeader.putShort(tcpLength.toShort())     // 2 bytes (TCP length)
        } else { // IPv6
            pseudoHeader.put(sourceAddress)       // 16 bytes
            pseudoHeader.put(destinationAddress)  // 16 bytes
            pseudoHeader.putInt(tcpLength)        // 4 bytes (TCP length)
            pseudoHeader.put(byteArrayOf(0, 0, 0)) // 3 bytes (reserved)
            pseudoHeader.put(Protocol.TCP.number.toByte()) // 1 byte (next header)
        }
        pseudoHeader.flip()
        var sum = 0
        while (pseudoHeader.remaining() > 1) {
            sum += pseudoHeader.getShort().toInt() and 0xFFFF
        }
        if (pseudoHeader.hasRemaining()) {
            sum += (pseudoHeader.get().toInt() and 0xFF) shl 8
        }
        while (tcpSegment.remaining() > 1) {
            sum += tcpSegment.getShort().toInt() and 0xFFFF
        }
        if (tcpSegment.hasRemaining()) {
            sum += (tcpSegment.get().toInt() and 0xFF) shl 8 // Handle odd byte in payload
        }
        while (sum shr 16 > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv() and 0xFFFF
    }
}


