package org.ebookdroid.core.codec;

import com.foobnix.pdf.info.Prefs;
import com.foobnix.sys.TempHolder;

import org.ebookdroid.droids.mupdf.codec.TextWord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public abstract class AbstractCodecPage implements CodecPage {


    String path;

    public AbstractCodecPage(String path) {
        this.path = path;
    }

    @Override
    public List<PageLink> getPageLinks() {
        return Collections.emptyList();
    }


    // Text and annotations share a persistent crash marker. Hold the native lock for
    // the whole check/extract/clear operation so another caller cannot mistake an
    // extraction in progress for a previous crash. The native methods use this same
    // reentrant lock internally.
    @Override
    public TextWord[][] getText() {
        TempHolder.lock.lock();
        try {
            if (Prefs.get().isErrorExist(path, 0)) return new TextWord[0][0];
            try {
                Prefs.get().put(path, 0);
                return getTextImpl();
            } finally {
                Prefs.get().remove(path, 0);
            }
        } finally {
            TempHolder.lock.unlock();
        }
    }

    @Override
    public List<Annotation> getAnnotations() {
        TempHolder.lock.lock();
        try {
            if (Prefs.get().isErrorExist(path, 0)) return new ArrayList<Annotation>();
            try {
                Prefs.get().put(path, 0);
                return getAnnotationsImpl();
            } finally {
                Prefs.get().remove(path, 0);
            }
        } finally {
            TempHolder.lock.unlock();
        }
    }

    public abstract TextWord[][] getTextImpl();
}
