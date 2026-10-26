package ru.suslikovanora.media.core.torrent

object TorrentInspector {
    const val MAX_TORRENT_FILE_SIZE = 10 * 1024 * 1024 // 10 MB sanity limit

    val VIDEO_EXTENSIONS = setOf(
        "mkv", "mp4", "m4v", "avi", "mov", "webm", "ts", "m2ts", "mts",
        "wmv", "vob", "mpg", "mpeg", "flv", "3gp", "ogv", "asf"
    )

    val NON_VIDEO_EXTENSIONS = setOf(
        "exe", "msi", "apk", "pkg", "deb", "rpm", "bin", "cue", "dmg",
        "zip", "rar", "7z", "tar", "gz", "cbr", "cbz", "pdf", "epub", "fb2",
        "mp3", "flac", "wav", "ape", "aac", "ogg", "wma", "doc", "docx"
    )

    data class TorrentFileInfo(
        val path: String,
        val size: Long
    ) {
        val extension: String
            get() = path.substringAfterLast('.', "").lowercase()

        val isVideo: Boolean
            get() = VIDEO_EXTENSIONS.contains(extension)
    }

    data class TorrentInspection(
        val files: List<TorrentFileInfo>,
        val totalSize: Long,
        val videoSize: Long,
        val hasVideo: Boolean,
        val videoCount: Int,
        val nonVideoExtensions: Set<String>,
        val isParsedSuccessfully: Boolean = true
    )

    fun isVideoFileName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return VIDEO_EXTENSIONS.contains(ext)
    }

    fun hasVideoFiles(files: List<TorrentFileInfo>): Boolean =
        files.any { it.isVideo }

    fun inspect(bytes: ByteArray): TorrentInspection {
        if (bytes.isEmpty() || bytes.size > MAX_TORRENT_FILE_SIZE) {
            return TorrentInspection(
                files = emptyList(),
                totalSize = 0L,
                videoSize = 0L,
                hasVideo = false,
                videoCount = 0,
                nonVideoExtensions = emptySet(),
                isParsedSuccessfully = false
            )
        }
        val files = runCatching { extractFilesFromTorrentBytesInternal(bytes) }.getOrNull()
        if (files == null) {
            return TorrentInspection(
                files = emptyList(),
                totalSize = 0L,
                videoSize = 0L,
                hasVideo = false,
                videoCount = 0,
                nonVideoExtensions = emptySet(),
                isParsedSuccessfully = false
            )
        }
        val totalSize = files.sumOf { it.size }
        val videoFiles = files.filter { it.isVideo }
        val videoSize = videoFiles.sumOf { it.size }
        val nonVideoExts = files.filter { !it.isVideo && it.extension.isNotBlank() }
            .map { it.extension }
            .toSet()

        return TorrentInspection(
            files = files,
            totalSize = totalSize,
            videoSize = videoSize,
            hasVideo = videoFiles.isNotEmpty(),
            videoCount = videoFiles.size,
            nonVideoExtensions = nonVideoExts,
            isParsedSuccessfully = true
        )
    }

    fun extractFilesFromTorrentBytes(bytes: ByteArray): List<TorrentFileInfo> {
        if (bytes.isEmpty() || bytes.size > MAX_TORRENT_FILE_SIZE) return emptyList()
        return runCatching { extractFilesFromTorrentBytesInternal(bytes) }.getOrDefault(emptyList())
    }

    private fun extractFilesFromTorrentBytesInternal(bytes: ByteArray): List<TorrentFileInfo> {
        val parser = BencodeParser(bytes)
        val root = parser.parse() as? Map<*, *> ?: throw IllegalArgumentException("Invalid bencode root")
        val info = root["info"] as? Map<*, *> ?: throw IllegalArgumentException("Missing info dictionary")

        val filesList = mutableListOf<TorrentFileInfo>()
        val multiFiles = info["files"] as? List<*>

        if (multiFiles != null) {
            for (item in multiFiles) {
                val fileDict = item as? Map<*, *> ?: continue
                val length = (fileDict["length"] as? Number)?.toLong() ?: 0L
                val pathList = (fileDict["path.utf-8"] as? List<*>)
                    ?: (fileDict["path"] as? List<*>)
                    ?: continue
                val pathStr = pathList.mapNotNull {
                    when (it) {
                        is ByteArray -> String(it, Charsets.UTF_8)
                        is String -> it
                        else -> null
                    }
                }.joinToString("/")
                if (pathStr.isNotBlank()) {
                    filesList.add(TorrentFileInfo(pathStr, length))
                }
            }
        } else {
            // Single-file torrent
            val nameObj = info["name.utf-8"] ?: info["name"]
            val name = when (nameObj) {
                is ByteArray -> String(nameObj, Charsets.UTF_8)
                is String -> nameObj
                else -> "unknown"
            }
            val length = (info["length"] as? Number)?.toLong() ?: 0L
            filesList.add(TorrentFileInfo(name, length))
        }

        return filesList
    }

    private class BencodeParser(private val bytes: ByteArray) {
        private var pos = 0

        fun parse(depth: Int = 0): Any? {
            if (depth > 32) throw IllegalStateException("Bencode recursion depth limit exceeded (> 32)")
            if (pos >= bytes.size) return null
            return when (bytes[pos].toInt().toChar()) {
                'i' -> parseInteger()
                'l' -> parseList(depth + 1)
                'd' -> parseDictionary(depth + 1)
                in '0'..'9' -> parseByteArray()
                else -> null
            }
        }

        private fun parseInteger(): Long {
            pos++ // skip 'i'
            val start = pos
            while (pos < bytes.size && bytes[pos].toInt().toChar() != 'e') {
                pos++
            }
            val numStr = String(bytes, start, pos - start, Charsets.US_ASCII)
            pos++ // skip 'e'
            return numStr.toLongOrNull() ?: 0L
        }

        private fun parseByteArray(): ByteArray {
            val start = pos
            while (pos < bytes.size && bytes[pos].toInt().toChar() != ':') {
                pos++
            }
            val len = String(bytes, start, pos - start, Charsets.US_ASCII).toIntOrNull() ?: 0
            pos++ // skip ':'
            val actualLen = len.coerceAtMost(bytes.size - pos)
            val result = ByteArray(actualLen)
            System.arraycopy(bytes, pos, result, 0, actualLen)
            pos += actualLen
            return result
        }

        private fun parseList(depth: Int): List<Any?> {
            pos++ // skip 'l'
            val list = mutableListOf<Any?>()
            var closed = false
            while (pos < bytes.size) {
                if (bytes[pos].toInt().toChar() == 'e') {
                    pos++
                    closed = true
                    break
                }
                val item = parse(depth) ?: break
                list.add(item)
            }
            if (!closed) throw IllegalArgumentException("Unclosed bencode list")
            return list
        }

        private fun parseDictionary(depth: Int): Map<String, Any?> {
            pos++ // skip 'd'
            val map = mutableMapOf<String, Any?>()
            var closed = false
            while (pos < bytes.size) {
                if (bytes[pos].toInt().toChar() == 'e') {
                    pos++
                    closed = true
                    break
                }
                val rawKey = parse(depth) as? ByteArray ?: break
                val key = String(rawKey, Charsets.UTF_8)
                val value = parse(depth)
                map[key] = value
            }
            if (!closed) throw IllegalArgumentException("Unclosed bencode dictionary")
            return map
        }
    }
}
