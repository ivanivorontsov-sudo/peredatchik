using System.Diagnostics;

namespace Peredatchik;

/// USB-кабель через ADB: проброс портов в обе стороны.
static class Adb
{
    public static string Find()
    {
        var cands = new List<string>();
        foreach (var d in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(';')) cands.Add(Path.Combine(d.Trim(), "adb.exe"));
        var la = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        cands.Add(Path.Combine(la, @"Android\Sdk\platform-tools\adb.exe"));
        cands.Add(Path.Combine(AppContext.BaseDirectory, @"platform-tools\adb.exe"));
        cands.Add(@"C:\platform-tools\adb.exe");
        return cands.FirstOrDefault(File.Exists);
    }

    public static string Setup()
    {
        var adb = Find();
        if (adb == null) return "adb.exe не найден. Скачайте «SDK Platform-Tools» с developer.android.com и распакуйте в C:\\platform-tools, " +
                                "затем включите на телефоне «Отладку по USB». Без ADB используйте «USB-модем» в настройках телефона — тогда работает обычный Wi-Fi-режим.";
        string Run(string a)
        {
            var p = Process.Start(new ProcessStartInfo(adb, a) { RedirectStandardOutput = true, RedirectStandardError = true, UseShellExecute = false, CreateNoWindow = true });
            var o = p.StandardOutput.ReadToEnd() + p.StandardError.ReadToEnd(); p.WaitForExit(15000); return o.Trim();
        }
        var dev = Run("devices");
        if (!dev.Split('\n').Skip(1).Any(l => l.Contains("\tdevice"))) return "Телефон не найден по USB. Подключите кабель и разрешите отладку по USB на телефоне.\n" + dev;
        var r1 = Run("reverse tcp:47252 tcp:47232");
        var r2 = Run("forward tcp:47242 tcp:47232");
        return "Готово: USB-канал настроен (adb reverse/forward). Выберите способ «USB-кабель через ADB» или «Авто».\n" + r1 + " " + r2;
    }
}
