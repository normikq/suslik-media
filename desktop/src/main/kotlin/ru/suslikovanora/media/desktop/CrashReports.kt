package ru.suslikovanora.media.desktop

import ru.suslikovanora.media.core.*
import java.io.File

/**
 * Reports that send themselves to the server (POST /api/app/report), so a problem is seen without anyone having to
 * describe it:
 *  - the app ended abnormally last time (crash of mpv / the JVM — nothing could be sent then): at the next start,
 *    with the end of the player journal and the JVM's crash file, if any;
 *  - an exception nobody caught: right away;
 *  - the playback supervisor had to reopen a film: the journal around it (at most once per 10 minutes).
 */
object CrashReports {
    private val marker get() = AppDir.file("running.flag")
    @Volatile private var lastRecoveryReport = 0L

    /** At app start, before anything else. */
    fun start() {
        val crashedBefore = marker.exists()
        runCatching { marker.writeText(System.currentTimeMillis().toString()) }
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            PlayerLog.write("UNCAUGHT in ${t.name}: ${e.stackTraceToString().take(4000)}")
            send("exception", "Thread ${t.name}\n${e.stackTraceToString()}\n\n--- journal ---\n${journalTail()}", now = true)
        }
        val jvmCrash = jvmCrashFiles()
        if (crashedBefore || jvmCrash.isNotEmpty()) {
            val text = buildString {
                append(if (jvmCrash.isNotEmpty()) "The JVM crashed last time.\n" else "The app did not close normally last time (crash or killed).\n")
                for (f in jvmCrash) { append("\n--- ${f.name} ---\n"); append(runCatching { f.readText().take(60_000) }.getOrDefault("")) }
                append("\n\n--- journal ---\n"); append(journalTail(file = AppDir.file("player.log")))
            }
            send(if (jvmCrash.isNotEmpty()) "jvm-crash" else "unclean-exit", text)
            jvmCrash.forEach { runCatching { it.delete() } }
        }
    }

    /** Clean exit: the next start must not report a crash. */
    fun stop() { runCatching { marker.delete() } }

    /** The supervisor had to reopen a film (rate-limited). */
    fun recovery(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastRecoveryReport < 10 * 60_000L) return
        lastRecoveryReport = now
        // a moment later, so the journal also shows how the recovery went
        Thread({ runCatching { Thread.sleep(20_000) }; send("recovery", "$reason\n\n--- journal ---\n${journalTail()}") }, "report").apply { isDaemon = true; start() }
    }

    private fun journalTail(file: File = PlayerLog.file, max: Int = 60_000): String = runCatching {
        val t = file.readText(); if (t.length > max) "…" + t.takeLast(max) else t
    }.getOrDefault("(no journal)")

    /** hs_err_pid*.log written by the JVM on a native crash (next to the app or in the working folder). */
    private fun jvmCrashFiles(): List<File> {
        val dirs = listOfNotNull(
            System.getProperty("jpackage.app-path")?.let { File(it).parentFile?.let { p -> File(p, "app") } },
            File(System.getProperty("user.dir")), AppDir.dir
        )
        return dirs.flatMap { d -> d.listFiles { f -> f.name.startsWith("hs_err_pid") && f.name.endsWith(".log") }?.toList().orEmpty() }
            .filter { System.currentTimeMillis() - it.lastModified() < 7 * 86_400_000L }
            .distinctBy { it.absolutePath }
    }

    private fun send(kind: String, text: String, now: Boolean = false) {
        val job = Runnable {
            runCatching {
                val req = Api.request("/api/app/report")
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .header("X-Platform", "desktop").header("X-App-Version", Updater.current).header("X-Report-Kind", kind)
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(text.take(900_000))).build()
                Api.client.send(req, java.net.http.HttpResponse.BodyHandlers.discarding())
            }
        }
        if (now) job.run() else Thread(job, "report").apply { isDaemon = true; start() }
    }
}
