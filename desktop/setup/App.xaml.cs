using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Windows;

namespace SuslikSetup
{
    /// <summary>
    /// Режимы: с MSI внутри — мастер установки; без него (SuslikUninstall.exe) или с /uninstall — окно удаления;
    /// /register &lt;версия&gt; — молча обновить запись в «Установленных приложениях» (зовёт само приложение);
    /// /update — самообновление из приложения: сразу ставит в ту же папку и снова запускает приложение.
    /// </summary>
    public partial class App : Application
    {
        public static bool HasPackage => Assembly.GetExecutingAssembly().GetManifestResourceInfo("package.msi") != null;
        public static string SelfPath => Assembly.GetExecutingAssembly().Location;
        public static string Log => Path.Combine(Path.GetTempPath(), "SuslikSetup.log");

        protected override void OnStartup(StartupEventArgs e)
        {
            base.OnStartup(e);
            var args = e.Args.Select(a => a.ToLowerInvariant()).ToList();
            bool Has(string flag) => args.Contains(flag);

            if (Has("/register"))
            {
                int code = 0;
                try
                {
                    int i = args.IndexOf("/register");
                    Product.Register(SelfPath, i + 1 < e.Args.Length ? e.Args[i + 1] : Product.Version);
                }
                catch { code = 1; }
                Shutdown(code);
                return;
            }

            // Снимки окон для проверки дизайна в CI (desktop.yml): ничего не ставит и не удаляет.
            if (Has("/preview"))
            {
                int i = args.IndexOf("/preview");
                var dir = i + 1 < e.Args.Length ? e.Args[i + 1] : ".";
                Directory.CreateDirectory(dir);
                SuslikSetup.MainWindow.Preview(dir);
                Shutdown(0);
                return;
            }

            bool uninstall = Has("/uninstall") || !HasPackage;
            if (uninstall && !Has("/temp"))
            {
                // Файл, который удаляется, нельзя держать открытым: работаем из копии во временной папке.
                try
                {
                    var copy = Path.Combine(Path.GetTempPath(), $"SuslikUninstall-{Guid.NewGuid():N}.exe");
                    File.Copy(SelfPath, copy, true);
                    var rest = string.Join(" ", e.Args.Where(a => !a.Equals("/uninstall", StringComparison.OrdinalIgnoreCase)).Select(Quote));
                    Process.Start(new ProcessStartInfo(copy, $"/uninstall /temp {rest}".Trim()) { UseShellExecute = false });
                    Shutdown(0);
                    return;
                }
                catch { /* не вышло — удаляем отсюда, файлы приложения доудалит Windows после перезагрузки */ }
            }
            if (uninstall && Has("/temp")) Exit += (_, __) => DeleteSelfLater();

            if (uninstall && Has("/quiet"))
            {
                var ok = Work.Uninstall(false, (_, __) => { }, () => false);
                Shutdown(ok ? 0 : 1);
                return;
            }

            ShutdownMode = ShutdownMode.OnMainWindowClose;
            var w = new MainWindow(uninstall, update: Has("/update"));
            MainWindow = w;
            w.Show();
        }

        static string Quote(string a) => a.Contains(' ') ? $"\"{a}\"" : a;

        static void DeleteSelfLater()
        {
            try
            {
                Process.Start(new ProcessStartInfo("cmd.exe", $"/c ping 127.0.0.1 -n 3 > nul & del /f /q \"{SelfPath}\"")
                    { CreateNoWindow = true, UseShellExecute = false, WindowStyle = ProcessWindowStyle.Hidden });
            }
            catch { }
        }
    }
}
