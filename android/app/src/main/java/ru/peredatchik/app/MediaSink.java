package ru.peredatchik.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.*;
import ru.peredatchik.core.*;

/** Принятые файлы: фото -> Pictures/Передатчик, видео -> Movies/Передатчик, остальное -> Download/Передатчик. Видны в галерее. */
final class MediaSink implements Sink {
    static final String FOLDER = "Передатчик";
    private final Context ctx;
    MediaSink(Context c) { ctx = c.getApplicationContext(); }

    public File partDir() { return new File(ctx.getFilesDir(), "partial"); }

    public String complete(File part, String name, String mime, Peer from) throws IOException {
        try {
            if (Build.VERSION.SDK_INT >= 29) return viaMediaStore(part, name, mime);
            return legacy(part, name, mime);
        } finally { part.delete(); }
    }

    private String viaMediaStore(File part, String name, String mime) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        Uri coll; String dir;
        if (Mime.isImage(mime)) { coll = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY); dir = Environment.DIRECTORY_PICTURES; }
        else if (Mime.isVideo(mime)) { coll = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY); dir = Environment.DIRECTORY_MOVIES; }
        else { coll = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY); dir = Environment.DIRECTORY_DOWNLOADS; }
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime == null ? "application/octet-stream" : mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, dir + "/" + FOLDER);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = cr.insert(coll, v);
        if (uri == null) throw new IOException("не удалось создать файл в галерее");
        try (OutputStream out = cr.openOutputStream(uri); InputStream in = new FileInputStream(part)) {
            copy(in, out);
        } catch (IOException e) { cr.delete(uri, null, null); throw e; }
        v.clear(); v.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(uri, v, null, null);
        return uri.toString();
    }

    @SuppressWarnings("deprecation")
    private String legacy(File part, String name, String mime) throws IOException {
        String type = Mime.isImage(mime) ? Environment.DIRECTORY_PICTURES : Mime.isVideo(mime) ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_DOWNLOADS;
        File dir = new File(Environment.getExternalStoragePublicDirectory(type), FOLDER);
        dir.mkdirs();
        File dst = DirSink.unique(dir, name);
        try (InputStream in = new FileInputStream(part); OutputStream out = new FileOutputStream(dst)) { copy(in, out); }
        MediaScannerConnection.scanFile(ctx, new String[]{dst.getAbsolutePath()}, new String[]{mime}, null);
        return Uri.fromFile(dst).toString();
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] b = new byte[1 << 16]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n);
    }
}
