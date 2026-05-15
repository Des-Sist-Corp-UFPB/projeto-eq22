package com.iwrite.export.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class TipTapMarkdownRenderer {

    private final ObjectMapper objectMapper;

    public TipTapMarkdownRenderer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Optional<String> render(String contentJson) {
        if (contentJson == null || contentJson.isBlank()) {
            return Optional.empty();
        }

        try {
            JsonNode root = objectMapper.readTree(contentJson);
            if (!"doc".equals(root.path("type").asText())) {
                return Optional.empty();
            }

            List<String> blocks = new ArrayList<>();
            for (JsonNode node : root.path("content")) {
                renderBlock(node).ifPresent(blocks::add);
            }

            String markdown = String.join("\n\n", blocks).strip();
            return markdown.isBlank() ? Optional.empty() : Optional.of(markdown);
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    private Optional<String> renderBlock(JsonNode node) {
        String type = node.path("type").asText();

        if ("paragraph".equals(type)) {
            return renderInlineContent(node).map(String::strip).filter(text -> !text.isBlank());
        }

        if ("heading".equals(type)) {
            int level = node.path("attrs").path("level").asInt(1);
            if (level < 1 || level > 3) {
                return Optional.empty();
            }

            return renderInlineContent(node)
                    .map(String::strip)
                    .filter(text -> !text.isBlank())
                    .map(text -> "#".repeat(level + 3) + " " + text);
        }

        return Optional.empty();
    }

    private Optional<String> renderInlineContent(JsonNode node) {
        StringBuilder text = new StringBuilder();

        for (JsonNode child : node.path("content")) {
            String renderedChild = renderInlineNode(child, text.isEmpty() || text.charAt(text.length() - 1) == '\n');
            if (renderedChild != null) {
                text.append(renderedChild);
            }
        }

        String renderedText = text.toString();
        return renderedText.isBlank() ? Optional.empty() : Optional.of(renderedText);
    }

    private String renderInlineNode(JsonNode node, boolean atLineStart) {
        String type = node.path("type").asText();

        if ("text".equals(type)) {
            return applyMarks(escapeText(node.path("text").asText(""), atLineStart), node.path("marks"));
        }

        if ("hardBreak".equals(type)) {
            return "\n";
        }

        return null;
    }

    private String applyMarks(String text, JsonNode marks) {
        boolean hasBold = false;
        boolean hasItalic = false;

        for (JsonNode mark : marks) {
            String markType = mark.path("type").asText();
            if ("bold".equals(markType)) {
                hasBold = true;
            }
            if ("italic".equals(markType)) {
                hasItalic = true;
            }
        }

        if (!hasBold && !hasItalic) {
            return text;
        }

        int contentStart = 0;
        while (contentStart < text.length() && Character.isWhitespace(text.charAt(contentStart))) {
            contentStart++;
        }

        int contentEnd = text.length();
        while (contentEnd > contentStart && Character.isWhitespace(text.charAt(contentEnd - 1))) {
            contentEnd--;
        }

        if (contentStart == contentEnd) {
            return text;
        }

        String leadingWhitespace = text.substring(0, contentStart);
        String markedText = text.substring(contentStart, contentEnd);
        String trailingWhitespace = text.substring(contentEnd);

        return leadingWhitespace + wrapMarkedText(markedText, hasBold, hasItalic) + trailingWhitespace;
    }

    private String wrapMarkedText(String text, boolean hasBold, boolean hasItalic) {
        if (hasBold && hasItalic) {
            return "**_" + text + "_**";
        }
        if (hasBold) {
            return "**" + text + "**";
        }
        if (hasItalic) {
            return "*" + text + "*";
        }

        return text;
    }

    private String escapeText(String text, boolean atLineStart) {
        StringBuilder escapedText = new StringBuilder();
        boolean isLineStart = atLineStart;
        int index = 0;

        while (index < text.length()) {
            if (isLineStart && startsWithMarkdownSyntax(text, index)) {
                escapedText.append('\\');
            }

            char character = text.charAt(index);
            escapedText.append(character);
            isLineStart = character == '\n';
            index++;
        }

        return escapedText.toString();
    }

    private boolean startsWithMarkdownSyntax(String text, int index) {
        return text.startsWith("***", index)
                || text.startsWith("---", index)
                || text.startsWith("#", index)
                || text.startsWith(">", index)
                || text.startsWith("- ", index)
                || text.startsWith("* ", index)
                || text.startsWith("+ ", index);
    }
}
