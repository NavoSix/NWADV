package com.northwoodsadv.ride.data

import java.io.File

/** Write-once file cache so rides work offline after the first load. */
class DiskCache(private val root: File) {
    fun file(path: String) = File(root, path)

    fun read(path: String): ByteArray? = file(path).takeIf { it.isFile && it.length() > 0 }?.readBytes()

    fun write(path: String, bytes: ByteArray) {
        val f = file(path)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".part")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** Returns cached bytes, or fetches, stores and returns them. */
    fun getOrFetch(path: String, fetch: () -> ByteArray): ByteArray =
        read(path) ?: fetch().also { write(path, it) }
}
