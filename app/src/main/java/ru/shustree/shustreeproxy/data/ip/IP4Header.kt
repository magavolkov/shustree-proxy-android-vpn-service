package ru.shustree.shustreeproxy.data.ip

import java.nio.ByteBuffer

class IP4Header(
    val version: Int,
    val IHL: Int,
    val typeOfService: Int,
    var totalLength: Int,
    val identification: Int,
    val flags: Int,
    val fragmentOffset: Int,
    val ttl: Int,
    val protocol: Protocol,
    var headerChecksum: Int,
    val sourceAddress: ByteArray,
    val destinationAddress: ByteArray
) {
    fun put(buffer: ByteBuffer) {
        buffer.put(((version shl 4) or IHL).toByte())
        buffer.put(typeOfService.toByte())
        buffer.putShort(totalLength.toShort())
        buffer.putShort(identification.toShort())
        buffer.putShort(((flags shl 13) or fragmentOffset).toShort())
        buffer.put(ttl.toByte())
        buffer.put(protocol.number.toByte())
        buffer.putShort(headerChecksum.toShort())
        buffer.put(sourceAddress)
        buffer.put(destinationAddress)
    }
}
