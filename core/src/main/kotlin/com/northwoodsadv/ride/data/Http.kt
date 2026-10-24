package com.northwoodsadv.ride.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Minimal blocking HTTP client (call from a worker thread only). */
object Http {
    const val USER_AGENT = "NorthwoodsADV-Ride/0.1 (motorcycle ride simulator; github.com/navosix/nwadv)"

    fun get(url: String, timeoutMs: Int = 20_000): ByteArray = request(url, null, timeoutMs)

    fun postForm(url: String, fields: Map<String, String>, timeoutMs: Int = 120_000): ByteArray {
        val body = fields.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        return request(url, body.toByteArray(Charsets.UTF_8), timeoutMs)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun request(url: String, body: ByteArray?, timeoutMs: Int): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs.coerceAtMost(20_000)
            c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", USER_AGENT)
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code from ${URL(url).host}")
            return c.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                out.toByteArray()
            }
        } finally {
            c.disconnect()
        }
    }
}
