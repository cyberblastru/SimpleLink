package com.simplelink.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

object TransferProgress {
    fun percent(done: Long, total: Long): Int {
        if (total <= 0) return 0
        return minOf(100, ((done * 100) / total).toInt())
    }

    fun sending(done: Long, total: Long): String =
        "${percent(done, total)}% — Sending…"

    fun receiving(done: Long, total: Long): String =
        "${percent(done, total)}% — Receiving…"
}

class BatchReceiveProgress {
    var batchTotal: Long = 0
        private set
    var batchOffset: Long = 0
        private set
    var currentFileReceived: Long = 0
        private set
    var lastPercent: Int = -1

    fun begin(batchTotal: Long, batchOffset: Long) {
        this.batchTotal = maxOf(batchTotal, 1)
        this.batchOffset = batchOffset
        currentFileReceived = 0
        lastPercent = -1
    }

    fun trackChunk(offset: Long, size: Int) {
        currentFileReceived = maxOf(currentFileReceived, offset + size)
    }

    val doneBytes: Long
        get() = minOf(batchOffset + currentFileReceived, batchTotal)

    fun currentPercent(): Int = TransferProgress.percent(doneBytes, batchTotal)
}

object PathUtils {
    fun sanitize(path: String): String =
        path.split('/')
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/")
}

class FileReceiver(private val context: Context) {
    private data class OpenTransfer(
        val relativePath: String,
        val tempFile: File
    )

    private val openFiles = mutableMapOf<String, OpenTransfer>()

    fun handleBegin(id: String, name: String, size: Long, relativePath: String?) {
        val rel = PathUtils.sanitize(relativePath ?: name)
        val safeName = File(rel).name
        val tempFile = File(context.cacheDir, "incoming-$id-$safeName")
        if (tempFile.exists()) tempFile.delete()
        tempFile.createNewFile()
        openFiles[id] = OpenTransfer(relativePath = rel, tempFile = tempFile)
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
        return publishToDownloads(entry.tempFile, entry.relativePath)
    }

    private fun publishToDownloads(source: File, relativePath: String): ReceivedFile? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishViaMediaStore(source, relativePath)
        } else {
            publishViaLegacyPath(source, relativePath)
        }
    }

    private fun publishViaMediaStore(source: File, relativePath: String): ReceivedFile? {
        val rel = PathUtils.sanitize(relativePath)
        val parts = rel.split('/')
        val fileName = parts.last()
        val subDir = parts.dropLast(1).joinToString("/")
        val relativeDir = if (subDir.isEmpty()) {
            "${Environment.DIRECTORY_DOWNLOADS}/SimpleLink"
        } else {
            "${Environment.DIRECTORY_DOWNLOADS}/SimpleLink/$subDir"
        }
        val resolver = context.contentResolver

        resolver.deleteExistingDownload(fileName, relativeDir)

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.RELATIVE_PATH, relativeDir)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
        } ?: return null

        resolver.update(uri, ContentValues().apply {
            put(MediaStore.Downloads.IS_PENDING, 0)
        }, null, null)
        source.delete()

        return ReceivedFile(
            name = fileName,
            path = "Download/SimpleLink/$rel"
        )
    }

    @Suppress("DEPRECATION")
    private fun publishViaLegacyPath(source: File, relativePath: String): ReceivedFile? {
        val rel = PathUtils.sanitize(relativePath)
        val target = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "SimpleLink/$rel"
        )
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()
        if (!source.renameTo(target)) {
            source.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            source.delete()
        }
        return ReceivedFile(
            name = target.name,
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
    fun send(
        file: ContextFile,
        output: DataOutputStream,
        batchTotal: Long,
        batchOffset: Long,
        onProgress: ((Long, Long) -> Unit)? = null
    ) {
        val id = java.util.UUID.randomUUID().toString()
        val begin = JSONObject()
            .put("id", id)
            .put("name", file.name)
            .put("size", file.size)
            .put("batchTotal", batchTotal)
            .put("batchOffset", batchOffset)
        if (file.relativePath != file.name) {
            begin.put("path", file.relativePath)
        }
        output.writeFrame(MessageType.FILE_BEGIN, LinkProtocol.jsonBytes(begin))

        file.openStream().use { input ->
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
                onProgress?.invoke(batchOffset + offset, batchTotal)
            }
        }

        output.writeFrame(MessageType.FILE_END, LinkProtocol.jsonBytes(JSONObject().put("id", id)))
    }
}

data class ContextFile(
    val name: String,
    val size: Long,
    val relativePath: String,
    val openStream: () -> InputStream
)

fun contextFilesFromUris(context: Context, uris: List<Uri>): List<ContextFile> {
    return uris.mapNotNull { contextFileFromUri(context, it) }
}

fun contextFilesFromTreeUri(context: Context, treeUri: Uri): List<ContextFile> {
    val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
    val rootName = root.name ?: "folder"
    val results = mutableListOf<ContextFile>()
    collectDocumentEntries(context, root, rootName, results)
    return results
}

private fun collectDocumentEntries(
    context: Context,
    file: DocumentFile,
    relativePath: String,
    out: MutableList<ContextFile>
) {
    if (file.isFile) {
        contextFileFromDocumentFile(context, file, relativePath)?.let(out::add)
        return
    }
    if (!file.isDirectory) return
    for (child in file.listFiles()) {
        val childName = child.name ?: continue
        val childPath = "$relativePath/$childName"
        collectDocumentEntries(context, child, childPath, out)
    }
}

private fun contextFileFromDocumentFile(
    context: Context,
    file: DocumentFile,
    relativePath: String
): ContextFile? {
    if (!file.isFile) return null
    val name = file.name ?: return null
    val rel = PathUtils.sanitize(relativePath)
    return ContextFile(
        name = name,
        size = file.length(),
        relativePath = rel,
        openStream = {
            context.contentResolver.openInputStream(file.uri)
                ?: error("Cannot open ${file.uri}")
        }
    )
}

fun contextFileFromUri(context: Context, uri: Uri): ContextFile? {
    if (DocumentsContract.isTreeUri(uri)) {
        return contextFilesFromTreeUri(context, uri).firstOrNull()
    }

    val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"

    val size = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (index >= 0 && cursor.moveToFirst()) cursor.getLong(index) else 0L
    } ?: 0L

    return ContextFile(
        name = name,
        size = size,
        relativePath = name,
        openStream = { context.contentResolver.openInputStream(uri) ?: error("Cannot open uri") }
    )
}
