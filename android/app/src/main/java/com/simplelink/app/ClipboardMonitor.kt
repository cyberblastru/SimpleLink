package com.simplelink.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper

class ClipboardMonitor(
    private val context: Context,
    private val onLocalChange: (String) -> Unit
) {
    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastSentText = ""

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        mainHandler.post { handleLocalChange() }
    }

    fun start() {
        lastSentText = readLocalText().orEmpty()
        mainHandler.post {
            clipboard.addPrimaryClipChangedListener(listener)
        }
    }

    fun stop() {
        mainHandler.post {
            clipboard.removePrimaryClipChangedListener(listener)
        }
    }

    fun poll() {
        mainHandler.post { handleLocalChange() }
    }

    fun applyRemoteText(text: String) {
        mainHandler.post {
            lastSentText = text
            clipboard.setPrimaryClip(ClipData.newPlainText("SimpleLink", text))
        }
    }

    private fun handleLocalChange() {
        val text = readLocalText()?.takeIf { it.isNotEmpty() } ?: return
        if (text == lastSentText) return
        lastSentText = text
        onLocalChange(text)
    }

    private fun readLocalText(): String? {
        if (!clipboard.hasPrimaryClip()) return null
        val clip = clipboard.primaryClip ?: return null
        for (index in 0 until clip.itemCount) {
            val text = clip.getItemAt(index).coerceToText(context)?.toString()?.trim()
            if (!text.isNullOrEmpty()) return text
        }
        return null
    }
}
