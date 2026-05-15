package com.iwrite.export.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xwpf.usermodel.Borders;
import org.apache.poi.xwpf.usermodel.UnderlinePatterns;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class TipTapDocxRenderer {

    private final ObjectMapper objectMapper;

    public TipTapDocxRenderer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public boolean renderInto(XWPFDocument document, String contentJson) {
        Optional<JsonNode> renderableRoot = renderableRoot(contentJson);
        if (renderableRoot.isEmpty()) {
            return false;
        }

        try {
            boolean renderedContent = false;
            for (JsonNode node : renderableRoot.get().path("content")) {
                renderedContent = renderBlock(document, node) || renderedContent;
            }

            return renderedContent;
        } catch (Exception exception) {
            return false;
        }
    }

    public boolean canRender(String contentJson) {
        return renderableRoot(contentJson).isPresent();
    }

    private Optional<JsonNode> renderableRoot(String contentJson) {
        if (contentJson == null || contentJson.isBlank()) {
            return Optional.empty();
        }

        try {
            JsonNode root = objectMapper.readTree(contentJson);
            if (!"doc".equals(root.path("type").asText()) || hasUnsupportedTextContent(root) || !hasRenderableContent(root)) {
                return Optional.empty();
            }
            return Optional.of(root);
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    private boolean renderBlock(XWPFDocument document, JsonNode node) {
        String type = node.path("type").asText();

        if ("paragraph".equals(type)) {
            return renderParagraph(document, node);
        }

        if ("heading".equals(type)) {
            return renderHeading(document, node);
        }

        if ("bulletList".equals(type)) {
            return renderList(document, node, "- ", 1);
        }

        if ("orderedList".equals(type)) {
            return renderList(document, node, null, node.path("attrs").path("start").asInt(1));
        }

        if ("blockquote".equals(type)) {
            return renderBlockquote(document, node);
        }

        if ("codeBlock".equals(type)) {
            return renderCodeBlock(document, node);
        }

        if ("horizontalRule".equals(type)) {
            XWPFParagraph paragraph = document.createParagraph();
            paragraph.setBorderBottom(Borders.SINGLE);
            return true;
        }

        return false;
    }

    private boolean renderParagraph(XWPFDocument document, JsonNode node) {
        Optional<String> plainText = plainInlineText(node);
        if (plainText.isEmpty()) {
            return false;
        }

        XWPFParagraph paragraph = document.createParagraph();
        renderInlineContent(paragraph, node);
        return true;
    }

    private boolean renderHeading(XWPFDocument document, JsonNode node) {
        Optional<String> plainText = plainInlineText(node);
        if (plainText.isEmpty()) {
            return false;
        }

        int tipTapLevel = node.path("attrs").path("level").asInt(1);
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setStyle("Heading" + internalHeadingLevel(tipTapLevel));
        renderInlineContent(paragraph, node);
        return true;
    }

    private boolean renderList(XWPFDocument document, JsonNode node, String unorderedMarker, int startNumber) {
        boolean renderedContent = false;
        int number = startNumber;

        for (JsonNode child : node.path("content")) {
            if (!"listItem".equals(child.path("type").asText())) {
                continue;
            }

            String marker = unorderedMarker == null ? number + ". " : unorderedMarker;
            renderedContent = renderListItem(document, child, marker) || renderedContent;
            number++;
        }

        return renderedContent;
    }

    private boolean renderListItem(XWPFDocument document, JsonNode node, String marker) {
        boolean renderedContent = false;
        boolean usedMarker = false;

        for (JsonNode child : node.path("content")) {
            if ("paragraph".equals(child.path("type").asText())) {
                Optional<String> plainText = plainInlineText(child);
                if (plainText.isPresent()) {
                    XWPFParagraph paragraph = document.createParagraph();
                    paragraph.createRun().setText(usedMarker ? "  " : marker);
                    renderInlineContent(paragraph, child);
                    usedMarker = true;
                    renderedContent = true;
                }
                continue;
            }

            renderedContent = renderBlock(document, child) || renderedContent;
        }

        return renderedContent;
    }

    private boolean renderBlockquote(XWPFDocument document, JsonNode node) {
        boolean renderedContent = false;

        for (JsonNode child : node.path("content")) {
            Optional<String> text = plainBlockText(child);
            if (text.isEmpty()) {
                continue;
            }

            XWPFParagraph paragraph = document.createParagraph();
            paragraph.setIndentationLeft(720);
            if ("paragraph".equals(child.path("type").asText()) || "heading".equals(child.path("type").asText())) {
                renderInlineContent(paragraph, child);
            } else {
                paragraph.createRun().setText(text.get());
            }
            renderedContent = true;
        }

        return renderedContent;
    }

    private boolean renderCodeBlock(XWPFDocument document, JsonNode node) {
        String code = plainText(node);
        if (code.isBlank()) {
            return false;
        }

        XWPFParagraph paragraph = document.createParagraph();
        XWPFRun run = paragraph.createRun();
        run.setFontFamily("Courier New");
        run.setText(code.stripTrailing());
        return true;
    }

    private void renderInlineContent(XWPFParagraph paragraph, JsonNode node) {
        for (JsonNode child : node.path("content")) {
            renderInlineNode(paragraph, child);
        }
    }

    private void renderInlineNode(XWPFParagraph paragraph, JsonNode node) {
        String type = node.path("type").asText();

        if ("text".equals(type)) {
            XWPFRun run = paragraph.createRun();
            applyMarks(run, node.path("marks"));
            run.setText(node.path("text").asText(""));
        }

        if ("hardBreak".equals(type)) {
            paragraph.createRun().addBreak();
        }
    }

    private void applyMarks(XWPFRun run, JsonNode marks) {
        for (JsonNode mark : marks) {
            String markType = mark.path("type").asText();
            if ("bold".equals(markType)) {
                run.setBold(true);
            }
            if ("italic".equals(markType)) {
                run.setItalic(true);
            }
            if ("code".equals(markType)) {
                run.setFontFamily("Courier New");
            }
            if ("strike".equals(markType)) {
                run.setStrikeThrough(true);
            }
            if ("underline".equals(markType)) {
                run.setUnderline(UnderlinePatterns.SINGLE);
            }
        }
    }

    private int internalHeadingLevel(int tipTapLevel) {
        if (tipTapLevel <= 1) {
            return 3;
        }
        return 4;
    }

    private Optional<String> plainInlineText(JsonNode node) {
        String text = plainText(node);
        return text.isBlank() ? Optional.empty() : Optional.of(text);
    }

    private Optional<String> plainBlockText(JsonNode node) {
        String text = plainText(node).strip();
        return text.isBlank() ? Optional.empty() : Optional.of(text);
    }

    private String plainText(JsonNode node) {
        StringBuilder text = new StringBuilder();
        appendPlainText(node, text);
        return text.toString();
    }

    private void appendPlainText(JsonNode node, StringBuilder text) {
        String type = node.path("type").asText();
        if ("text".equals(type)) {
            text.append(node.path("text").asText(""));
            return;
        }
        if ("hardBreak".equals(type)) {
            text.append("\n");
            return;
        }

        for (JsonNode child : node.path("content")) {
            appendPlainText(child, text);
        }
    }

    private boolean hasUnsupportedTextContent(JsonNode node) {
        String type = node.path("type").asText();
        if (!isSupportedNode(type) && hasTextContent(node)) {
            return true;
        }

        for (JsonNode child : node.path("content")) {
            if (hasUnsupportedTextContent(child)) {
                return true;
            }
        }

        return false;
    }

    private boolean hasRenderableContent(JsonNode node) {
        String type = node.path("type").asText();
        if (("text".equals(type) && !node.path("text").asText("").isBlank()) || "horizontalRule".equals(type)) {
            return true;
        }

        for (JsonNode child : node.path("content")) {
            if (hasRenderableContent(child)) {
                return true;
            }
        }

        return false;
    }

    private boolean isSupportedNode(String type) {
        return "doc".equals(type)
                || "paragraph".equals(type)
                || "heading".equals(type)
                || "text".equals(type)
                || "hardBreak".equals(type)
                || "bulletList".equals(type)
                || "orderedList".equals(type)
                || "listItem".equals(type)
                || "blockquote".equals(type)
                || "codeBlock".equals(type)
                || "horizontalRule".equals(type);
    }

    private boolean hasTextContent(JsonNode node) {
        if ("text".equals(node.path("type").asText()) && !node.path("text").asText("").isBlank()) {
            return true;
        }

        for (JsonNode child : node.path("content")) {
            if (hasTextContent(child)) {
                return true;
            }
        }

        return false;
    }
}
