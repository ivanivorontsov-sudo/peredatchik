using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;

namespace Peredatchik.Core;

/// Защищённый канал — побайтно совместим с SecureChannel.java (см. описание там).
public sealed class SecureChannel : IDisposable
{
    public const int Version = 1;
    public const int MaxFrame = (1 << 20) + 1024;
    public const byte TJson = (byte)'J', TData = (byte)'D';
    static readonly byte[] Info = Encoding.ASCII.GetBytes("peredatchik v1");

    private readonly Stream _in, _out;
    private readonly IDisposable _link;
    private byte[] _kSend, _kRecv;
    private AesGcm _gSend, _gRecv;
    private long _sc, _rc;
    public Peer Peer;
    public bool NewlyPaired;

    private SecureChannel(Stream i, Stream o, IDisposable link)
    {
        _in = new BufferedStream(i, 1 << 16); _out = new BufferedStream(o, 1 << 16); _link = link;
    }

    void WriteFrame(byte[] b)
    {
        Span<byte> h = stackalloc byte[4]; BinaryPrimitives.WriteInt32BigEndian(h, b.Length);
        _out.Write(h); _out.Write(b); _out.Flush();
    }

    byte[] ReadFrame()
    {
        var h = new byte[4]; _in.ReadExactly(h);
        int n = BinaryPrimitives.ReadInt32BigEndian(h);
        if (n < 0 || n > MaxFrame) throw new ProtocolException("слишком большой кадр");
        var b = new byte[n]; _in.ReadExactly(b); return b;
    }

    static byte[] Nonce(long c) { var n = new byte[12]; BinaryPrimitives.WriteInt64BigEndian(n.AsSpan(4), c); return n; }

    public void Send(byte type, byte[] data, int off, int len)
    {
        var p = new byte[len + 1]; p[0] = type; Buffer.BlockCopy(data, off, p, 1, len);
        var ct = new byte[p.Length + 16];
        _gSend.Encrypt(Nonce(_sc++), p, ct.AsSpan(0, p.Length), ct.AsSpan(p.Length));
        WriteFrame(ct);
    }

    public void SendJson(JsonObject o) { var b = J.Bytes(o); Send(TJson, b, 0, b.Length); }

    public byte[] Recv()
    {
        var f = ReadFrame();
        if (f.Length < 17) throw new ProtocolException("короткий кадр");
        var p = new byte[f.Length - 16];
        try { _gRecv.Decrypt(Nonce(_rc++), f.AsSpan(0, p.Length), f.AsSpan(p.Length), p); }
        catch (CryptographicException) { throw new ProtocolException("ошибка расшифровки — канал подменён или повреждён"); }
        return p;
    }

    public JsonObject RecvJson()
    {
        var p = Recv();
        if (p[0] != TJson) throw new ProtocolException("ожидалось сообщение, пришли данные");
        return J.Parse(p, 1);
    }

    public void Dispose() { try { _link?.Dispose(); } catch { } _gSend?.Dispose(); _gRecv?.Dispose(); }

    static byte[] Hello(Identity me, ECDiffieHellman eph, byte[] n) => J.Bytes(new JsonObject
    {
        ["t"] = "hello", ["v"] = Version, ["id"] = me.Id, ["name"] = me.Name,
        ["spub"] = Convert.ToBase64String(me.Pub), ["epub"] = Convert.ToBase64String(Crypto.EncodePub(eph)), ["n"] = Convert.ToBase64String(n)
    });

    void Derive(Identity me, ECDiffieHellman eph, JsonObject other, byte[] th, bool client)
    {
        var e = Crypto.Ecdh(eph, Convert.FromBase64String(J.S(other, "epub")));
        var s = Crypto.Ecdh(me.Key.K, Convert.FromBase64String(J.S(other, "spub")));
        var okm = Crypto.Hkdf(e.Concat(s).ToArray(), th, Info, 64);
        var c2s = okm[..32]; var s2c = okm[32..];
        _kSend = client ? c2s : s2c; _kRecv = client ? s2c : c2s;
        _gSend = new AesGcm(_kSend, 16); _gRecv = new AesGcm(_kRecv, 16);
    }

    static JsonObject CheckHello(byte[] raw)
    {
        JsonObject h;
        try { h = J.Parse(raw, 0); } catch (Exception) { throw new ProtocolException("это не «Передатчик»"); }
        if (J.S(h, "t") != "hello") throw new ProtocolException("это не «Передатчик»");
        if (J.L(h, "v") != Version) throw new ProtocolException("несовместимая версия протокола");
        if (Crypto.Fingerprint(Convert.FromBase64String(J.S(h, "spub"))) != J.S(h, "id")) throw new ProtocolException("id не совпадает с ключом");
        return h;
    }

    public static SecureChannel Client(Stream i, Stream o, IDisposable link, Identity me, PeerStore store, string pairCode, string expectId)
    {
        var ch = new SecureChannel(i, o, link);
        try
        {
            using var eph = Crypto.Generate();
            var hc = Hello(me, eph, Crypto.Random(16));
            ch.WriteFrame(hc);
            var hsRaw = ch.ReadFrame();
            var hs = CheckHello(hsRaw);
            var th = Crypto.Sha256(hc, hsRaw);
            ch.Derive(me, eph, hs, th, true);
            var id = J.S(hs, "id"); var spub = Convert.FromBase64String(J.S(hs, "spub"));
            if (expectId != null && !string.Equals(expectId, id, StringComparison.OrdinalIgnoreCase)) throw new ProtocolException("отпечаток ключа не совпадает с QR-кодом");
            var known = store.Get(id);
            if (pairCode == null)
            {
                if (known == null) throw new ProtocolException("устройство не сопряжено");
                if (!Crypto.Eq(known.Pub, spub)) throw new ProtocolException("ключ устройства изменился — сопрягите заново");
                ch.SendJson(new JsonObject { ["t"] = "auth" });
            }
            else
            {
                var mac = Crypto.Hmac(Crypto.CodeKey(pairCode), "C"u8.ToArray(), th);
                ch.SendJson(new JsonObject { ["t"] = "pair", ["mac"] = Convert.ToBase64String(mac) });
            }
            var r = ch.RecvJson();
            if (J.S(r, "t") == "err") throw new ProtocolException(J.S(r, "msg"));
            if (J.S(r, "t") != "ok") throw new ProtocolException("неожиданный ответ");
            if (pairCode != null)
            {
                var mac = Crypto.Hmac(Crypto.CodeKey(pairCode), "S"u8.ToArray(), th);
                if (J.S(r, "mac") == null || !Crypto.Eq(mac, Convert.FromBase64String(J.S(r, "mac")))) throw new ProtocolException("ответ сопряжения не подтверждён");
                var p = known != null && Crypto.Eq(known.Pub, spub) ? known : new Peer { Id = id, Pub = spub };
                p.Name = J.S(hs, "name");
                store.Put(p);
                ch.NewlyPaired = true; known = p;
            }
            ch.Peer = known;
            return ch;
        }
        catch (Exception e)
        {
            ch.Dispose();
            if (e is IOException) throw;
            throw new ProtocolException("ошибка рукопожатия: " + e.Message);
        }
    }

    public static SecureChannel Server(Stream i, Stream o, IDisposable link, Identity me, PeerStore store, Pairing pairing)
    {
        var ch = new SecureChannel(i, o, link);
        try
        {
            var hcRaw = ch.ReadFrame();
            var hc = CheckHello(hcRaw);
            using var eph = Crypto.Generate();
            var hs = Hello(me, eph, Crypto.Random(16));
            ch.WriteFrame(hs);
            var th = Crypto.Sha256(hcRaw, hs);
            ch.Derive(me, eph, hc, th, false);
            var id = J.S(hc, "id"); var spub = Convert.FromBase64String(J.S(hc, "spub"));
            var known = store.Get(id);
            var m = ch.RecvJson();
            var t = J.S(m, "t");
            if (t == "auth")
            {
                if (known == null || !Crypto.Eq(known.Pub, spub))
                {
                    ch.SendJson(new JsonObject { ["t"] = "err", ["msg"] = $"устройство не сопряжено с «{me.Name}»" });
                    throw new ProtocolException("попытка подключения несопряжённого устройства");
                }
                ch.SendJson(new JsonObject { ["t"] = "ok" });
            }
            else if (t == "pair")
            {
                if (pairing == null || !pairing.Active)
                {
                    ch.SendJson(new JsonObject { ["t"] = "err", ["msg"] = $"на «{me.Name}» не открыто окно сопряжения" });
                    throw new ProtocolException("сопряжение не открыто");
                }
                var mac = Crypto.Hmac(Crypto.CodeKey(pairing.Code), "C"u8.ToArray(), th);
                if (J.S(m, "mac") == null || !Crypto.Eq(mac, Convert.FromBase64String(J.S(m, "mac"))))
                {
                    pairing.Fail();
                    ch.SendJson(new JsonObject { ["t"] = "err", ["msg"] = "неверный код сопряжения" });
                    throw new ProtocolException("неверный код");
                }
                pairing.Done();
                var p = new Peer { Id = id, Pub = spub, Name = J.S(hc, "name") };
                if (known != null && Crypto.Eq(known.Pub, spub)) { p.LastHost = known.LastHost; p.LastPort = known.LastPort; p.BtAddress = known.BtAddress; }
                store.Put(p); known = p; ch.NewlyPaired = true;
                ch.SendJson(new JsonObject { ["t"] = "ok", ["mac"] = Convert.ToBase64String(Crypto.Hmac(Crypto.CodeKey(pairing.Code), "S"u8.ToArray(), th)) });
            }
            else throw new ProtocolException("неожиданное сообщение");
            known.Name = J.S(hc, "name");
            ch.Peer = known;
            return ch;
        }
        catch (Exception e)
        {
            ch.Dispose();
            if (e is IOException) throw;
            throw new ProtocolException("ошибка рукопожатия: " + e.Message);
        }
    }
}
