package com.simplelink.app

import android.content.Context

object PairingStore {
    private const val PREFS = "simplelink_pairing"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val KEY_TOKEN = "token"

    fun save(context: Context, pairing: PairingPayload) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HOST, pairing.host)
            .putInt(KEY_PORT, pairing.port)
            .putString(KEY_TOKEN, pairing.token)
            .apply()
    }

    fun load(context: Context): PairingPayload? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_HOST, null) ?: return null
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        val port = prefs.getInt(KEY_PORT, LinkProtocol.DEFAULT_PORT)
        return PairingPayload(v = 1, host = host, port = port, token = token)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
