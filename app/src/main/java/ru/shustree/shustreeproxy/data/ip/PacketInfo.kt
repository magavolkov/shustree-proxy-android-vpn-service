package ru.shustree.shustreeproxy.data.ip

import java.net.InetAddress

/**
 * A data class to hold the structured components of a network connection,
 * parsed from a raw IP packet.
 */
data class ConnectionInfo(
    val keyString: String,
    val protocol: Int,
    val sourceAddress: InetAddress,
    val sourcePort: Int,
    val destinationAddress: InetAddress,
    val destinationPort: Int,
    //val maskedDestinationAddress: InetAddress,
    val isMasked: Boolean
) {
}