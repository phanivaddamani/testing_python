package com.example.voterroll.extract;

import com.example.voterroll.model.Word;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Pulls words with their positions out of a PDF page's text layer. */
@Component
public class PdfWordExtractor {

    public List<Word> extract(PDDocument doc, int pageIndex) throws IOException {
        WordCollector collector = new WordCollector();
        collector.setSortByPosition(true);
        collector.setStartPage(pageIndex + 1);
        collector.setEndPage(pageIndex + 1);
        collector.getText(doc);
        return collector.words;
    }

    private static final class WordCollector extends PDFTextStripper {
        final List<Word> words = new ArrayList<>();

        WordCollector() throws IOException {
            super();
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) {
            StringBuilder sb = new StringBuilder();
            float x0 = 0, x1 = 0, top = Float.MAX_VALUE, bottom = 0;
            TextPosition prev = null;

            for (TextPosition tp : positions) {
                String ch = tp.getUnicode();
                if (ch == null) continue;

                // Split on spaces, and on large horizontal gaps that have no space glyph.
                boolean gap = prev != null
                        && tp.getXDirAdj() - (prev.getXDirAdj() + prev.getWidthDirAdj())
                           > prev.getWidthOfSpace() * 1.5f;
                if (ch.isBlank() || gap) {
                    flush(sb, x0, x1, top, bottom);
                    sb.setLength(0);
                    top = Float.MAX_VALUE;
                    bottom = 0;
                    prev = ch.isBlank() ? null : prev;
                    if (ch.isBlank()) continue;
                }

                if (sb.isEmpty()) x0 = tp.getXDirAdj();
                sb.append(ch);
                x1 = tp.getXDirAdj() + tp.getWidthDirAdj();
                float h = Math.max(tp.getHeightDir(), 1f);
                top = Math.min(top, tp.getYDirAdj() - h);
                bottom = Math.max(bottom, tp.getYDirAdj());
                prev = tp;
            }
            flush(sb, x0, x1, top, bottom);
        }

        private void flush(StringBuilder sb, float x0, float x1, float top, float bottom) {
            String t = sb.toString().trim();
            if (!t.isEmpty()) {
                words.add(new Word(t, x0, top, Math.max(x1 - x0, 0.1f), Math.max(bottom - top, 0.1f)));
            }
        }
    }
}
