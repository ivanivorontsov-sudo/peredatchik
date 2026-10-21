package ru.peredatchik.app;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.*;
import ru.peredatchik.core.FileSource;
import ru.peredatchik.core.Mime;

/** Файл из галереи/проводника (content://). */
final class UriSource implements FileSource {
    private final ContentResolver cr; private final Uri uri;
    private String name; private long size = -1; private final String mime;

    UriSource(Context c, Uri uri) {
        this.cr = c.getContentResolver(); this.uri = uri;
        try (Cursor q = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (q != null && q.moveToFirst()) {
                name = q.getString(0);
                if (!q.isNull(1)) size = q.getLong(1);
            }
        } catch (Exception ignored) {}
        if (name == null) name = uri.getLastPathSegment() != null ? uri.getLastPathSegment() : "file";
        String m = cr.getType(uri);
        mime = m != null ? m : Mime.guess(name);
        if (!name.contains(".") && mime.startsWith("image/")) name += "." + mime.substring(6).replace("jpeg", "jpg");
        if (size < 0) { // размер неизвестен — посчитаем
            long n = 0;
            try (InputStream in = open()) { byte[] b = new byte[65536]; int r; while ((r = in.read(b)) > 0) n += r; } catch (IOException ignored) {}
            size = n;
        }
    }

    public String name() { return name; }
    public long size() { return size; }
    public String mime() { return mime; }
    public InputStream open() throws IOException {
        InputStream in = cr.openInputStream(uri);
        if (in == null) throw new FileNotFoundException(uri.toString());
        return in;
    }
}
