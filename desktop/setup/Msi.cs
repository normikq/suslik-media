using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Text;

namespace SuslikSetup
{
    /// <summary>
    /// Windows Installer без его собственного окна: MSI ставится и удаляется «молча», а ход работы приходит сюда
    /// (MsiSetExternalUI) и рисуется в нашем окне.
    /// </summary>
    static class Msi
    {
        const int INSTALLUILEVEL_NONE = 2;
        const int INSTALLUILEVEL_SOURCERESONLY = 0x100;
        const uint INSTALLLOGMODE_FATALEXIT = 1 << 0, INSTALLLOGMODE_ERROR = 1 << 1, INSTALLLOGMODE_WARNING = 1 << 2,
            INSTALLLOGMODE_USER = 1 << 3, INSTALLLOGMODE_ACTIONSTART = 1 << 8, INSTALLLOGMODE_PROGRESS = 1 << 10,
            INSTALLLOGMODE_FILESINUSE = 1 << 5, INSTALLLOGMODE_RMFILESINUSE = 1 << 25;
        const uint INSTALLMESSAGE_ERROR = 0x02000000, INSTALLMESSAGE_FATALEXIT = 0x00000000,
            INSTALLMESSAGE_PROGRESS = 0x0A000000, INSTALLMESSAGE_ACTIONSTART = 0x08000000,
            INSTALLMESSAGE_FILESINUSE = 0x05000000, INSTALLMESSAGE_RMFILESINUSE = 0x19000000;
        const int IDOK = 1, IDCANCEL = 2, IDIGNORE = 5;
        public const int ERROR_SUCCESS = 0, ERROR_SUCCESS_REBOOT_REQUIRED = 3010, ERROR_INSTALL_USEREXIT = 1602;

        delegate int InstallUIHandler(IntPtr context, uint messageType, [MarshalAs(UnmanagedType.LPWStr)] string message);

        [DllImport("msi.dll", CharSet = CharSet.Unicode)] static extern int MsiSetInternalUI(int level, IntPtr window);
        [DllImport("msi.dll", CharSet = CharSet.Unicode)] static extern InstallUIHandler MsiSetExternalUIW(InstallUIHandler handler, uint filter, IntPtr context);
        [DllImport("msi.dll", CharSet = CharSet.Unicode)] static extern uint MsiInstallProductW(string packagePath, string commandLine);
        [DllImport("msi.dll", CharSet = CharSet.Unicode)] static extern uint MsiConfigureProductExW(string product, int level, int state, string commandLine);
        [DllImport("msi.dll", CharSet = CharSet.Unicode)] static extern uint MsiEnumRelatedProductsW(string upgradeCode, uint reserved, uint index, StringBuilder product);
        [DllImport("msi.dll", CharSet = CharSet.Unicode)] static extern uint MsiGetProductInfoW(string product, string property, StringBuilder value, ref uint len);
        [DllImport("msi.dll", CharSet = CharSet.Unicode)] static extern uint MsiOpenPackageExW(string path, uint options, out IntPtr handle);
        [DllImport("msi.dll", CharSet = CharSet.Unicode)] static extern uint MsiGetPropertyW(IntPtr handle, string name, StringBuilder value, ref uint len);
        [DllImport("msi.dll")] static extern uint MsiCloseHandle(IntPtr handle);
        [DllImport("msi.dll", CharSet = CharSet.Unicode)] static extern uint MsiEnableLogW(uint mode, string file, uint attributes);

        /// <summary>Установленные версии продукта (по UpgradeCode, общему для всех версий).</summary>
        public static List<string> RelatedProducts(string upgradeCode)
        {
            var list = new List<string>();
            for (uint i = 0; ; i++)
            {
                var sb = new StringBuilder(39);
                if (MsiEnumRelatedProductsW(upgradeCode, 0, i, sb) != 0) break;
                list.Add(sb.ToString());
            }
            return list;
        }

        public static string ProductInfo(string product, string property)
        {
            uint len = 512; var sb = new StringBuilder((int)len);
            return MsiGetProductInfoW(product, property, sb, ref len) == 0 ? sb.ToString() : "";
        }

        /// <summary>ProductCode и ProductVersion из самого файла MSI.</summary>
        public static (string code, string version) PackageInfo(string path)
        {
            MsiSetInternalUI(INSTALLUILEVEL_NONE, IntPtr.Zero);
            if (MsiOpenPackageExW(path, 1 /* MSIOPENPACKAGEFLAGS_IGNOREMACHINESTATE */, out var h) != 0) return ("", "");
            try { return (Property(h, "ProductCode"), Property(h, "ProductVersion")); }
            finally { MsiCloseHandle(h); }
        }

        static string Property(IntPtr h, string name)
        {
            uint len = 256; var sb = new StringBuilder((int)len);
            return MsiGetPropertyW(h, name, sb, ref len) == 0 ? sb.ToString() : "";
        }

        public static uint Install(string package, string commandLine, Action<double, string> progress, Func<bool> cancelled, string log) =>
            Run(() => MsiInstallProductW(package, commandLine), progress, cancelled, log);

        public static uint Uninstall(string product, Action<double, string> progress, Func<bool> cancelled, string log) =>
            Run(() => MsiConfigureProductExW(product, 0, 2 /* INSTALLSTATE_ABSENT */, "REBOOT=ReallySuppress"), progress, cancelled, log);

        /// <summary>Последний текст ошибки от Windows Installer (для окна ошибки).</summary>
        public static string LastError { get; private set; }

        static readonly object gate = new object();

        static uint Run(Func<uint> action, Action<double, string> progress, Func<bool> cancelled, string log)
        {
            lock (gate)
            {
                LastError = null;
                var tracker = new Progress(progress, cancelled);
                InstallUIHandler handler = tracker.Handle; // держим ссылку, пока идёт установка
                MsiSetInternalUI(INSTALLUILEVEL_NONE | INSTALLUILEVEL_SOURCERESONLY, IntPtr.Zero);
                if (log != null) MsiEnableLogW(0x1FFF /* основные сообщения */, log, 0);
                var previous = MsiSetExternalUIW(handler,
                    INSTALLLOGMODE_PROGRESS | INSTALLLOGMODE_ACTIONSTART | INSTALLLOGMODE_ERROR | INSTALLLOGMODE_FATALEXIT |
                    INSTALLLOGMODE_WARNING | INSTALLLOGMODE_USER | INSTALLLOGMODE_FILESINUSE | INSTALLLOGMODE_RMFILESINUSE, IntPtr.Zero);
                try { return action(); }
                finally
                {
                    MsiSetExternalUIW(previous, 0, IntPtr.Zero);
                    GC.KeepAlive(handler);
                }
            }
        }

        /// <summary>Перевод сообщений INSTALLMESSAGE_PROGRESS в долю 0..1 (схема из документации Windows Installer).</summary>
        sealed class Progress
        {
            readonly Action<double, string> report;
            readonly Func<bool> cancelled;
            long total, done, step;
            bool forward = true, enableActionData, scriptInProgress;
            string action = "";

            public Progress(Action<double, string> report, Func<bool> cancelled) { this.report = report; this.cancelled = cancelled; }

            public int Handle(IntPtr context, uint messageType, string message)
            {
                if (cancelled()) return IDCANCEL;
                uint kind = messageType & 0xFF000000;
                switch (kind)
                {
                    case INSTALLMESSAGE_PROGRESS: OnProgress(message); return IDOK;
                    case INSTALLMESSAGE_ACTIONSTART:
                        // «Действие 12:00:00: InstallFiles. Копирование новых файлов. …»
                        var parts = (message ?? "").Split(new[] { ". " }, 3, StringSplitOptions.None);
                        action = parts.Length > 1 ? parts[1].Trim() : "";
                        if (enableActionData && step > 0) Advance(step);
                        report(Fraction(), action);
                        return IDOK;
                    // Программа держит файлы: мы её уже закрыли, остальное (например, проводник) — пропускаем.
                    case INSTALLMESSAGE_ERROR: LastError = message; return 0;
                    case INSTALLMESSAGE_FILESINUSE: return IDIGNORE;
                    case INSTALLMESSAGE_RMFILESINUSE: return IDIGNORE;
                    default: return 0;
                }
            }

            void OnProgress(string message)
            {
                var f = Fields(message);
                if (f.Count < 2) return;
                switch (f[0])
                {
                    case 0: // сброс: всего тиков, направление, идёт ли скрипт
                        total = f[1]; forward = f.Count < 3 || f[2] == 0;
                        done = forward ? 0 : total; enableActionData = false;
                        scriptInProgress = f.Count > 3 && f[3] == 1;
                        break;
                    case 1: // тиков на каждое ActionData
                        step = f[1]; enableActionData = f.Count > 2 && f[2] != 0;
                        break;
                    case 2: // продвинулись
                        if (total > 0) Advance(f[1]);
                        break;
                    case 3: // добавить к общему
                        total += f[1];
                        break;
                }
                if (!scriptInProgress) report(Fraction(), action);
            }

            void Advance(long ticks) { done += forward ? ticks : -ticks; if (done < 0) done = 0; if (done > total) done = total; }

            double Fraction() => total <= 0 ? 0 : (double)done / total;

            static List<long> Fields(string message)
            {
                // «1: 0 2: 1234 3: 0 4: 1 »
                var list = new List<long>();
                var tokens = (message ?? "").Split(new[] { ' ' }, StringSplitOptions.RemoveEmptyEntries);
                for (int i = 0; i + 1 < tokens.Length; i += 2)
                {
                    if (!tokens[i].EndsWith(":") || !long.TryParse(tokens[i + 1], out var v)) break;
                    list.Add(v);
                }
                return list;
            }
        }
    }
}
