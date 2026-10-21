using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Peredatchik.Core;

public sealed class Identity
{
    public ECDiffieHellmanHolder Key;
    public byte[] Pub;
    public string Id;
    public string Name;

    public static Identity Create(string name)
    {
        var k = Crypto.Generate();
        return new Identity { Key = new ECDiffieHellmanHolder(k), Pub = Crypto.EncodePub(k), Id = Crypto.Fingerprint(Crypto.EncodePub(k)), Name = name };
    }

    public static Identity LoadOrCreate(string path, string defName)
    {
        try
        {
            if (File.Exists(path))
            {
                var o = JsonNode.Parse(File.ReadAllText(path));
                var k = Crypto.Generate();
                k.ImportPkcs8PrivateKey(Convert.FromBase64String((string)o["priv"]), out _);
                var pub = Crypto.EncodePub(k);
                return new Identity { Key = new ECDiffieHellmanHolder(k), Pub = pub, Id = Crypto.Fingerprint(pub), Name = (string)o["name"] ?? defName };
            }
        }
        catch { }
        var id = Create(defName);
        id.Save(path);
        return id;
    }

    public void Save(string path)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(path)));
        var o = new JsonObject { ["priv"] = Convert.ToBase64String(Key.K.ExportPkcs8PrivateKey()), ["pub"] = Convert.ToBase64String(Pub), ["name"] = Name };
        File.WriteAllText(path, o.ToJsonString());
    }
}

public sealed class ECDiffieHellmanHolder
{
    public readonly System.Security.Cryptography.ECDiffieHellman K;
    public ECDiffieHellmanHolder(System.Security.Cryptography.ECDiffieHellman k) { K = k; }
}

public sealed class Peer
{
    public string Id; public byte[] Pub; public string Name;
    public string LastHost; public int LastPort; public string BtAddress;

    public JsonObject ToJson() => new()
    {
        ["id"] = Id, ["pub"] = Convert.ToBase64String(Pub), ["name"] = Name,
        ["host"] = LastHost, ["port"] = LastPort, ["bt"] = BtAddress
    };

    public static Peer FromJson(JsonNode o)
    {
        var pub = Convert.FromBase64String((string)o["pub"]);
        return new Peer
        {
            Id = Crypto.Fingerprint(pub), Pub = pub, Name = (string)o["name"],
            LastHost = (string)o["host"], LastPort = o["port"] is null ? 0 : (int)o["port"], BtAddress = (string)o["bt"]
        };
    }

    public static string RelayRoom(byte[] a, byte[] b)
    {
        bool ab = string.CompareOrdinal(Crypto.Hex(a), Crypto.Hex(b)) < 0;
        return Crypto.Hex(Crypto.Sha256(ab ? a : b, ab ? b : a))[..32];
    }
}

public class PeerStore
{
    private readonly string _path;
    private readonly Dictionary<string, Peer> _peers = new();
    public event Action Changed;

    public PeerStore(string path)
    {
        _path = path;
        try
        {
            if (path != null && File.Exists(path))
                foreach (var n in JsonNode.Parse(File.ReadAllText(path)).AsArray()) { var p = Peer.FromJson(n); _peers[p.Id] = p; }
        }
        catch { }
    }

    public Peer Get(string id) { lock (_peers) return _peers.TryGetValue(id, out var p) ? p : null; }
    public List<Peer> All() { lock (_peers) return _peers.Values.ToList(); }
    public void Put(Peer p) { lock (_peers) _peers[p.Id] = p; Save(); }
    public void Remove(string id) { lock (_peers) _peers.Remove(id); Save(); }

    private void Save()
    {
        if (_path != null)
        {
            JsonArray a;
            lock (_peers) a = new JsonArray(_peers.Values.Select(p => (JsonNode)p.ToJson()).ToArray());
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(_path)));
                File.WriteAllText(_path + ".tmp", a.ToJsonString());
                File.Move(_path + ".tmp", _path, true);
            }
            catch { }
        }
        Changed?.Invoke();
    }
}

public sealed class Pairing
{
    public readonly string Code;
    private readonly DateTime _exp;
    private int _fail; private bool _used;
    public Pairing(string code, TimeSpan ttl) { Code = code; _exp = DateTime.UtcNow + ttl; }
    public static Pairing NewRandom(TimeSpan ttl) => new(System.Security.Cryptography.RandomNumberGenerator.GetInt32(1_000_000).ToString("D6"), ttl);
    public bool Active { get { lock (this) return !_used && _fail < 5 && DateTime.UtcNow < _exp; } }
    public void Fail() { lock (this) _fail++; }
    public void Done() { lock (this) _used = true; }
    public int SecondsLeft => Math.Max(0, (int)(_exp - DateTime.UtcNow).TotalSeconds);
}

public class ProtocolException : IOException { public ProtocolException(string m) : base(m) { } }

public enum Method { Auto, Wifi, UsbAdb, Bluetooth, Relay, Manual }

public static class MethodInfo
{
    public static string Title(Method m) => m switch
    {
        Method.Auto => "Авто (лучший доступный)",
        Method.Wifi => "Wi-Fi: одна сеть / точка доступа телефона / USB-модем",
        Method.UsbAdb => "USB-кабель через ADB (adb reverse)",
        Method.Bluetooth => "Bluetooth",
        Method.Relay => "Через интернет (ретранслятор)",
        _ => "Вручную: IP-адрес и порт",
    };
}

public static class Mime
{
    public static string Guess(string name) => Path.GetExtension(name).ToLowerInvariant() switch
    {
        ".jpg" or ".jpeg" => "image/jpeg", ".png" => "image/png", ".gif" => "image/gif", ".webp" => "image/webp",
        ".bmp" => "image/bmp", ".heic" => "image/heic", ".mp4" => "video/mp4", ".mov" => "video/quicktime",
        ".pdf" => "application/pdf", ".txt" => "text/plain", ".zip" => "application/zip",
        _ => "application/octet-stream",
    };

    public static string SafeName(string name)
    {
        var n = name ?? "";
        n = n[(Math.Max(n.LastIndexOf('/'), n.LastIndexOf('\\')) + 1)..];
        n = Regex.Replace(n, "[<>:\"|?*\\x00-\\x1f]", "_").Trim();
        n = n.TrimStart('.');
        if (n.Length == 0) n = "file";
        if (n.Length > 150) n = n[^150..];
        return n;
    }
}

public sealed class FileSource
{
    public string Name; public long Size; public string MimeType; public Func<Stream> Open;
    public static FileSource Of(string path)
    {
        var fi = new FileInfo(path);
        return new FileSource { Name = fi.Name, Size = fi.Length, MimeType = Mime.Guess(fi.Name), Open = () => File.OpenRead(path) };
    }
}

public delegate void Progress(string name, int index, int count, long done, long total);

public interface ISink
{
    string PartDir { get; }
    string Complete(string partPath, string name, string mime, Peer from);
    bool Accept(Peer from, List<string> names, long total) => true;
}

public class DirSink : ISink
{
    public readonly string Dir;
    public DirSink(string dir) { Dir = dir; }
    public string PartDir => Path.Combine(Dir, ".partial");
    public virtual string Complete(string part, string name, string mime, Peer from)
    {
        Directory.CreateDirectory(Dir);
        var dst = Unique(Dir, name);
        File.Move(part, dst, true);
        return dst;
    }
    public static string Unique(string dir, string name)
    {
        var f = Path.Combine(dir, name);
        var b = Path.GetFileNameWithoutExtension(name); var e = Path.GetExtension(name);
        if (name.StartsWith(".") || b.Length == 0) { b = name; e = ""; }
        for (int i = 2; File.Exists(f); i++) f = Path.Combine(dir, $"{b} ({i}){e}");
        return f;
    }
}

internal static class J
{
    public static byte[] Bytes(JsonObject o) => Encoding.UTF8.GetBytes(o.ToJsonString(new JsonSerializerOptions { TypeInfoResolver = new System.Text.Json.Serialization.Metadata.DefaultJsonTypeInfoResolver(), Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping }));
    public static JsonObject Parse(byte[] b, int off) => JsonNode.Parse(Encoding.UTF8.GetString(b, off, b.Length - off)) as JsonObject ?? throw new ProtocolException("плохое сообщение");
    public static string S(JsonNode o, string k) => o?[k]?.GetValue<string>();
    public static long L(JsonNode o, string k) => o?[k] is JsonValue v ? (v.TryGetValue<long>(out var l) ? l : (long)v.GetValue<double>()) : 0;
}
