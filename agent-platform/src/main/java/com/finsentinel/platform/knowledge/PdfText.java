package com.finsentinel.platform.knowledge;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts text page by page, so chunks can carry the page they start on.
 *
 * <p>Text smaller than {@code minFontPt} is dropped. In the MAS documents body text is 12pt, footnotes 9pt and
 * footnote markers 6pt; without this, footnotes land in the middle of whatever paragraph spans the page break
 * ("...no later than 30 calendar days after receipt 11 Such links are only to provide information...") and
 * markers glue onto words ("12 hours13").
 */
final class PdfText {

    private PdfText() {
    }

    static List<String> pages(Path pdf, float minFontPt) {
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            PDFTextStripper stripper = new BodyTextStripper(minFontPt);
            stripper.setSortByPosition(true);
            List<String> pages = new ArrayList<>(document.getNumberOfPages());
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                pages.add(stripper.getText(document));
            }
            return pages;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + pdf, e);
        }
    }

    private static final class BodyTextStripper extends PDFTextStripper {

        private final float minFontPt;

        BodyTextStripper(float minFontPt) {
            this.minFontPt = minFontPt;
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws IOException {
            StringBuilder kept = new StringBuilder(text.length());
            List<TextPosition> keptPositions = new ArrayList<>(positions.size());
            for (TextPosition position : positions) {
                if (position.getFontSizeInPt() >= minFontPt) {
                    kept.append(position.getUnicode());
                    keptPositions.add(position);
                }
            }
            if (!keptPositions.isEmpty()) {
                super.writeString(kept.toString(), keptPositions);
            }
        }
    }
}
