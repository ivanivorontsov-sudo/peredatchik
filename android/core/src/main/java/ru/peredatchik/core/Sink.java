package ru.peredatchik.core;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** Куда принимать файлы. Реализуется платформой. */
public interface Sink {
    /** Каталог для недокачанных .part (для докачки). */
    File partDir();
    /** Перенести готовый файл в итоговое место, вернуть путь/URI для показа. */
    String complete(File part, String name, String mime, Peer from) throws IOException;
    /** Можно ли принять пакет (например, спросить пользователя). */
    default boolean accept(Peer from, List<String> names, long total) { return true; }
}
