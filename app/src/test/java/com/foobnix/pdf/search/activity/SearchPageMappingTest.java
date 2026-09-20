package com.foobnix.pdf.search.activity;

import org.junit.Test;
import static org.junit.Assert.*;

public class SearchPageMappingTest {
    @Test public void rangesContainExactlyThePagesShownInEveryReadingMode() {
        for (SearchPageMapping mapping : new SearchPageMapping[]{
                new SearchPageMapping(false, false, false),
                new SearchPageMapping(true, false, false),
                new SearchPageMapping(false, true, false),
                new SearchPageMapping(false, true, true)}) {
            for (int count = 1; count <= 10; count++) {
                assertEquals(0, mapping.nativeFirst(0));
                assertTrue(mapping.nativeEnd(mapping.displayedCount(count)) >= count);
                for (int page = 0; page < count; page++) {
                    int display = mapping.displayedPage(page);
                    assertTrue(display < mapping.displayedCount(count));
                    assertTrue(mapping.nativeFirst(display) <= page);
                    assertTrue(mapping.nativeEnd(display + 1) > page);
                }
            }
        }
    }

    @Test public void eitherHalfOfSplitPageIncludesItsNativePage() {
        SearchPageMapping mapping = new SearchPageMapping(true, false, false);
        assertEquals(2, mapping.nativeFirst(4));
        assertEquals(2, mapping.nativeFirst(5));
        assertEquals(3, mapping.nativeEnd(5));
        assertEquals(3, mapping.nativeEnd(6));
    }

    @Test public void coverAloneDoesNotShiftFirstSpreadOrLoseFinalPage() {
        SearchPageMapping mapping = new SearchPageMapping(false, true, true);
        assertEquals(0, mapping.displayedPage(0));
        assertEquals(1, mapping.displayedPage(1));
        assertEquals(1, mapping.displayedPage(2));
        assertEquals(1, mapping.nativeFirst(1));
        assertEquals(3, mapping.nativeEnd(2));
        assertEquals(51, mapping.displayedCount(100));
    }
}
