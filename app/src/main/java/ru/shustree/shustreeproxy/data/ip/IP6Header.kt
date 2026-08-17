package ru.shustree.shustreeproxy.data.ip

import java.nio.ByteBuffer

class IP6Header(
    val version: Int,
    val trafficClass: Int,
    val flowLabel: Int,
    var payloadLength: Int,
    val nextHeader: Byte,
    val hopLimit: Int,
    val sourceAddress: ByteArray,
    val destinationAddress: ByteArray
) {
    fun put(buffer: ByteBuffer) {
        val versionAndTrafficClass = (version shl 4) or (trafficClass shr 4)
        buffer.put(versionAndTrafficClass.toByte())

        val trafficClassAndFlowLabel = ((trafficClass and 0x0F) shl 28) or flowLabel
        buffer.put((trafficClassAndFlowLabel shr 24).toByte())
        buffer.putShort((trafficClassAndFlowLabel shr 8).toShort())

        buffer.putShort(payloadLength.toShort())
        buffer.put(nextHeader)
        buffer.put(hopLimit.toByte())
        buffer.put(sourceAddress)
        buffer.put(destinationAddress)
    }
}
