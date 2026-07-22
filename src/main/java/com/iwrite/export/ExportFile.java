package com.iwrite.export;

import org.springframework.http.MediaType;

public record ExportFile(
        byte[] content,
        MediaType contentType,
        String fileName
) {
}
