package com.simplelink.app

import android.app.Application

class SimpleLinkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LinkSession.init(this)
    }
}
