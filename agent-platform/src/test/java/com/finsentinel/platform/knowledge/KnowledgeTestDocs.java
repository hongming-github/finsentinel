package com.finsentinel.platform.knowledge;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Writes small MAS-shaped PDFs and a sources.json into a temp knowledge folder. */
final class KnowledgeTestDocs {

    private KnowledgeTestDocs() {
    }

    static void pdf(Path file, List<String> lines) throws IOException {
        Files.createDirectories(file.getParent());
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream out = new PDPageContentStream(doc, page)) {
                out.beginText();
                out.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                out.setLeading(14);
                out.newLineAtOffset(50, 740);
                for (String line : lines) {
                    out.showText(line);
                    out.newLine();
                }
                out.endText();
            }
            doc.save(file.toFile());
        }
    }

    static void manifest(Path dir, String... docIds) throws IOException {
        StringBuilder json = new StringBuilder("{\"documents\": [");
        for (int i = 0; i < docIds.length; i++) {
            json.append(i == 0 ? "" : ",").append("""
                    {"id": "%1$s", "shortName": "%1$s", "title": "Test %1$s", "url": "https://example.test/%1$s.pdf",
                     "file": "%1$s.pdf"}""".formatted(docIds[i]));
        }
        Files.writeString(dir.resolve("sources.json"), json.append("]}"));
    }

    static List<String> paymentsDoc() {
        return List.of(
                "1    Overview",
                "1.1  These Guidelines apply to responsible financial institutions.",
                "2    Duties of the responsible FI",
                "2.1  A responsible FI should impose a cooling off period of at least 12 hours before",
                "     a new payee can receive funds.",
                "2.2  A responsible FI should provide a kill switch to block account access.",
                "3    Suspicious transactions",
                "3.1  A bank shall promptly submit a suspicious transaction report to the STRO.");
    }
}
