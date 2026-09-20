package com.foobnix.pdf.search.activity;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Worker-confined retention policy. Connections must close before their files can be removed. */
final class SearchIndexCache {
    private final long maxBytes;
    private final int maxEntries;
    private final Map<File, Integer> open = new HashMap<>();
    private final Set<File> clearOnClose = new HashSet<>();

    SearchIndexCache(long maxBytes, int maxEntries) {
        this.maxBytes = maxBytes;
        this.maxEntries = maxEntries;
    }

    void acquire(File file) {
        open.merge(file, 1, Integer::sum);
    }

    void accessed(File file) {
        file.setLastModified(System.currentTimeMillis());
        prune(file.getParentFile());
    }

    void release(File file) {
        open.computeIfPresent(file, (key, count) -> count > 1 ? count - 1 : null);
        if (!open.containsKey(file) && clearOnClose.remove(file)) delete(file);
        prune(file.getParentFile());
    }

    void clear(File directory) {
        for (File file : databases(directory)) {
            if (open.containsKey(file)) clearOnClose.add(file);
            else delete(file);
        }
    }

    void prune(File directory) {
        File[] files = databases(directory);
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed()
                .thenComparing(File::getName));
        Set<String> books = new HashSet<>();
        long bytes = 0;
        int count = 0;
        // Reserve the budget for live connections before considering evictable indexes.
        for (File file : files) if (open.containsKey(file)) { bytes += size(file); count++; }
        for (File file : files) {
            String name = file.getName();
            int separator = name.indexOf('-');
            String book = separator < 0 ? name : name.substring(0, separator);
            boolean olderLayout = !books.add(book);
            if (open.containsKey(file)) continue;
            long size = size(file);
            if (olderLayout || count >= maxEntries || bytes + size > maxBytes) delete(file);
            else { bytes += size; count++; }
        }
    }

    private static File[] databases(File directory) {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".db"));
        return files == null ? new File[0] : files;
    }

    private static long size(File file) {
        return file.length() + new File(file + "-wal").length();
    }

    private static void delete(File file) {
        if (file.delete()) {
            for (String suffix : new String[]{"-wal", "-shm", "-journal"}) new File(file + suffix).delete();
        }
    }
}
