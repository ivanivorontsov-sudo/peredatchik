package ru.peredatchik.core;

import java.io.*;
import java.util.*;

/**
 * Протокол передачи поверх SecureChannel.
 * S: offer{files:[{name,size,mime,sha}]}  R: accept{off:[...]} | reject{msg}
 * для каждого файла: D-кадры (с off до size), затем end{i};  R: got{i,ok}
 * S: bye.  Недокачанное хранится как <partDir>/<peer>-<sha16>.part, повтор докачивает.
 */
public final class Transfer {
    private Transfer() {}
    public static final int CHUNK = 256 * 1024;

    public static String sha256Hex(FileSource f) throws IOException {
        try (InputStream in = f.open()) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[CHUNK]; int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return Crypto.hex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
    }

    public static void send(SecureChannel ch, List<FileSource> files, List<String> shas, Progress pr) throws IOException {
        List<Object> list = new ArrayList<>();
        long total = 0;
        for (int i = 0; i < files.size(); i++) {
            FileSource f = files.get(i);
            list.add(Json.map("name", Mime.safeName(f.name()), "size", f.size(), "mime", f.mime(), "sha", shas.get(i)));
            total += f.size();
        }
        ch.sendJson(Json.map("t", "offer", "files", list));
        Map<String, Object> r = ch.recvJson();
        if ("reject".equals(r.get("t"))) throw new ProtocolException("получатель отказался: " + r.get("msg"));
        if (!"accept".equals(r.get("t"))) throw new ProtocolException("неожиданный ответ на предложение");
        List<?> offs = (List<?>) r.get("off");
        long done = 0;
        for (int i = 0; i < files.size(); i++) done += Math.min(Json.num(offs.get(i)), files.get(i).size());
        byte[] buf = new byte[CHUNK];
        for (int i = 0; i < files.size(); i++) {
            FileSource f = files.get(i);
            long off = Math.min(Json.num(offs.get(i)), f.size());
            try (InputStream in = f.open()) {
                long skipped = 0;
                while (skipped < off) { long s = in.skip(off - skipped); if (s <= 0) { if (in.read() < 0) throw new EOFException(); s = 1; } skipped += s; }
                long left = f.size() - off;
                pr.update(f.name(), i, files.size(), done, total);
                while (left > 0) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n < 0) throw new IOException("файл «" + f.name() + "» стал короче во время отправки");
                    ch.send(SecureChannel.T_DATA, buf, 0, n);
                    left -= n; done += n;
                    pr.update(f.name(), i, files.size(), done, total);
                }
            }
            ch.sendJson(Json.map("t", "end", "i", (long) i));
            Map<String, Object> g = ch.recvJson();
            if (!"got".equals(g.get("t")) || !Boolean.TRUE.equals(g.get("ok")))
                throw new ProtocolException("файл «" + f.name() + "» повреждён при передаче, повторите");
        }
        ch.sendJson(Json.map("t", "bye"));
    }

    /** Принять все пакеты в соединении до bye. Возвращает пути сохранённых файлов. */
    @SuppressWarnings("unchecked")
    public static List<String> receive(SecureChannel ch, Sink sink, Progress pr) throws IOException {
        List<String> saved = new ArrayList<>();
        while (true) {
            Map<String, Object> m;
            try { m = ch.recvJson(); } catch (EOFException e) { return saved; }
            String t = (String) m.get("t");
            if ("bye".equals(t)) return saved;
            if ("ping".equals(t)) { ch.sendJson(Json.map("t", "pong")); continue; }
            if (!"offer".equals(t)) throw new ProtocolException("неожиданное сообщение " + t);
            List<Map<String, Object>> files = (List<Map<String, Object>>) m.get("files");
            List<String> names = new ArrayList<>(); long total = 0;
            for (Map<String, Object> f : files) { names.add((String) f.get("name")); total += Json.num(f.get("size")); }
            if (!sink.accept(ch.peer, names, total)) { ch.sendJson(Json.map("t", "reject", "msg", "отклонено пользователем")); continue; }
            File dir = sink.partDir(); dir.mkdirs();
            List<Object> offs = new ArrayList<>(); File[] parts = new File[files.size()];
            long done = 0;
            for (int i = 0; i < files.size(); i++) {
                Map<String, Object> f = files.get(i);
                String sha = ((String) f.get("sha")).toLowerCase(Locale.ROOT);
                if (!sha.matches("[0-9a-f]{64}")) throw new ProtocolException("плохая контрольная сумма");
                parts[i] = new File(dir, ch.peer.id + "-" + sha.substring(0, 16) + ".part");
                long size = Json.num(f.get("size"));
                long have = parts[i].exists() ? parts[i].length() : 0;
                if (have > size) { parts[i].delete(); have = 0; }
                offs.add(have); done += have;
            }
            ch.sendJson(Json.map("t", "accept", "off", offs));
            for (int i = 0; i < files.size(); i++) {
                Map<String, Object> f = files.get(i);
                String name = Mime.safeName((String) f.get("name"));
                long size = Json.num(f.get("size"));
                long have = Json.num(offs.get(i));
                try (FileOutputStream out = new FileOutputStream(parts[i], true)) {
                    pr.update(name, i, files.size(), done, total);
                    while (true) {
                        byte[] p = ch.recv();
                        if (p[0] == SecureChannel.T_DATA) {
                            if (have + p.length - 1 > size) throw new ProtocolException("лишние данные");
                            out.write(p, 1, p.length - 1);
                            have += p.length - 1; done += p.length - 1;
                            pr.update(name, i, files.size(), done, total);
                        } else {
                            Map<String, Object> e = Json.obj(new String(p, 1, p.length - 1, "UTF-8"));
                            if (!"end".equals(e.get("t"))) throw new ProtocolException("ожидался конец файла");
                            break;
                        }
                    }
                }
                String sha = Transfer.sha256Hex(FileSource.of(parts[i]));
                boolean ok = have == size && sha.equalsIgnoreCase((String) f.get("sha"));
                if (!ok) parts[i].delete();
                else saved.add(sink.complete(parts[i], name, (String) f.get("mime"), ch.peer));
                ch.sendJson(Json.map("t", "got", "i", (long) i, "ok", ok));
            }
        }
    }
}
