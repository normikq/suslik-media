package ru.suslikovanora.media

import android.app.Application

class SuslikApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Strings.init(this)
        ru.suslikovanora.media.library.CloudSync.init(this) // прогресс и дорожки по аккаунту Discord
        Tv.isTv(this) // the room's client id says «tv» or «an» (party/Party.kt)
    }
}
