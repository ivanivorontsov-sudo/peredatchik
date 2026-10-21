package ru.peredatchik.core;

import java.io.*;

public interface FileSource {
    String name();
    long size();
    String mime();
    InputStream open() throws IOException;

    static FileSource of(File f) {
        return new FileSource() {
            public String name() { return f.getName(); }
            public long size() { return f.length(); }
            public String mime() { return Mime.guess(f.getName()); }
            public InputStream open() throws IOException { return new FileInputStream(f); }
        };
    }
}
