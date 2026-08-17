package ru.shustree.shustreeproxy.data.ip

/**
 * A simple enum to represent IP protocol numbers.
 * This replaces the nested class that was in the old IPPacket.kt.
 */
enum class Protocol(val number: Int) {
    TCP(6),
    UDP(17),
    ICMP(1);

    companion object {
        fun fromNumber(number: Int): Protocol? {
            return entries.find { it.number == number }
        }
    }
}