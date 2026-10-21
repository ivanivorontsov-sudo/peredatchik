package ru.peredatchik.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Хранилище сопряжённых устройств в JSON-файле. */
public class FilePeerStore extends MemoryPeerStore {
    private final File file;

    @SuppressWarnings("unchecked")
    public FilePeerStore(File file) {
        this.file = file;
        try {
            if (file.exists()) {
                List<Object> l = (List<Object>) Json.parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                for (Object o : l) { Peer p = Peer.fromMap((Map<String, Object>) o); peers.put(p.id, p); }
            }
        } catch (Exception ignored) {}
    }

    public synchronized void remove(String id) { peers.remove(id); saved(); }

    @Override protected void saved() {
        List<Object> l = new ArrayList<>();
        for (Peer p : peers.values()) l.add(p.toMap());
        try {
            File tmp = new File(file.getPath() + ".tmp");
            Files.write(tmp.toPath(), Json.write(l).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {}
    }

    /** Загрузить или создать ключ устройства в файле identity.json. */
    public static Identity identity(File f, String defaultName) {
        try {
            if (f.exists()) {
                Map<String, Object> m = Json.obj(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                return Identity.load((String) m.get("priv"), (String) m.get("pub"), (String) m.get("name"));
            }
        } catch (Exception ignored) {}
        Identity id = Identity.create(defaultName);
        saveIdentity(f, id);
        return id;
    }

    public static void saveIdentity(File f, Identity id) {
        try {
            Files.write(f.toPath(), Json.write(Json.map("priv", id.privB64(), "pub", id.pubB64(), "name", id.name)).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {}
    }
}
