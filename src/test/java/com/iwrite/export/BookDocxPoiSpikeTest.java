package com.iwrite.export;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BookDocxPoiSpikeTest {

    @Test
    void generatesMinimalDocxInMemoryWithTitleParagraphBoldAndItalic() throws Exception {
        byte[] docxBytes = createMinimalDocxBytes();

        assertThat(docxBytes).isNotEmpty();

        try (XWPFDocument document = openDocument(docxBytes)) {
            List<XWPFParagraph> paragraphs = document.getParagraphs();

            assertThat(paragraphs).hasSizeGreaterThanOrEqualTo(4);
            assertThat(paragraphs.get(0).getText()).isEqualTo("Livro de Spike DOCX");
            assertThat(paragraphs.get(1).getText()).isEqualTo("Paragrafo normal com texto simples.");

            XWPFParagraph formattedParagraph = paragraphs.get(2);
            assertThat(formattedParagraph.getText()).isEqualTo("Texto com negrito e italico.");
            assertThat(runWithText(formattedParagraph, "negrito")).matches(XWPFRun::isBold);
            assertThat(runWithText(formattedParagraph, "italico")).matches(XWPFRun::isItalic);
        }
    }

    @Test
    void opensGeneratedDocxAndReadsExpectedText() throws Exception {
        byte[] docxBytes = createMinimalDocxBytes();

        try (XWPFDocument document = openDocument(docxBytes)) {
            String documentText = document.getParagraphs().stream()
                    .map(XWPFParagraph::getText)
                    .reduce("", (current, paragraphText) -> current + "\n" + paragraphText);

            assertThat(documentText)
                    .contains("Livro de Spike DOCX")
                    .contains("Paragrafo normal com texto simples.")
                    .contains("Texto com negrito e italico.")
                    .contains("- Primeiro item")
                    .contains("- Segundo item");
        }
    }

    private byte[] createMinimalDocxBytes() throws IOException {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            XWPFParagraph title = document.createParagraph();
            title.setStyle("Title");
            title.createRun().setText("Livro de Spike DOCX");

            XWPFParagraph normalParagraph = document.createParagraph();
            normalParagraph.createRun().setText("Paragrafo normal com texto simples.");

            XWPFParagraph formattedParagraph = document.createParagraph();
            formattedParagraph.createRun().setText("Texto com ");

            XWPFRun boldRun = formattedParagraph.createRun();
            boldRun.setBold(true);
            boldRun.setText("negrito");

            formattedParagraph.createRun().setText(" e ");

            XWPFRun italicRun = formattedParagraph.createRun();
            italicRun.setItalic(true);
            italicRun.setText("italico");

            formattedParagraph.createRun().setText(".");

            document.createParagraph().createRun().setText("- Primeiro item");
            document.createParagraph().createRun().setText("- Segundo item");

            document.write(outputStream);
            return outputStream.toByteArray();
        }
    }

    private XWPFDocument openDocument(byte[] docxBytes) throws IOException {
        return new XWPFDocument(new ByteArrayInputStream(docxBytes));
    }

    private XWPFRun runWithText(XWPFParagraph paragraph, String text) {
        return paragraph.getRuns().stream()
                .filter(run -> text.equals(run.text()))
                .findFirst()
                .orElseThrow();
    }
}
