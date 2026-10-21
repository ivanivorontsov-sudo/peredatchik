package ru.peredatchik.core;

import java.io.*;
import java.nio.file.*;

/** Приём в обычную папку (ПК, тесты). Совпадающие имена получают суффикс « (2)». */
public class DirSink implements Sink {
    private final File dir;
    public DirSink(File dir) { this.dir = dir; }
    public File partDir() { return new File(dir, ".partial"); }
    public String complete(File part, String name, String mime, Peer from) throws IOException {
        dir.mkdirs();
        File dst = unique(dir, name);
        Files.move(part.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return dst.getAbsolutePath();
    }
    public static File unique(File dir, String name) {
        File f = new File(dir, name);
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) { base = name.substring(0, dot); ext = name.substring(dot); }
        for (int i = 2; f.exists(); i++) f = new File(dir, base + " (" + i + ")" + ext);
        return f;
    }
}
