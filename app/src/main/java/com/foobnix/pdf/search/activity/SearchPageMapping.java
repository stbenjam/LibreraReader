package com.foobnix.pdf.search.activity;

/** Conversion at the reader boundary; search storage always uses native, zero-based pages. */
public final class SearchPageMapping {
    private final boolean split, spread, coverAlone;

    public SearchPageMapping(boolean split, boolean spread, boolean coverAlone) {
        this.split = split;
        this.spread = spread;
        this.coverAlone = coverAlone;
    }

    public int displayedPage(int nativePage) {
        if (split) return nativePage * 2;
        if (spread) return coverAlone ? (nativePage + 1) / 2 : nativePage / 2;
        return nativePage;
    }

    public int nativeFirst(int displayedPage) {
        if (split) return displayedPage / 2;
        if (spread) return Math.max(0, displayedPage * 2 - (coverAlone ? 1 : 0));
        return displayedPage;
    }

    /** Exclusive upper bound: either half of a split page includes the whole native page. */
    public int nativeEnd(int displayedEnd) {
        if (split) return (displayedEnd + 1) / 2;
        return nativeFirst(displayedEnd);
    }

    public int displayedCount(int nativeCount) {
        if (nativeCount == 0) return 0;
        return split ? nativeCount * 2 : displayedPage(nativeCount - 1) + 1;
    }
}
