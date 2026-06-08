package com.simplelink.app

import android.content.Context
import android.content.Intent
import android.net.Uri

sealed class SharePayload {
    data class Text(val text: String) : SharePayload()
    data class Files(val uris: List<Uri>) : SharePayload()
}

object ShareIntentParser {
    fun parse(context: Context, intent: Intent?): SharePayload? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> parseSend(context, intent)
            Intent.ACTION_SEND_MULTIPLE -> parseSendMultiple(intent)
            else -> null
        }
    }

    private fun parseSend(context: Context, intent: Intent): SharePayload? {
        val stream = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        if (stream != null) {
            return SharePayload.Files(listOf(stream))
        }

        extractText(intent)?.let { return SharePayload.Text(it) }

        return extractFromClipData(context, intent)
    }

    private fun parseSendMultiple(intent: Intent): SharePayload? {
        val streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        if (!streams.isNullOrEmpty()) {
            return SharePayload.Files(streams)
        }
        return null
    }

    private fun extractText(intent: Intent): String? {
        val body = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)

        return when {
            !body.isNullOrBlank() && !subject.isNullOrBlank() ->
                "$subject\n$body".trim()
            !body.isNullOrBlank() -> body.trim()
            !subject.isNullOrBlank() -> subject.trim()
            else -> null
        }
    }

    private fun extractFromClipData(context: Context, intent: Intent): SharePayload? {
        val clip = intent.clipData ?: return null
        val uris = mutableListOf<Uri>()
        val texts = mutableListOf<String>()

        for (index in 0 until clip.itemCount) {
            val item = clip.getItemAt(index)
            item.uri?.let { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                uris.add(uri)
            }
            item.text?.toString()?.takeIf { it.isNotBlank() }?.let(texts::add)
        }

        if (uris.isNotEmpty()) return SharePayload.Files(uris.distinct())
        if (texts.isNotEmpty()) return SharePayload.Text(texts.joinToString("\n").trim())
        return null
    }
}
