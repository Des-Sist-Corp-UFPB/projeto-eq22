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

            return renderInlineContent(node)
                    .map(String::strip)
                    .filter(text -> !text.isBlank())
                    .map(text -> "#".repeat(internalHeadingLevel(level)) + " " + text);
        }

        if ("bulletList".equals(type)) {
            return renderList(node, "- ", 1);
        }

        if ("orderedList".equals(type)) {
            return renderList(node, null, node.path("attrs").path("start").asInt(1));
        }

        if ("listItem".equals(type)) {
            return renderListItemContent(node);
        }

        if ("blockquote".equals(type)) {
            return renderChildBlocks(node)
                    .map(block -> prefixLines(block, "> "))
                    .filter(text -> !text.isBlank());
        }

        if ("codeBlock".equals(type)) {
            String code = plainText(node);
            if (code.isBlank()) {
                return Optional.empty();
            }

            String fence = codeFenceFor(code);
            return Optional.of(fence + "\n" + code.stripTrailing() + "\n" + fence);
        }

        if ("horizontalRule".equals(type)) {
            return Optional.of("---");
        }

        if (hasTextContent(node)) {
            throw new UnsupportedContentNodeException();
        }

        return Optional.empty();
    }

    private Optional<String> renderList(JsonNode node, String unorderedMarker, int startNumber) {
        List<String> items = new ArrayList<>();
        int number = startNumber;

        for (JsonNode child : node.path("content")) {
            if (!"listItem".equals(child.path("type").asText())) {
                if (hasTextContent(child)) {
                    throw new UnsupportedContentNodeException();
                }
                continue;
            }

            Optional<String> itemContent = renderListItemContent(child);
            if (itemContent.isPresent()) {
                String marker = unorderedMarker == null ? number + ". " : unorderedMarker;
                items.add(formatListItem(marker, itemContent.get()));
            }
            number++;
        }

        String markdown = String.join("\n", items);
        return markdown.isBlank() ? Optional.empty() : Optional.of(markdown);
    }

    private int internalHeadingLevel(int tipTapLevel) {
        if (tipTapLevel <= 1) {
            return 4;
        }
        if (tipTapLevel == 2) {
            return 5;
        }
        return 6;
    }

    private Optional<String> renderListItemContent(JsonNode node) {
        return renderChildBlocks(node).map(text -> text.replaceAll("\\n{3,}", "\n\n"));
    }

    private Optional<String> renderChildBlocks(JsonNode node) {
        List<String> blocks = new ArrayList<>();
        for (JsonNode child : node.path("content")) {
            renderBlock(child).ifPresent(blocks::add);
        }

        String markdown = String.join("\n\n", blocks).strip();
        return markdown.isBlank() ? Optional.empty() : Optional.of(markdown);
    }

    private String formatListItem(String marker, String content) {
        String[] lines = content.split("\\R", -1);
        StringBuilder item = new StringBuilder(marker).append(lines[0]);

        for (int index = 1; index < lines.length; index++) {
            item.append("\n  ").append(lines[index]);
        }

        return item.toString();
    }

    private String prefixLines(String text, String prefix) {
        String[] lines = text.split("\\R", -1);
        StringBuilder prefixedText = new StringBuilder();

        for (int index = 0; index < lines.length; index++) {
            if (index > 0) {
                prefixedText.append("\n");
            }
            prefixedText.append(prefix).append(lines[index]);
        }

        return prefixedText.toString();
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

        if (hasTextContent(node)) {
            throw new UnsupportedContentNodeException();
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

    private String codeFenceFor(String code) {
        int longestRun = 0;
        int currentRun = 0;

        for (int index = 0; index < code.length(); index++) {
            if (code.charAt(index) == '`') {
                currentRun++;
                longestRun = Math.max(longestRun, currentRun);
            } else {
                currentRun = 0;
            }
        }

        return "`".repeat(Math.max(3, longestRun + 1));
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

    private static class UnsupportedContentNodeException extends RuntimeException {
    }
}
