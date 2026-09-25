package com.example.clubmate.e2ee

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Stores each chat's encrypted [ChatStore] as a file in the app's no-backup directory (never copied
 * to cloud backups). Writes are atomic: a crash mid-write leaves the previous version intact.
 */
internal class FileStoreBackend(context: Context) : StoreBackend {

    private val dir = File(context.noBackupFilesDir, "e2ee").apply { mkdirs() }

    override fun read(name: String): ByteArray? = try {
        AtomicFile(File(dir, name)).readFully()
    } catch (e: FileNotFoundException) {
        null
    }

    override fun write(name: String, data: ByteArray) {
        val file = AtomicFile(File(dir, name))
        val out = file.startWrite()
        try {
            out.write(data)
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
            throw e
        }
    }

    override fun delete(name: String) {
        AtomicFile(File(dir, name)).delete()
    }
}
