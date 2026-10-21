using System.Security.Cryptography;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Peredatchik.Core;

/// Передача файлов — совместима с Transfer.java.
public static class Transfer
{
    public const int Chunk = 256 * 1024;

    public static string Sha256Hex(FileSource f)
    {
        using var s = f.Open();
        using var h = SHA256.Create();
        return Crypto.Hex(h.ComputeHash(s));
    }

    public static void Send(SecureChannel ch, List<FileSource> files, List<string> shas, Progress pr)
    {
        pr ??= (_, _, _, _, _) => { };
        var list = new JsonArray(); long total = 0;
        for (int i = 0; i < files.Count; i++)
        {
            var f = files[i];
            list.Add(new JsonObject { ["name"] = Mime.SafeName(f.Name), ["size"] = f.Size, ["mime"] = f.MimeType, ["sha"] = shas[i] });
            total += f.Size;
        }
        ch.SendJson(new JsonObject { ["t"] = "offer", ["files"] = list });
        var r = ch.RecvJson();
        if (J.S(r, "t") == "reject") throw new ProtocolException("получатель отказался: " + J.S(r, "msg"));
        if (J.S(r, "t") != "accept") throw new ProtocolException("неожиданный ответ на предложение");
        var offs = r["off"].AsArray().Select(x => x.GetValue<long>()).ToList();
        long done = 0;
        for (int i = 0; i < files.Count; i++) done += Math.Min(offs[i], files[i].Size);
        var buf = new byte[Chunk];
        for (int i = 0; i < files.Count; i++)
        {
            var f = files[i];
            long off = Math.Min(offs[i], f.Size);
            using (var s = f.Open())
            {
                if (s.CanSeek) s.Seek(off, SeekOrigin.Begin);
                else { long sk = off; while (sk > 0) { int n = s.Read(buf, 0, (int)Math.Min(buf.Length, sk)); if (n <= 0) throw new EndOfStreamException(); sk -= n; } }
                long left = f.Size - off;
                pr(f.Name, i, files.Count, done, total);
                while (left > 0)
                {
                    int n = s.Read(buf, 0, (int)Math.Min(buf.Length, left));
                    if (n <= 0) throw new IOException($"файл «{f.Name}» стал короче во время отправки");
                    ch.Send(SecureChannel.TData, buf, 0, n);
                    left -= n; done += n;
                    pr(f.Name, i, files.Count, done, total);
                }
            }
            ch.SendJson(new JsonObject { ["t"] = "end", ["i"] = i });
            var g = ch.RecvJson();
            if (J.S(g, "t") != "got" || g["ok"]?.GetValue<bool>() != true)
                throw new ProtocolException($"файл «{f.Name}» повреждён при передаче, повторите");
        }
        ch.SendJson(new JsonObject { ["t"] = "bye" });
    }

    public static List<string> Receive(SecureChannel ch, ISink sink, Progress pr)
    {
        pr ??= (_, _, _, _, _) => { };
        var saved = new List<string>();
        while (true)
        {
            JsonObject m;
            try { m = ch.RecvJson(); } catch (EndOfStreamException) { return saved; }
            var t = J.S(m, "t");
            if (t == "bye") return saved;
            if (t == "ping") { ch.SendJson(new JsonObject { ["t"] = "pong" }); continue; }
            if (t != "offer") throw new ProtocolException("неожиданное сообщение " + t);
            var files = m["files"].AsArray();
            var names = files.Select(f => J.S(f, "name")).ToList();
            long total = files.Sum(f => J.L(f, "size"));
            if (!sink.Accept(ch.Peer, names, total)) { ch.SendJson(new JsonObject { ["t"] = "reject", ["msg"] = "отклонено пользователем" }); continue; }
            Directory.CreateDirectory(sink.PartDir);
            var offs = new JsonArray(); var parts = new string[files.Count]; long done = 0;
            for (int i = 0; i < files.Count; i++)
            {
                var sha = J.S(files[i], "sha").ToLowerInvariant();
                if (!Regex.IsMatch(sha, "^[0-9a-f]{64}$")) throw new ProtocolException("плохая контрольная сумма");
                parts[i] = Path.Combine(sink.PartDir, ch.Peer.Id + "-" + sha[..16] + ".part");
                long size = J.L(files[i], "size");
                long have = File.Exists(parts[i]) ? new FileInfo(parts[i]).Length : 0;
                if (have > size) { File.Delete(parts[i]); have = 0; }
                offs.Add(JsonValue.Create(have)); done += have;
            }
            ch.SendJson(new JsonObject { ["t"] = "accept", ["off"] = offs });
            for (int i = 0; i < files.Count; i++)
            {
                var f = files[i];
                var name = Mime.SafeName(J.S(f, "name"));
                long size = J.L(f, "size"), have = offs[i].GetValue<long>();
                using (var o = new FileStream(parts[i], FileMode.Append, FileAccess.Write))
                {
                    pr(name, i, files.Count, done, total);
                    while (true)
                    {
                        var p = ch.Recv();
                        if (p[0] == SecureChannel.TData)
                        {
                            if (have + p.Length - 1 > size) throw new ProtocolException("лишние данные");
                            o.Write(p, 1, p.Length - 1);
                            have += p.Length - 1; done += p.Length - 1;
                            pr(name, i, files.Count, done, total);
                        }
                        else
                        {
                            if (J.S(J.Parse(p, 1), "t") != "end") throw new ProtocolException("ожидался конец файла");
                            break;
                        }
                    }
                }
                var got = Sha256Hex(FileSource.Of(parts[i]));
                bool ok = have == size && string.Equals(got, J.S(f, "sha"), StringComparison.OrdinalIgnoreCase);
                if (!ok) File.Delete(parts[i]);
                else saved.Add(sink.Complete(parts[i], name, J.S(f, "mime"), ch.Peer));
                ch.SendJson(new JsonObject { ["t"] = "got", ["i"] = i, ["ok"] = ok });
            }
        }
    }
}
