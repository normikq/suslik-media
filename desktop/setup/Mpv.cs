using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Security.Cryptography;
using System.Threading;

namespace SuslikSetup
{
    /// <summary>
    /// libmpv-2.dll (видеодвижок mpv) — отдельно от MSI, один раз, в %APPDATA%\SuslikMedia\mpv. Установщик везёт её
    /// сжатой внутри себя (libmpv-2.dll.gz, кладёт CI). Та же схема, что в приложении (MpvInstall.kt):
    /// версия — libmpv.properties, метка libmpv.version = SHA-256 библиотеки; если она совпадает, ничего не делаем.
    /// </summary>
    static class Mpv
    {
        static readonly Dictionary<string, string> pin = Load();
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

        /// <summary>Библиотека есть внутри установщика.</summary>
        public static bool Bundled => Assembly.GetExecutingAssembly().GetManifestResourceInfo("libmpv-2.dll.gz") != null;

        /// <summary>Распаковывает и проверяет; progress(доля, готово байт, всего байт).</summary>
        public static void Install(Action<double, long, long> progress, CancellationToken ct)
        {
            Directory.CreateDirectory(Product.MpvFolder);
            var part = Dll + ".part";
            using (var res = Assembly.GetExecutingAssembly().GetManifestResourceStream("libmpv-2.dll.gz"))
            using (var counting = new CountingStream(res))
            using (var gz = new GZipStream(counting, CompressionMode.Decompress))
            using (var sha = SHA256.Create())
            {
                long total = res.Length;
                using (var file = File.Create(part))
                {
                    var buf = new byte[1 << 16];
                    int n;
                    while ((n = gz.Read(buf, 0, buf.Length)) > 0)
                    {
                        ct.ThrowIfCancellationRequested();
                        file.Write(buf, 0, n);
                        sha.TransformBlock(buf, 0, n, null, 0);
                        progress((double)counting.Count / total, counting.Count, total);
                    }
                }
                sha.TransformFinalBlock(new byte[0], 0, 0);
                var hash = BitConverter.ToString(sha.Hash).Replace("-", "").ToLowerInvariant();
                if (hash != Sha256) { File.Delete(part); throw new IOException("Видеодвижок повреждён"); }
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
