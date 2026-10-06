package com.example.ui.viewmodel

import java.util.Locale

object PwdGenerator {
    private fun hexToBytes(s: String): ByteArray {
        var sanitized = s.filter { it in "0123456789ABCDEFabcdef" }
        // Guard against odd-length input to avoid IndexOutOfBounds on the last pair.
        if (sanitized.length % 2 != 0) {
            sanitized += "0"
        }
        val len = sanitized.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            val high = Character.digit(sanitized[i], 16)
            val low = Character.digit(sanitized[i + 1], 16)
            if (high != -1 && low != -1) {
                data[i / 2] = ((high shl 4) + low).toByte()
            }
            i += 2
        }
        return data
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = java.lang.StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02X", b))
        }
        return sb.toString()
    }

    fun generateDefaultXor(uidHex: String): String {
        val uid = hexToBytes(uidHex)
        if (uid.size < 7) return "00000000"
        val pwd = ByteArray(4)
        pwd[0] = (uid[0].toInt() xor uid[3].toInt() xor uid[6].toInt()).toByte()
        pwd[1] = (uid[1].toInt() xor uid[4].toInt()).toByte()
        pwd[2] = (uid[2].toInt() xor uid[5].toInt()).toByte()
        pwd[3] = (pwd[0].toInt() xor pwd[1].toInt() xor pwd[2].toInt()).toByte()
        return bytesToHex(pwd)
    }

    fun generateSalto(uidHex: String): String {
        val uid = hexToBytes(uidHex)
        if (uid.size < 7) return "00000000"
        val u = uid.map { it.toInt() and 0xFF }
        val pos = (u[3] xor u[4] xor u[5] xor u[6]) % 32
        val xortable = longArrayOf(
            0x4f2711c1L, 0x07D7BB83L, 0x9636EF07L, 0xB5F4460EL, 0xF271141CL, 0x7D7BB038L, 0x636EF871L, 0x5F4468E3L,
            0x271149C7L, 0xD7BB0B8FL, 0x36EF8F1EL, 0xF446863DL, 0x7114947AL, 0x7BB0B0F5L, 0x6EF8F9EBL, 0x44686BD7L,
            0x11494fAFL, 0xBB0B075FL, 0xEF8F96BEL, 0x4686B57CL, 0x1494F2F9L, 0xB0B07DF3L, 0xF8F963E6L, 0x686B5FCCL,
            0x494F2799L, 0x0B07D733L, 0x8F963667L, 0x86B5F4CEL, 0x94F2719CL, 0xB07D7B38L, 0xF9636E70L, 0x6B5F44E0L
        )
        val valL = xortable[pos]
        val entry = byteArrayOf(
            ((valL ushr 24) and 0xFF).toByte(),
            ((valL ushr 16) and 0xFF).toByte(),
            ((valL ushr 8) and 0xFF).toByte(),
            (valL and 0xFF).toByte()
        )
        
        val pwd = ByteArray(4)
        pwd[0] = (entry[0].toInt() xor u[1] xor u[2] xor u[3]).toByte()
        pwd[1] = (entry[1].toInt() xor u[0] xor u[2] xor u[4]).toByte()
        pwd[2] = (entry[2].toInt() xor u[0] xor u[1] xor u[5]).toByte()
        pwd[3] = (entry[3].toInt() xor u[6]).toByte()
        return bytesToHex(pwd)
    }

    fun generateAmiibo(uidHex: String): String {
        val uid = hexToBytes(uidHex)
        if (uid.size < 7) return "00000000"
        val pwd = ByteArray(4)
        pwd[0] = (uid[1].toInt() xor uid[3].toInt() xor 0xAA).toByte()
        pwd[1] = (uid[2].toInt() xor uid[4].toInt() xor 0x55).toByte()
        pwd[2] = (uid[3].toInt() xor uid[5].toInt() xor 0xAA).toByte()
        pwd[3] = (uid[4].toInt() xor uid[6].toInt() xor 0x55).toByte()
        return bytesToHex(pwd)
    }

    fun generateLego(uidHex: String): String {
        val uidBytes = hexToBytes(uidHex)
        var pwd = 0L
        val base = longArrayOf(
            0xffffffffL, 0x28ffffffL,
            0x43202963L, 0x7279706fL,
            0x74686769L, 0x47454c20L,
            0x3032204fL, 0xaaaa3431L
        )
        val baseBytes = java.nio.ByteBuffer.allocate(32).apply {
            order(java.nio.ByteOrder.LITTLE_ENDIAN)
            base.forEach { putInt(it.toInt()) }
        }.array()
        
        val uidLen = uidBytes.size.coerceAtMost(7)
        System.arraycopy(uidBytes, 0, baseBytes, 0, uidLen)
        
        val updatedBase = java.nio.ByteBuffer.wrap(baseBytes).apply {
            order(java.nio.ByteOrder.LITTLE_ENDIAN)
        }
        val finalBase = LongArray(8) {
            updatedBase.getInt().toLong() and 0xFFFFFFFFL
        }
        
        for (i in 0 until 8) {
            val term1 = finalBase[i]
            val rotr25 = ((pwd ushr 25) or (pwd shl (32 - 25))) and 0xFFFFFFFFL
            val rotr10 = ((pwd ushr 10) or (pwd shl (32 - 10))) and 0xFFFFFFFFL
            pwd = (term1 + rotr25 + rotr10 - pwd) and 0xFFFFFFFFL
        }
        
        val swapped = ((pwd and 0xFFL) shl 24) or
                      ((pwd and 0xFF00L) shl 8) or
                      ((pwd and 0xFF0000L) ushr 8) or
                      ((pwd and 0xFF000000L) ushr 24)
        
        val resultBytes = ByteArray(4)
        resultBytes[0] = ((swapped ushr 24) and 0xFFL).toByte()
        resultBytes[1] = ((swapped ushr 16) and 0xFFL).toByte()
        resultBytes[2] = ((swapped ushr 8) and 0xFFL).toByte()
        resultBytes[3] = (swapped and 0xFFL).toByte()
        return bytesToHex(resultBytes)
    }

    fun generateXiaomi(uidHex: String): String {
        val uidBytes = hexToBytes(uidHex)
        try {
            val md = java.security.MessageDigest.getInstance("SHA-1")
            val hash = md.digest(uidBytes.copyOf(7))
            val h0 = hash[0].toInt() and 0xFF
            val p0 = hash[h0 % 20].toInt() and 0xFF
            val p1 = hash[(h0 + 5) % 20].toInt() and 0xFF
            val p2 = hash[(h0 + 13) % 20].toInt() and 0xFF
            val p3 = hash[(h0 + 17) % 20].toInt() and 0xFF
            return bytesToHex(byteArrayOf(p0.toByte(), p1.toByte(), p2.toByte(), p3.toByte()))
        } catch(e: Exception) {
            return "00000000"
        }
    }

    fun generateNdef(uidHex: String): String {
        val uidBytes = hexToBytes(uidHex)
        try {
            val md = java.security.MessageDigest.getInstance("MD5")
            val hash = md.digest(uidBytes.copyOf(7))
            return bytesToHex(byteArrayOf(hash[0], hash[1], hash[2], hash[3]))
        } catch(e: Exception) {
            return "00000000"
        }
    }
}
