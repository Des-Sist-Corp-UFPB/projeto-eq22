package com.iwrite.export.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class TipTapPlainTextRenderer {

    public static final String TEXT_SEPARATOR = "----------------------------------------";

    private final ObjectMapper objectMapper;

    public TipTapPlainTextRenderer(ObjectMapper objectMapper) {
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

            String text = String.join("\n\n", blocks).strip();
            return text.isBlank() ? Optional.empty() : Optional.of(text);
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    private Optional<String> renderBlock(JsonNode node) {
        String type = node.path("type").asText();

        if ("paragraph".equals(type) || "heading".equals(type)) {
            return renderInlineContent(node).map(String::strip).filter(text -> !text.isBlank());
        }

        if ("bulletList".equals(type)) {
            return renderList(node, "• ", 1);
        }

        if ("orderedList".equals(type)) {
            return renderList(node, null, node.path("attrs").path("start").asInt(1));
        }

        if ("listItem".equals(type)) {
            return renderListItemContent(node);
        }

        if ("blockquote".equals(type)) {
            return renderChildBlocks(node)
                    .map(block -> "CITAÇÃO:\n" + block)
                    .filter(text -> !text.isBlank());
        }

        if ("codeBlock".equals(type)) {
            String code = plainText(node).stripTrailing();
            return code.isBlank() ? Optional.empty() : Optional.of(code);
        }

        if ("horizontalRule".equals(type)) {
            return Optional.of(TEXT_SEPARATOR);
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

        String text = String.join("\n", items);
        return text.isBlank() ? Optional.empty() : Optional.of(text);
    }

    private Optional<String> renderListItemContent(JsonNode node) {
        return renderChildBlocks(node).map(text -> text.replaceAll("\\n{3,}", "\n\n"));
    }

    private Optional<String> renderChildBlocks(JsonNode node) {
        List<String> blocks = new ArrayList<>();
        for (JsonNode child : node.path("content")) {
            renderBlock(child).ifPresent(blocks::add);
        }

        String text = String.join("\n\n", blocks).strip();
        return text.isBlank() ? Optional.empty() : Optional.of(text);
    }

    private String formatListItem(String marker, String content) {
        String[] lines = content.split("\\R", -1);
        StringBuilder item = new StringBuilder(marker).append(lines[0]);

        for (int index = 1; index < lines.length; index++) {
            item.append("\n  ").append(lines[index]);
        }

        return item.toString();
    }

    private Optional<String> renderInlineContent(JsonNode node) {
        StringBuilder text = new StringBuilder();

        for (JsonNode child : node.path("content")) {
            String renderedChild = renderInlineNode(child);
            if (renderedChild != null) {
                text.append(renderedChild);
            }
        }

        String renderedText = text.toString();
        return renderedText.isBlank() ? Optional.empty() : Optional.of(renderedText);
    }

    private String renderInlineNode(JsonNode node) {
        String type = node.path("type").asText();

        if ("text".equals(type)) {
            return node.path("text").asText("");
        }

        if ("hardBreak".equals(type)) {
            return "\n";
        }

        if (hasTextContent(node)) {
            throw new UnsupportedContentNodeException();
        }

        return null;
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
