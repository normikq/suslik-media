import ru.suslikovanora.media.net.BurstProxy
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }
fun get(url: String, range: String?): Pair<Int, ByteArray> {
    val c = URL(url).openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
    range?.let { c.setRequestProperty("Range", it) }
    val code = c.responseCode
    val body = (if (code < 400) c.inputStream else c.errorStream)?.readBytes() ?: ByteArray(0)
    return code to body
}
fun main(args: Array<String>) {
    val origin = args[0]; val expectedMd5 = args[1]; val size = args[2].toLong()
    val tmp = java.io.File("/tmp/claude-0/bpcache").apply { deleteRecursively(); mkdirs() }
    BurstProxy.init(android.content.Context(tmp))
    val local = BurstProxy.wrap(origin)
    println("local=$local")
    // 1. whole file
    val (c1, b1) = get(local, null); println("full: $c1 ${b1.size} ${md5(b1) == expectedMd5}")
    // 2. ranges
    for ((a, z) in listOf(0L to 99L, 1000L to 1999L, size - 100 to size - 1, 5_000_000L to 5_100_000L)) {
        val (c, b) = get(local, "bytes=$a-$z")
        val (_, ref) = get(origin, "bytes=$a-$z")
        println("range $a-$z: $c ${b.size} match=${b.contentEquals(ref)}")
    }
    val (c3, b3) = get(local, "bytes=${size - 10}-"); println("tail: $c3 ${b3.size}")
    val (c4, _) = get(local, "bytes=${size + 10}-"); println("beyond: $c4")
    // 3. throttled sequential read (simulated player) - check burst pattern via origin log
    BurstProxy.clear()
    println("--- throttled from a fresh start (origin requests below are its bursts)")
    val local2 = BurstProxy.wrap(origin)
    val c = URL(local2).openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
    c.setRequestProperty("Range", "bytes=0-")
    val inp = c.inputStream; val buf = ByteArray(64 * 1024); var tot = 0L; val t0 = System.currentTimeMillis()
    val md = MessageDigest.getInstance("MD5")
    // ~2 MB/s like a high-bitrate film: 64 KB every 32 ms
    while (true) { val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n); tot += n; Thread.sleep(32L * n / (64 * 1024)) }
    println("throttled: $tot ${md.digest().joinToString("") { "%02x".format(it) } == expectedMd5} in ${System.currentTimeMillis() - t0} ms")
    BurstProxy.clear()
    println("files left: ${tmp.resolve("stream").list()?.size}")
    System.exit(0)
}
