using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Net;
using System.Net.Http;
using System.Reflection;
using System.Security.Cryptography;
using System.Threading;
using System.Threading.Tasks;

namespace SuslikSetup
{
    /// <summary>
    /// libmpv-2.dll (видеодвижок mpv) — отдельно от MSI, один раз, в %APPDATA%\SuslikMedia\mpv. Та же схема, что
    /// в приложении (MpvInstall.kt): версия — libmpv.properties, метка libmpv.version = SHA-256 библиотеки.
    /// </summary>
    static class Mpv
    {
        static readonly Dictionary<string, string> pin = Load();
        static string Url => pin.TryGetValue("url", out var v) ? v : "";
        static string Sha256 => (pin.TryGetValue("dllSha256", out var v) ? v : "").ToLowerInvariant();
        static string Dll => Path.Combine(Product.MpvFolder, "libmpv-2.dll");
        static string Mark => Path.Combine(Product.MpvFolder, "libmpv.version");

        static Dictionary<string, string> Load()
        {
            var map = new Dictionary<string, string>();
            using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("libmpv.properties"))
            {
                if (s == null) return map;
                using (var r = new StreamReader(s))
                    for (string line; (line = r.ReadLine()) != null;)
                    {
                        line = line.Trim();
                        if (line.Length == 0 || line[0] == '#') continue;
                        int i = line.IndexOf('=');
                        if (i > 0) map[line.Substring(0, i).Trim()] = line.Substring(i + 1).Trim();
                    }
            }
            return map;
        }

        /// <summary>Нужная версия уже на месте: при обновлении ничего не качаем.</summary>
        public static bool Ready
        {
            get
            {
                if (Sha256.Length == 0) return true;
                try { return File.Exists(Dll) && File.ReadAllText(Mark).Trim() == Sha256; } catch { return false; }
            }
        }

        /// <summary>Качает и проверяет; progress(доля, скачано, всего).</summary>
        public static async Task Download(Action<double, long, long> progress, CancellationToken ct)
        {
            ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12;
            Directory.CreateDirectory(Product.MpvFolder);
            var part = Dll + ".part";
            using (var http = new HttpClient { Timeout = TimeSpan.FromMinutes(30) })
            using (var res = await http.GetAsync(Url, HttpCompletionOption.ResponseHeadersRead, ct))
            {
                res.EnsureSuccessStatusCode();
                long total = res.Content.Headers.ContentLength ?? -1;
                using (var net = await res.Content.ReadAsStreamAsync())
                using (var counting = new CountingStream(net))
                using (var gz = new GZipStream(counting, CompressionMode.Decompress))
                using (var sha = SHA256.Create())
                {
                    using (var file = File.Create(part))
                    {
                        var buf = new byte[1 << 16];
                        int n;
                        while ((n = await gz.ReadAsync(buf, 0, buf.Length, ct)) > 0)
                        {
                            await file.WriteAsync(buf, 0, n, ct);
                            sha.TransformBlock(buf, 0, n, null, 0);
                            progress(total > 0 ? (double)counting.Count / total : 0, counting.Count, total);
                        }
                    }
                    sha.TransformFinalBlock(new byte[0], 0, 0);
                    var hash = BitConverter.ToString(sha.Hash).Replace("-", "").ToLowerInvariant();
                    if (hash != Sha256) { File.Delete(part); throw new IOException("Файл видеодвижка повреждён при загрузке"); }
                }
            }
            if (File.Exists(Dll)) File.Delete(Dll);
            File.Move(part, Dll);
            File.WriteAllText(Mark, Sha256);
        }

        sealed class CountingStream : Stream
        {
            readonly Stream inner;
            public long Count;
            public CountingStream(Stream inner) { this.inner = inner; }
            public override int Read(byte[] buffer, int offset, int count) { int n = inner.Read(buffer, offset, count); Count += n; return n; }
            public override async Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken ct)
            {
                int n = await inner.ReadAsync(buffer, offset, count, ct); Count += n; return n;
            }
            public override bool CanRead => true;
            public override bool CanSeek => false;
            public override bool CanWrite => false;
            public override long Length => throw new NotSupportedException();
            public override long Position { get => Count; set => throw new NotSupportedException(); }
            public override void Flush() { }
            public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
            public override void SetLength(long value) => throw new NotSupportedException();
            public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
            protected override void Dispose(bool disposing) { if (disposing) inner.Dispose(); base.Dispose(disposing); }
        }
    }
}
