package ru.suslikovanora.media

import android.app.Application

class SuslikApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Strings.init(this)
    }
}
