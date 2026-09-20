package com.foobnix.pdf.search.activity;

import androidx.room.ColumnInfo;
import androidx.room.Dao;
import androidx.room.Database;
import androidx.room.Entity;
import androidx.room.Fts4;
import androidx.room.FtsOptions;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.PrimaryKey;
import androidx.room.Query;
import androidx.room.RoomDatabase;

import java.util.List;

/** SQLite owns the inverted index, storage, transactions and crash recovery. */
@Database(entities = {BookSearchDatabase.Passage.class, BookSearchDatabase.State.class},
        version = 1, exportSchema = false)
public abstract class BookSearchDatabase extends RoomDatabase {
    @Entity(tableName = "passages")
    @Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61, notIndexed = {"ownWords", "rawText"})
    public static class Passage {
        @PrimaryKey @ColumnInfo(name = "rowid") public int rowid;
        public String text;
        public String rawText;
        // The remainder of text is overlap from the next page, for boundary-spanning matches.
        public int ownWords;
    }

    @Entity(tableName = "state")
    public static class State {
        @PrimaryKey public int id = 1;
        public int indexedPages;
        public int totalPages;
        public String fingerprint;
    }

    @Dao
    public interface Access {
        @Query("SELECT * FROM state WHERE id = 1") State state();
        @Insert(onConflict = OnConflictStrategy.REPLACE) void state(State state);
        @Insert(onConflict = OnConflictStrategy.REPLACE) void passage(Passage passage);
        @Query("SELECT rowid, text, rawText, ownWords FROM passages WHERE rowid = :rowid") Passage page(int rowid);
        @Query("DELETE FROM passages") void clearPassages();
        @Query("SELECT rowid, text, rawText, ownWords FROM passages WHERE instr(text, :query) > 0 "
                + "AND rowid > :firstPage AND rowid <= :lastPage ORDER BY rowid LIMIT :limit")
        List<Passage> typed(String query, int firstPage, int lastPage, int limit);
        @Query("SELECT rowid, text, rawText, ownWords FROM passages WHERE passages MATCH :query LIMIT :limit")
        List<Passage> candidates(String query, int limit);
    }

    public abstract Access access();
}
