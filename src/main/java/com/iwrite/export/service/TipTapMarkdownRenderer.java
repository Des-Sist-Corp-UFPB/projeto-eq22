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
                    .map(text -> "#".repeat(level) + " " + text);
        }

        return Optional.empty();
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
            return applyMarks(node.path("text").asText(""), node.path("marks"));
        }

        if ("hardBreak".equals(type)) {
            return "\n";
        }

        return null;
    }

    private String applyMarks(String text, JsonNode marks) {
        String markedText = text;

        for (JsonNode mark : marks) {
            String markType = mark.path("type").asText();
            if ("bold".equals(markType)) {
                markedText = "**" + markedText + "**";
            }
            if ("italic".equals(markType)) {
                markedText = "*" + markedText + "*";
            }
        }

        return markedText;
    }
}
