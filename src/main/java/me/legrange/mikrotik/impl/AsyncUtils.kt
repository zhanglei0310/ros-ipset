package me.legrange.mikrotik.impl

import me.legrange.mikrotik.ApiConnectionException
import java.io.IOException
import java.io.InputStream
import java.lang.StringBuilder
import java.nio.charset.Charset
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

object AsyncUtil {
    fun hexStrToStr(s: String): String {
        val ret: StringBuilder = StringBuilder()
        var i = 0
        while (i < s.length) {
            ret.append(s.substring(i, i + 2).toInt(16).toChar())
            i += 2
        }
        return ret.toString()
    }

    fun hashMD5(s: String): String {
        var algorithm: MessageDigest = try {
            MessageDigest.getInstance("MD5")
        } catch (nsae: NoSuchAlgorithmException) {
            throw ApiDataException("Cannot find MD5 digest algorithm")
        }
        val defaultBytes = ByteArray(s.length)
        for (i in 0 until s.length) {
            defaultBytes[i] = (0xFF and s.get(i).toInt()).toByte()
        }
        algorithm.reset()
        algorithm.update(defaultBytes)
        val messageDigest = algorithm.digest()
        val hexString = StringBuilder()
        for (i in messageDigest.indices) {
            val hex = Integer.toHexString(0xFF and messageDigest[i].toInt())
            if (hex.length == 1) {
                hexString.append('0')
            }
            hexString.append(hex)
        }
        return hexString.toString()
    }

    fun write(cmd: Command, baos: ByteArrayOutputStream) {

    }

    fun decode(buf: ByteArray): String {
        try {
            val buf = ByteArray(len)
            for (i in 0 until len) {
                val c: Int = `in`.read()
                if (c < 0) {
                    throw ApiDataException("Truncated data. Expected to read more bytes")
                }
                buf[i] = (c and 0xFF).toByte()
            }
            val res = String(buf, Charset.forName("UTF-8"))
            if (result.length > 0) {
                result.append("\n")
            }
            result.append(res)
            Util.decode(`in`, result)
        } catch (ex: IOException) {
            throw ApiConnectionException(ex.message, ex)
        }
    }


}