package com.iwrite.export;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
public class ExportResponseFactory {

    public ResponseEntity<byte[]> attachment(ExportFile file) {
        return ResponseEntity.ok()
                .contentType(file.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.fileName() + "\"")
                .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.CONTENT_DISPOSITION)
                .body(file.content());
    }
}
