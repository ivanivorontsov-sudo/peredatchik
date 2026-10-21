using System.Security.Cryptography;
using System.Text;

namespace Peredatchik.Core;

/// ECDH P-256 + HKDF-SHA256 + AES-256-GCM — совместимо с ru.peredatchik.core.Crypto (Android).
public static class Crypto
{
    public static ECDiffieHellman Generate() => ECDiffieHellman.Create(ECCurve.NamedCurves.nistP256);

    public static byte[] EncodePub(ECDiffieHellman k)
    {
        var p = k.ExportParameters(false);
        var o = new byte[65]; o[0] = 4;
        Buffer.BlockCopy(p.Q.X, 0, o, 1 + 32 - p.Q.X.Length, p.Q.X.Length);
        Buffer.BlockCopy(p.Q.Y, 0, o, 33 + 32 - p.Q.Y.Length, p.Q.Y.Length);
        return o;
    }

    public static ECDiffieHellman DecodePub(byte[] b)
    {
        if (b.Length != 65 || b[0] != 4) throw new ArgumentException("плохой открытый ключ");
        var e = ECDiffieHellman.Create();
        e.ImportParameters(new ECParameters { Curve = ECCurve.NamedCurves.nistP256, Q = new ECPoint { X = b[1..33], Y = b[33..65] } });
        return e;
    }

    public static byte[] Ecdh(ECDiffieHellman priv, byte[] otherPub)
    {
        using var o = DecodePub(otherPub);
        return priv.DeriveRawSecretAgreement(o.PublicKey);
    }

    public static byte[] Hmac(byte[] key, params byte[][] data)
    {
        using var h = new HMACSHA256(key);
        foreach (var d in data) h.TransformBlock(d, 0, d.Length, null, 0);
        h.TransformFinalBlock(Array.Empty<byte>(), 0, 0);
        return h.Hash;
    }

    public static byte[] Sha256(params byte[][] data)
    {
        using var h = SHA256.Create();
        foreach (var d in data) h.TransformBlock(d, 0, d.Length, null, 0);
        h.TransformFinalBlock(Array.Empty<byte>(), 0, 0);
        return h.Hash;
    }

    public static byte[] Hkdf(byte[] ikm, byte[] salt, byte[] info, int len)
    {
        var prk = Hmac(salt, ikm);
        var o = new byte[len]; var t = Array.Empty<byte>(); int pos = 0;
        for (int i = 1; pos < len; i++)
        {
            t = Hmac(prk, t, info, new[] { (byte)i });
            int n = Math.Min(t.Length, len - pos);
            Buffer.BlockCopy(t, 0, o, pos, n); pos += n;
        }
        return o;
    }

    public static string Hex(byte[] b) => Convert.ToHexString(b).ToLowerInvariant();
    public static string Fingerprint(byte[] pub) => Hex(Sha256(pub)[..8]);
    public static byte[] CodeKey(string code) => Sha256(Encoding.UTF8.GetBytes("peredatchik-code:" + code.Trim()));
    public static bool Eq(byte[] a, byte[] b) => a != null && b != null && CryptographicOperations.FixedTimeEquals(a, b);
    public static byte[] Random(int n) => RandomNumberGenerator.GetBytes(n);
}
