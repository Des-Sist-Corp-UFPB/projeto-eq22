package com.iwrite.export;

import com.iwrite.common.exception.BadRequestException;
import org.springframework.http.MediaType;

import java.util.Locale;

public enum ExportFormat {
    TXT("txt", MediaType.parseMediaType("text/plain; charset=UTF-8")),
    MD("md", MediaType.parseMediaType("text/markdown; charset=UTF-8")),
    DOCX("docx", MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));

    private final String extension;
    private final MediaType contentType;

    ExportFormat(String extension, MediaType contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    public static ExportFormat parse(String value) {
        if (value == null || value.isBlank()) {
            return MD;
        }

        String normalizedValue = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalizedValue) {
            case "txt" -> TXT;
            case "md" -> MD;
            case "docx" -> DOCX;
            default -> throw new BadRequestException("Unsupported export format: " + value);
        };
    }

    public String extension() {
        return extension;
    }

    public MediaType contentType() {
        return contentType;
    }
}
