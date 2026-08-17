import java.net.InetAddress
import java.nio.ByteBuffer
import kotlin.experimental.inv

/**
 * A specialized builder to create a valid IP packet containing a UDP datagram.
 */
object UDPInIPPacketBuilder {
    fun build(
        sourceAddress: InetAddress,
        sourcePort: Int,
        destinationAddress: InetAddress,
        destinationPort: Int,
        payload: ByteArray
    ): ByteBuffer {
        val ipHeaderLength = 20
        val udpHeaderLength = 8
        val ipPacketLength = ipHeaderLength + udpHeaderLength + payload.size

        // Allocate buffer for the entire IP packet
        val buffer = ByteBuffer.allocate(ipPacketLength)

        // --- IP Header (20 bytes) ---
        buffer.put(0x45.toByte()) // Version 4, Header Length 5 (20 bytes)
        buffer.put(0.toByte()) // Differentiated Services Field
        buffer.putShort(ipPacketLength.toShort()) // Total Length
        buffer.putShort(0) // Identification (can be 0)
        buffer.putShort(0x4000.toShort()) // Flags (Don't Fragment)
        buffer.put(64.toByte()) // TTL (Time To Live)
        buffer.put(17.toByte()) // Protocol: 17 for UDP
        buffer.putShort(0) // <<<<<< Placeholder for Header Checksum
        buffer.put(sourceAddress.address)
        buffer.put(destinationAddress.address)

        // --- UDP Header (8 bytes) ---
        buffer.putShort(sourcePort.toShort()) // Source Port
        buffer.putShort(destinationPort.toShort()) // Destination Port
        val udpLength = (udpHeaderLength + payload.size).toShort()
        buffer.putShort(udpLength) // UDP Length
        buffer.putShort(0) // UDP checksum (0 is valid in IPv4)

        // --- Payload ---
        buffer.put(payload)

        // --- Checksum Calculation ---
        buffer.rewind() // Go back to the start of the buffer
        val ipHeaderForChecksum = ByteArray(ipHeaderLength)
        buffer.get(ipHeaderForChecksum) // Read the IP header bytes we just wrote
        val ipChecksum = calculateIpChecksum(ipHeaderForChecksum)
        buffer.putShort(10, ipChecksum) // Put the correct checksum at byte 10

        // Prepare buffer for reading/writing from the start
        buffer.rewind()
        return buffer
    }

    /**
     * Calculates the IPv4 header checksum.
     * The checksum field itself (at offset 10) must be zero during calculation.
     */
    private fun calculateIpChecksum(header: ByteArray): Short {
        var sum = 0
        var i = 0
        // The header is 20 bytes long. Sum 16-bit words.
        while (i < header.size) {
            // Combine two bytes into a 16-bit word
            sum += ((header[i].toInt() and 0xFF) shl 8) or (header[i + 1].toInt() and 0xFF)
            i += 2
        }

        // Fold 32-bit sum to 16 bits: add carrier to result
        while (sum shr 16 > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }

        // Return one's complement of sum
        return sum.toShort().inv()
    }
}
