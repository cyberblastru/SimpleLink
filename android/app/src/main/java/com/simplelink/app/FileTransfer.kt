package com.simplelink.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile

class FileReceiver(private val context: Context) {
    private data class OpenTransfer(
        val name: String,
        val tempFile: File
    )

    private val openFiles = mutableMapOf<String, OpenTransfer>()

    fun handleBegin(id: String, name: String, size: Long) {
        val safeName = File(name).name
        val tempFile = File(context.cacheDir, "incoming-$id-$safeName")
        if (tempFile.exists()) tempFile.delete()
        tempFile.createNewFile()
        openFiles[id] = OpenTransfer(name = safeName, tempFile = tempFile)
    }

    fun handleChunk(id: String, offset: Long, data: ByteArray) {
        val entry = openFiles[id] ?: return
        RandomAccessFile(entry.tempFile, "rw").use { raf ->
            raf.seek(offset)
            raf.write(data)
        }
    }

    fun handleEnd(id: String): ReceivedFile? {
        val entry = openFiles.remove(id) ?: return null
        return publishToDownloads(entry.tempFile, entry.name)
    }

    private fun publishToDownloads(source: File, name: String): ReceivedFile? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishViaMediaStore(source, name)
        } else {
            publishViaLegacyPath(source, name)
        }
    }

    private fun publishViaMediaStore(source: File, name: String): ReceivedFile? {
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/SimpleLink"
        val resolver = context.contentResolver

        resolver.deleteExistingDownload(name, relativePath)

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
        } ?: return null

        val published = ContentValues().apply {
            put(MediaStore.Downloads.IS_PENDING, 0)
        }
        resolver.update(uri, published, null, null)
        source.delete()

        return ReceivedFile(
            name = name,
            path = "Download/SimpleLink/$name"
        )
    }

    @Suppress("DEPRECATION")
    private fun publishViaLegacyPath(source: File, name: String): ReceivedFile? {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "SimpleLink"
        )
        if (!dir.exists()) dir.mkdirs()
        val target = File(dir, name)
        if (target.exists()) target.delete()
        if (!source.renameTo(target)) {
            source.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            source.delete()
        }
        return ReceivedFile(
            name = name,
            path = target.absolutePath
        )
    }

    private fun android.content.ContentResolver.deleteExistingDownload(
        name: String,
        relativePath: String
    ) {
        val selection = "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?"
        delete(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            selection,
            arrayOf(name, "$relativePath/")
        )
    }
}

data class ReceivedFile(
    val name: String,
    val path: String
)

class FileSender {
    fun send(uri: ContextFile, output: DataOutputStream) {
        val id = java.util.UUID.randomUUID().toString()
        val begin = JSONObject()
            .put("id", id)
            .put("name", uri.name)
            .put("size", uri.size)
        output.writeFrame(MessageType.FILE_BEGIN, LinkProtocol.jsonBytes(begin))

        uri.openStream().use { input ->
            val buffer = ByteArray(LinkProtocol.CHUNK_SIZE)
            var offset = 0L
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break

                val header = JSONObject()
                    .put("id", id)
                    .put("offset", offset)
                val headerBytes = LinkProtocol.jsonBytes(header)

                val payload = ByteArray(4 + headerBytes.size + read)
                val bb = java.nio.ByteBuffer.wrap(payload).order(java.nio.ByteOrder.BIG_ENDIAN)
                bb.putInt(headerBytes.size)
                bb.put(headerBytes)
                bb.put(buffer, 0, read)
                output.writeFrame(MessageType.FILE_CHUNK, payload)
                offset += read
            }
        }

        val end = JSONObject().put("id", id)
        output.writeFrame(MessageType.FILE_END, LinkProtocol.jsonBytes(end))
    }
}

data class ContextFile(
    val name: String,
    val size: Long,
    val openStream: () -> java.io.InputStream
)

fun contextFileFromUri(context: Context, uri: android.net.Uri): ContextFile? {
    val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"

    val size = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
        if (index >= 0 && cursor.moveToFirst()) cursor.getLong(index) else 0L
    } ?: 0L

    return ContextFile(
        name = name,
        size = size,
        openStream = { context.contentResolver.openInputStream(uri) ?: error("Cannot open uri") }
    )
}

fun contextFileFromFile(file: File): ContextFile? {
    if (!file.exists() || !file.isFile) return null
    return ContextFile(
        name = file.name,
        size = file.length(),
        openStream = { file.inputStream() }
    )
}
