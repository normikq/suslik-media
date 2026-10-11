package ru.suslikovanora.media.net
object Api { val plain = okhttp3.OkHttpClient.Builder().readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build() }
