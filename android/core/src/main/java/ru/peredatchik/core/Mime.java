package ru.peredatchik.core;

import java.util.Locale;

public final class Mime {
    private Mime() {}
    public static String guess(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        String e = n.contains(".") ? n.substring(n.lastIndexOf('.') + 1) : "";
        switch (e) {
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "bmp": return "image/bmp";
            case "heic": return "image/heic";
            case "mp4": return "video/mp4";
            case "mov": return "video/quicktime";
            case "pdf": return "application/pdf";
            case "txt": return "text/plain";
            case "zip": return "application/zip";
            default: return "application/octet-stream";
        }
    }
    public static boolean isImage(String mime) { return mime != null && mime.startsWith("image/"); }
    public static boolean isVideo(String mime) { return mime != null && mime.startsWith("video/"); }

    /** Безопасное имя файла: без путей и запрещённых в Windows символов. */
    public static String safeName(String name) {
        String n = name == null ? "" : name;
        n = n.substring(Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\')) + 1);
        n = n.replaceAll("[<>:\"|?*\\x00-\\x1f]", "_").trim();
        while (n.startsWith(".")) n = n.substring(1);
        if (n.isEmpty()) n = "file";
        if (n.length() > 150) n = n.substring(n.length() - 150);
        return n;
    }
}
