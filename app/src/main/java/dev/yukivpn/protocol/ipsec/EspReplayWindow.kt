package dev.yukivpn.protocol.ipsec

internal class EspReplayWindow(private val windowSize: Int = 64) {
    private var highestSequence = 0u
    private var received = 0uL

    init {
        require(windowSize in 1..64)
    }

    fun accept(sequenceNumber: Int): Boolean {
        val sequence = sequenceNumber.toUInt()
        if (sequence == 0u) return false
        if (highestSequence == 0u) {
            highestSequence = sequence
            received = 1uL
            return true
        }
        if (sequence > highestSequence) {
            val shift = (sequence - highestSequence).toInt()
            received = if (shift >= windowSize) 1uL else (received shl shift) or 1uL
            highestSequence = sequence
            return true
        }
        val offset = (highestSequence - sequence).toInt()
        if (offset >= windowSize) return false
        val mask = 1uL shl offset
        if (received and mask != 0uL) return false
        received = received or mask
        return true
    }
}
