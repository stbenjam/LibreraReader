package com.foobnix.pdf.search.activity;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class SearchIndexCacheTest {
    @Rule public TemporaryFolder directory = new TemporaryFolder();

    private File file(String name, int bytes, long time) throws Exception {
        File file = directory.newFile(name);
        Files.write(file.toPath(), new byte[bytes]);
        assertTrue(file.setLastModified(time));
        return file;
    }

    @Test public void removesOldLayoutsAndTheirSqliteSidecarsAfterClosing() throws Exception {
        SearchIndexCache cache = new SearchIndexCache(1000, 10);
        File old = file("book-old.db", 10, 1000);
        File wal = file("book-old.db-wal", 10, 1000);
        File latest = file("book-new.db", 10, 2000);
        cache.acquire(old);
        cache.prune(directory.getRoot());
        assertTrue(old.exists());
        cache.release(old);
        assertFalse(old.exists());
        assertFalse(wal.exists());
        assertTrue(latest.exists());
    }

    @Test public void evictsLeastRecentlyUsedIndexesWithinByteAndCountBudgets() throws Exception {
        File oldest = file("a-layout.db", 40, 1000);
        File middle = file("b-layout.db", 40, 2000);
        File newest = file("c-layout.db", 40, 3000);
        new SearchIndexCache(90, 10).prune(directory.getRoot());
        assertFalse(oldest.exists());
        assertTrue(middle.exists());
        assertTrue(newest.exists());
        new SearchIndexCache(1000, 1).prune(directory.getRoot());
        assertFalse(middle.exists());
        assertTrue(newest.exists());
    }

    @Test public void explicitClearWaitsForEveryConnectionToClose() throws Exception {
        SearchIndexCache cache = new SearchIndexCache(1000, 10);
        File active = file("a-layout.db", 10, 1000);
        File idle = file("b-layout.db", 10, 2000);
        cache.acquire(active);
        cache.acquire(active);
        cache.clear(directory.getRoot());
        assertFalse(idle.exists());
        assertTrue(active.exists());
        cache.release(active);
        assertTrue(active.exists());
        cache.release(active);
        assertFalse(active.exists());
    }

    @Test public void neverEvictsALiveOversizeIndex() throws Exception {
        SearchIndexCache cache = new SearchIndexCache(5, 1);
        File active = file("a-layout.db", 10, 1000);
        cache.acquire(active);
        cache.prune(directory.getRoot());
        assertTrue(active.exists());
        cache.release(active);
        assertFalse(active.exists());
    }
}
