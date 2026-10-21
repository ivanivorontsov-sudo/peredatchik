using Microsoft.Win32;

namespace Peredatchik;

/// Встраивание в Проводник (для текущего пользователя, без прав администратора):
/// «Открыть с помощью → Передатчик», контекстное меню «Отправить на телефон», ярлык в «Отправить».
public static class ShellIntegration
{
    static readonly string[] Exts = { ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".heic", ".tif", ".tiff", ".mp4", ".mov", ".pdf", ".txt", ".zip", ".docx", ".xlsx" };
    const string ProgId = "Peredatchik.Send";
    static string Exe => Environment.ProcessPath;

    public static bool IsRegistered()
    {
        using var k = Registry.CurrentUser.OpenSubKey(@"Software\Classes\*\shell\Peredatchik\command");
        return k != null && (k.GetValue("") as string ?? "").Contains(Exe, StringComparison.OrdinalIgnoreCase);
    }

    public static void Register()
    {
        var cmd = $"\"{Exe}\" \"%1\"";
        using (var k = Registry.CurrentUser.CreateSubKey(@"Software\Classes\*\shell\Peredatchik"))
        {
            k.SetValue("MUIVerb", "Отправить на телефон");
            k.SetValue("Icon", $"\"{Exe}\",0");
            k.SetValue("MultiSelectModel", "Player");
            using var c = k.CreateSubKey("command"); c.SetValue("", cmd);
        }
        using (var k = Registry.CurrentUser.CreateSubKey($@"Software\Classes\{ProgId}"))
        {
            k.SetValue("", "Отправить на телефон (Передатчик)");
            k.SetValue("FriendlyTypeName", "Передатчик");
            using (var i = k.CreateSubKey("DefaultIcon")) i.SetValue("", $"\"{Exe}\",0");
            using var c = k.CreateSubKey(@"shell\open\command"); c.SetValue("", cmd);
        }
        using (var k = Registry.CurrentUser.CreateSubKey(@"Software\Classes\Applications\Peredatchik.exe"))
        {
            k.SetValue("FriendlyAppName", "Передатчик — отправить на телефон");
            using (var c = k.CreateSubKey(@"shell\open\command")) c.SetValue("", cmd);
            using var st = k.CreateSubKey("SupportedTypes");
            foreach (var e in Exts) st.SetValue(e, "");
        }
        foreach (var e in Exts)
            using (var k = Registry.CurrentUser.CreateSubKey($@"Software\Classes\{e}\OpenWithProgids")) k.SetValue(ProgId, Array.Empty<byte>(), RegistryValueKind.None);
        try
        {
            var sendTo = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.SendTo), "Телефон (Передатчик).lnk");
            CreateShortcut(sendTo, Exe, "", "Отправить на телефон");
        }
        catch { }
        using (var run = Registry.CurrentUser.CreateSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run"))
            run.SetValue("Peredatchik", $"\"{Exe}\" --tray");
        SHChangeNotify(0x08000000, 0, IntPtr.Zero, IntPtr.Zero);
    }

    public static void Unregister()
    {
        try { Registry.CurrentUser.DeleteSubKeyTree(@"Software\Classes\*\shell\Peredatchik", false); } catch { }
        try { Registry.CurrentUser.DeleteSubKeyTree($@"Software\Classes\{ProgId}", false); } catch { }
        try { Registry.CurrentUser.DeleteSubKeyTree(@"Software\Classes\Applications\Peredatchik.exe", false); } catch { }
        foreach (var e in Exts)
            try { using var k = Registry.CurrentUser.OpenSubKey($@"Software\Classes\{e}\OpenWithProgids", true); k?.DeleteValue(ProgId, false); } catch { }
        try { File.Delete(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.SendTo), "Телефон (Передатчик).lnk")); } catch { }
        try { using var run = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run", true); run?.DeleteValue("Peredatchik", false); } catch { }
        SHChangeNotify(0x08000000, 0, IntPtr.Zero, IntPtr.Zero);
    }

    public static bool Autostart
    {
        get { using var run = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run"); return run?.GetValue("Peredatchik") != null; }
        set
        {
            using var run = Registry.CurrentUser.CreateSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run");
            if (value) run.SetValue("Peredatchik", $"\"{Exe}\" --tray"); else run.DeleteValue("Peredatchik", false);
        }
    }

    static void CreateShortcut(string lnk, string target, string args, string desc)
    {
        var t = Type.GetTypeFromProgID("WScript.Shell");
        dynamic sh = Activator.CreateInstance(t);
        dynamic s = sh.CreateShortcut(lnk);
        s.TargetPath = target; s.Arguments = args; s.Description = desc; s.IconLocation = target + ",0";
        s.Save();
    }

    [System.Runtime.InteropServices.DllImport("shell32.dll")]
    static extern void SHChangeNotify(int eventId, int flags, IntPtr item1, IntPtr item2);

    /// Разрешить входящие в брандмауэре (запрос прав администратора).
    public static void AddFirewallRules()
    {
        var exe = Exe;
        var cmd = $"/c netsh advfirewall firewall delete rule name=\"Передатчик\" & " +
                  $"netsh advfirewall firewall add rule name=\"Передатчик\" dir=in action=allow program=\"{exe}\" enable=yes profile=private,domain & " +
                  $"netsh advfirewall firewall add rule name=\"Передатчик\" dir=in action=allow protocol=UDP localport=47231 profile=private,domain & " +
                  $"netsh advfirewall firewall add rule name=\"Передатчик\" dir=in action=allow protocol=TCP localport=47232 profile=private,domain";
        System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo("cmd.exe", cmd) { Verb = "runas", UseShellExecute = true, WindowStyle = System.Diagnostics.ProcessWindowStyle.Hidden });
    }
}
