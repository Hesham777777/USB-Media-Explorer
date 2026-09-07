package com.usbmediaexplorer.util

import java.security.MessageDigest

/** Stable hashing helpers used for cache keys. */
object Hashing {

    fun md5Hex(input: String): String = digest("MD5", input.toByteArray(Charsets.UTF_8))

    fun sha1Hex(input: String): String = digest("SHA-1", input.toByteArray(Charsets.UTF_8))

    private fun digest(algorithm: String, bytes: ByteArray): String {
        val md = MessageDigest.getInstance(algorithm)
        val out = md.digest(bytes)
        val sb = StringBuilder(out.size * 2)
        for (b in out) {
            val hex = Integer.toHexString(b.toInt() and 0xFF)
            if (hex.length == 1) sb.append('0')
            sb.append(hex)
        }
        return sb.toString()
    }
}
