package ru.suslikovanora.media.desktop.trackers

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

enum class BrowserType(val displayName: String, val icon: String) {
    FIREFOX("Mozilla Firefox", "🦊"),
    EDGE("Microsoft Edge", "🌐"),
    CHROME("Google Chrome", "🌐"),
    YANDEX("Яндекс Браузер", "🔴"),
    OPERA("Opera", "⭕"),
    OPERA_GX("Opera GX", "🎮"),
    BRAVE("Brave", "🦁"),
    VIVALDI("Vivaldi", "⚡");

    val isChromium: Boolean get() = this != FIREFOX
}

object BrowserExecutableFinder {
    private val localAppData = System.getenv("LOCALAPPDATA") ?: ""
    private val programFiles = System.getenv("ProgramFiles") ?: "C:\\Program Files"
    private val programFilesX86 = System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)"

    fun find(type: BrowserType): File? {
        val candidates = when (type) {
            BrowserType.EDGE -> listOf(
                "$programFilesX86\\Microsoft\\Edge\\Application\\msedge.exe",
                "$programFiles\\Microsoft\\Edge\\Application\\msedge.exe",
                "$localAppData\\Microsoft\\Edge\\Application\\msedge.exe"
            )
            BrowserType.CHROME -> listOf(
                "$programFiles\\Google\\Chrome\\Application\\chrome.exe",
                "$programFilesX86\\Google\\Chrome\\Application\\chrome.exe",
                "$localAppData\\Google\\Chrome\\Application\\chrome.exe"
            )
            BrowserType.YANDEX -> listOf(
                "$localAppData\\Yandex\\YandexBrowser\\Application\\browser.exe",
                "$programFiles\\Yandex\\YandexBrowser\\Application\\browser.exe",
                "$programFilesX86\\Yandex\\YandexBrowser\\Application\\browser.exe"
            )
            BrowserType.BRAVE -> listOf(
                "$programFiles\\BraveSoftware\\Brave-Browser\\Application\\brave.exe",
                "$programFilesX86\\BraveSoftware\\Brave-Browser\\Application\\brave.exe",
                "$localAppData\\BraveSoftware\\Brave-Browser\\Application\\brave.exe"
            )
            BrowserType.OPERA -> listOf(
                "$localAppData\\Programs\\Opera\\opera.exe",
                "$programFiles\\Opera\\launcher.exe",
                "$programFilesX86\\Opera\\launcher.exe"
            )
            BrowserType.OPERA_GX -> listOf(
                "$localAppData\\Programs\\Opera GX\\opera.exe",
                "$programFiles\\Opera GX\\launcher.exe"
            )
            BrowserType.VIVALDI -> listOf(
                "$localAppData\\Vivaldi\\Application\\vivaldi.exe",
                "$programFiles\\Vivaldi\\Application\\vivaldi.exe"
            )
            BrowserType.FIREFOX -> listOf(
                "$programFiles\\Mozilla Firefox\\firefox.exe",
                "$programFilesX86\\Mozilla Firefox\\firefox.exe"
            )
        }

        for (path in candidates) {
            if (path.isNotBlank()) {
                val f = File(path)
                if (f.exists() && f.isFile) return f
            }
        }

        // Registry query fallback
        val exeName = when (type) {
            BrowserType.EDGE -> "msedge.exe"
            BrowserType.CHROME -> "chrome.exe"
            BrowserType.YANDEX -> "browser.exe"
            BrowserType.BRAVE -> "brave.exe"
            BrowserType.OPERA, BrowserType.OPERA_GX -> "opera.exe"
            BrowserType.VIVALDI -> "vivaldi.exe"
            BrowserType.FIREFOX -> "firefox.exe"
        }
        return findInRegistry(exeName)
    }

    private fun findInRegistry(exeName: String): File? {
        val keys = listOf(
            "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\$exeName",
            "HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\$exeName"
        )
        for (k in keys) {
            try {
                val proc = ProcessBuilder("reg", "query", k, "/ve")
                    .redirectErrorStream(true)
                    .start()
                val out = proc.inputStream.bufferedReader().readText()
                proc.waitFor()
                val match = Regex("""REG_SZ\s+(.*)""", RegexOption.IGNORE_CASE).find(out)
                if (match != null) {
                    val p = match.groupValues[1].trim().removeSurrounding("\"")
                    val f = File(p)
                    if (f.exists() && f.isFile) return f
                }
            } catch (_: Exception) {}
        }
        return null
    }
}

data class DetectedBrowser(
    val type: BrowserType,
    val profileName: String,
    val cookieFile: File,
    val localStateFile: File? = null
) {
    val title: String get() = "${type.icon} ${type.displayName}" + if (profileName.isNotBlank() && profileName != "Default") " ($profileName)" else ""
}

object BrowserDetector {

    private val localAppData = System.getenv("LOCALAPPDATA")?.let { File(it) }
    private val appData = System.getenv("APPDATA")?.let { File(it) }

    /**
     * Detects all installed browsers and their active profiles on the system.
     */
    fun detect(): List<DetectedBrowser> {
        val out = mutableListOf<DetectedBrowser>()

        // 1. Firefox
        appData?.let {
            val ffDir = File(it, "Mozilla/Firefox/Profiles")
            if (ffDir.exists() && ffDir.isDirectory) {
                ffDir.listFiles()?.filter { p -> p.isDirectory }?.forEach { profile ->
                    val cookieDb = File(profile, "cookies.sqlite")
                    if (cookieDb.exists()) {
                        out.add(DetectedBrowser(BrowserType.FIREFOX, profile.name, cookieDb))
                    }
                }
            }
        }

        // Helper for Chromium-based browsers
        fun checkChromium(type: BrowserType, userDataDir: File) {
            if (!userDataDir.exists() || !userDataDir.isDirectory) return
            val localState = File(userDataDir, "Local State").takeIf { it.exists() }

            val profileDirs = mutableListOf<File>()
            val defaultDir = File(userDataDir, "Default")
            if (defaultDir.exists()) profileDirs.add(defaultDir)

            userDataDir.listFiles()?.filter {
                it.isDirectory && (it.name.startsWith("Profile ") || it.name == "Default")
            }?.forEach {
                if (!profileDirs.contains(it)) profileDirs.add(it)
            }

            profileDirs.forEach { profDir ->
                val c1 = File(profDir, "Network/Cookies")
                val c2 = File(profDir, "Cookies")
                val cookieFile = if (c1.exists()) c1 else if (c2.exists()) c2 else null
                if (cookieFile != null) {
                    out.add(DetectedBrowser(type, profDir.name, cookieFile, localState))
                }
            }
        }

        fun checkBrowserWithFallback(type: BrowserType, userDataDir: File?) {
            var found = false
            if (userDataDir != null && userDataDir.exists()) {
                val before = out.size
                checkChromium(type, userDataDir)
                if (out.size > before) found = true
            }
            if (!found && BrowserExecutableFinder.find(type) != null) {
                val profDir = EdgeBrowserBridge.profileDirFor(type)
                val fallbackCookies = File(profDir, "Default/Network/Cookies")
                val localState = File(profDir, "Local State").takeIf { it.exists() }
                out.add(DetectedBrowser(type, "Default", fallbackCookies, localState))
            }
        }

        // 2. Microsoft Edge
        checkBrowserWithFallback(BrowserType.EDGE, localAppData?.let { File(it, "Microsoft/Edge/User Data") })

        // 3. Google Chrome
        checkBrowserWithFallback(BrowserType.CHROME, localAppData?.let { File(it, "Google/Chrome/User Data") })

        // 4. Yandex Browser
        checkBrowserWithFallback(BrowserType.YANDEX, localAppData?.let { File(it, "Yandex/YandexBrowser/User Data") })

        // 5. Opera & Opera GX
        checkBrowserWithFallback(BrowserType.OPERA, appData?.let { File(it, "Opera Software/Opera Stable") })
        checkBrowserWithFallback(BrowserType.OPERA_GX, appData?.let { File(it, "Opera Software/Opera GX Stable") })

        // 6. Brave
        checkBrowserWithFallback(BrowserType.BRAVE, localAppData?.let { File(it, "BraveSoftware/Brave-Browser/User Data") })

        // 7. Vivaldi
        checkBrowserWithFallback(BrowserType.VIVALDI, localAppData?.let { File(it, "Vivaldi/User Data") })

        return out
    }

    /**
     * Attempts to automatically extract cookies for a given tracker domain (e.g. "rutracker.org").
     */
    fun extractCookies(detected: DetectedBrowser, domainKeyword: String = "rutracker"): Result<String> {
        return try {
            when {
                detected.type == BrowserType.FIREFOX -> extractFirefox(detected.cookieFile, domainKeyword)
                detected.type.isChromium -> {
                    val existing = runCatching {
                        kotlinx.coroutines.runBlocking {
                            EdgeBrowserBridge.getExistingCookies(detected.type, "https://$domainKeyword.org")
                        }
                    }.getOrNull()
                    if (!existing.isNullOrBlank()) {
                        Result.success(existing)
                    } else {
                        extractChromium(detected.cookieFile, detected.localStateFile, domainKeyword, detected.type)
                    }
                }
                else -> Result.failure(IllegalStateException("Неподдерживаемый браузер"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractFirefox(cookieFile: File, domainKeyword: String): Result<String> {
        if (!cookieFile.exists()) return Result.failure(IllegalStateException("Файл cookies.sqlite не найден"))

        // Copy SQLite DB because running Firefox keeps an exclusive file lock
        val tmp = File.createTempFile("sm_ff_cookies_", ".sqlite")
        try {
            Files.copy(cookieFile.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
            val bytes = tmp.readBytes()
            val text = String(bytes, Charsets.UTF_8)

            if (!text.contains(domainKeyword, ignoreCase = true)) {
                return Result.failure(IllegalStateException("В этом профиле Firefox не найдено кук для $domainKeyword"))
            }

            val cookies = LinkedHashMap<String, String>()

            // Targeted extractions for RuTracker session & clearance
            Regex("""bb_session[\x00-\x1F]*([0-9a-zA-Z\-_]{15,80})""").find(text)?.groupValues?.get(1)?.let {
                cookies["bb_session"] = it
            }
            Regex("""bb_guid[\x00-\x1F]*([0-9a-zA-Z]{6,30})""").find(text)?.groupValues?.get(1)?.let {
                cookies["bb_guid"] = it
            }
            Regex("""cf_clearance[\x00-\x1F]*([0-9a-zA-Z._\-]{80,800})""").find(text)?.groupValues?.get(1)?.let {
                cookies["cf_clearance"] = it
            }
            cookies["bb_ssl"] = "1"

            if (!cookies.containsKey("bb_session")) {
                return Result.failure(IllegalStateException("В профиле найден домен $domainKeyword, но bb_session отсутствует"))
            }

            val header = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            return Result.success(header)
        } finally {
            runCatching { tmp.delete() }
        }
    }

    private fun extractChromium(
        cookieFile: File,
        localStateFile: File?,
        domainKeyword: String,
        type: BrowserType = BrowserType.EDGE
    ): Result<String> {
        if (!cookieFile.exists()) return Result.failure(IllegalStateException("Файл Cookies не найден"))
        if (localStateFile == null || !localStateFile.exists()) {
            return Result.failure(IllegalStateException("Файл Local State не найден"))
        }

        // PowerShell script to inspect and decrypt Chromium cookies
        val psScript = """
            try {
                ${'$'}cFile = '${cookieFile.absolutePath.replace("'", "''")}';
                ${'$'}lState = '${localStateFile.absolutePath.replace("'", "''")}';
                ${'$'}domain = '$domainKeyword';
                
                if (-not (Test-Path ${'$'}cFile)) { exit 1 }

                ${'$'}tmpDb = [System.IO.Path]::GetTempFileName();
                ${'$'}copied = ${'$'}false;
                try {
                    Copy-Item -Path ${'$'}cFile -Destination ${'$'}tmpDb -Force -ErrorAction Stop;
                    ${'$'}copied = ${'$'}true;
                } catch {}

                if (-not ${'$'}copied) {
                    exit 6;
                }

                ${'$'}rawBytes = [System.IO.File]::ReadAllBytes(${'$'}tmpDb);
                Remove-Item ${'$'}tmpDb -Force -ErrorAction SilentlyContinue;
                ${'$'}rawText = [System.Text.Encoding]::UTF8.GetString(${'$'}rawBytes);

                if (-not (${'$'}rawText -match ${'$'}domain)) { exit 2 }

                # Modern Chromium App-Bound Encryption check
                if (${'$'}rawText -match 'v20') { exit 5 }

                # Check for unencrypted fallback cookies
                ${'$'}cookies = @();
                [regex]::Matches(${'$'}rawText, "(bb_[a-z_]+)\x00([^\x00\x01\x02\x03\x04\x05]{1,120})") | ForEach-Object {
                    ${'$'}cookies += "${'$'}(${'$'}_.Groups[1].Value)=${'$'}(${'$'}_.Groups[2].Value)"
                };
                if (${'$'}cookies.Count -gt 0) {
                    Write-Output (${'$'}cookies -join '; ');
                    exit 0;
                }

                exit 3;
            } catch { exit 1 }
        """.trimIndent()

        val process = ProcessBuilder("powershell", "-NoProfile", "-Command", psScript)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText().trim()
        val exitCode = process.waitFor()

        return when (exitCode) {
            0 -> if (output.isNotBlank()) Result.success(output) else Result.failure(IllegalStateException("Куки не найдены"))
            2 -> Result.failure(IllegalStateException("В этом браузере (${type.displayName}) нет сохранённых кук для $domainKeyword"))
            5 -> Result.failure(IllegalStateException("В ${type.displayName} куки защищены App-Bound Encryption (v20): используйте кнопку «Войти через ${type.displayName}»"))
            6 -> Result.failure(IllegalStateException("Файл Cookies занят работающим браузером — закройте ${type.displayName} или используйте кнопку выше"))
            else -> Result.failure(IllegalStateException("Куки в ${type.displayName} зашифрованы: скопируйте значение bb_session из DevTools"))
        }
    }
}
