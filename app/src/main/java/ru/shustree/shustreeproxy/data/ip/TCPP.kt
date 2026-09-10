package ru.shustree.shustreeproxy.data.ip

import java.nio.ByteBuffer

/**
 * A builder class for creating TCP packets (segments).
 * It is responsible for constructing the header, calculating the checksum,
 * and combining it with the payload into a final ByteBuffer.
 * 'P' can stand for Producer or Packet.
 */
class TCPP(
    // These are the existing parameters
    private val sourcePort: Int,
    private val destinationPort: Int,
    private val sequenceNumber: Long,
    private val acknowledgementNumber: Long,
    private val payload: ByteArray?,
    private val sourceAddress: ByteArray,
    private val destinationAddress: ByteArray,
    private val ipProtocol: Int,
    private val mss: Int? = null,

    // --- ADD THESE NEW PARAMETERS ---
    private val isSYN: Boolean = false,
    private val isACK: Boolean = false,
    private val isPSH: Boolean = false,
    private val isFIN: Boolean = false,
    private val isRST: Boolean = false
) {
    fun build(): ByteBuffer {
        // --- START OF REFACTORED LOGIC ---

        // 1. Determine Header Size and Data Offset
        // Standard header is 20 bytes (Offset 5).
        // With MSS Option (4 bytes), header is 24 bytes (Offset 6).
        val hasMss = mss != null
        val tcpHeaderSize = if (hasMss) 24 else 20
        val dataOffset = tcpHeaderSize / 4

        val payloadSize = payload?.size ?: 0
        val totalTcpSegmentSize = tcpHeaderSize + payloadSize

        // 2. Allocate the buffer for the entire TCP segment (header + payload).
        val packet = ByteBuffer.allocate(totalTcpSegmentSize)



        // 3. Populate the TCP header fields.
        packet.putShort(sourcePort.toShort())
        packet.putShort(destinationPort.toShort())
        packet.putInt(sequenceNumber.toInt())
        packet.putInt(acknowledgementNumber.toInt())

        // 4. Assemble the flags integer from the boolean parameters.
        var flags = 0
        if (isSYN) flags = flags or TCPFlag.SYN
        if (isACK) flags = flags or TCPFlag.ACK
        if (isPSH) flags = flags or TCPFlag.PSH
        if (isFIN) flags = flags or TCPFlag.FIN
        if (isRST) flags = flags or TCPFlag.RST

        // Data Offset (5 for a 20-byte header) + Flags
        //packet.putShort(((5 shl 12) or flags).toShort())
        packet.putShort(((dataOffset shl 12) or flags).toShort())

        packet.putShort(65535.toShort()) // Window size
        packet.putShort(0) // Checksum placeholder, will be filled later
        packet.putShort(0) // Urgent pointer

        // 5. Inject TCP Options if MSS is present
        if (hasMss) {
            packet.put(2.toByte())          // Kind: MSS
            packet.put(4.toByte())          // Length: 4 bytes
            packet.putShort(mss!!.toShort()) // Value (e.g., 1240)
        }

        // 5. Put the payload (if any) into the buffer.
        if (payload != null) {
            packet.put(payload)
        }

        // --- END OF REFACTORED LOGIC ---

        // 6. Prepare the packet for checksum calculation.
        packet.flip()

        // 7. Calculate the checksum on the complete segment.
        val checksum = calculateChecksum(packet)

        // 8. Put the calculated checksum into the correct position (offset 16).
        packet.putShort(16, checksum.toShort())

        // 9. Rewind the packet's position to 0 so the consumer (IPP) can read it from the start.
        packet.rewind()

        return packet
    }


    private fun calculateChecksum(tcpSegment: ByteBuffer): Int {
        val tcpLength = tcpSegment.remaining()


        tcpSegment.putShort(16, 0)
        tcpSegment.rewind() // Start from the very beginning of the TCP segment



        // 1. Create a temporary buffer for the pseudo-header to ensure correct byte order
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

        // 2. Calculate the sum
        var sum = 0

        // Sum the pseudo-header
        while (pseudoHeader.remaining() > 1) {
            sum += pseudoHeader.getShort().toInt() and 0xFFFF
        }
        // There should not be an odd byte in a correctly formed pseudo-header, but for safety:
        if (pseudoHeader.hasRemaining()) {
            sum += (pseudoHeader.get().toInt() and 0xFF) shl 8
        }

        // Sum the TCP header and payload from the original segment
        // The checksum field in the tcpSegment is already 0, which is correct for calculation.
        while (tcpSegment.remaining() > 1) {
            sum += tcpSegment.getShort().toInt() and 0xFFFF
        }
        if (tcpSegment.hasRemaining()) {
            sum += (tcpSegment.get().toInt() and 0xFF) shl 8 // Handle odd byte in payload
        }

        // 3. Fold the sum to 16 bits
        while (sum shr 16 > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }

        // 4. One's complement
        return sum.inv() and 0xFFFF
    }
}


