using System.Text.Json;
using Peredatchik.Core;

namespace Peredatchik;

public sealed class Settings
{
    public string InboxDir { get; set; } = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyPictures), "Передатчик");
    public Method DefaultMethod { get; set; } = Method.Auto;
    public string Relay { get; set; } = "";
    public string Manual { get; set; } = "";
    public bool OpenFolderAfterReceive { get; set; } = false;
    public bool AskBeforeSend { get; set; } = true;
    public string LastPeer { get; set; }

    static string PathFile => Path.Combine(Program.DataDir, "settings.json");
    public static Settings Load()
    {
        try { if (File.Exists(PathFile)) return JsonSerializer.Deserialize<Settings>(File.ReadAllText(PathFile)) ?? new Settings(); } catch { }
        return new Settings();
    }
    public void Save()
    {
        Directory.CreateDirectory(Program.DataDir);
        File.WriteAllText(PathFile, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
    }
}
