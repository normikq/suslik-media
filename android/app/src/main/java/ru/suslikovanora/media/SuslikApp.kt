package ru.suslikovanora.media

import android.app.Application

class SuslikApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Strings.init(this)
        // Before anything touches Api.client: built earlier, it would stay without its disk cache for the whole process
        ru.suslikovanora.media.net.Api.init(this)
        ru.suslikovanora.media.library.CloudSync.init(this) // прогресс и дорожки по аккаунту Discord
        Tv.isTv(this) // the room's client id says «tv» or «an» (party/Party.kt)
    }
}
