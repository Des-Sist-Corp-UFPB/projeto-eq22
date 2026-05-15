package com.iwrite.export.controller;

import com.iwrite.export.service.BookExportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/books")
public class BookExportController {

    private static final MediaType MARKDOWN_MEDIA_TYPE = MediaType.parseMediaType("text/markdown; charset=UTF-8");

    private final BookExportService bookExportService;

    public BookExportController(BookExportService bookExportService) {
        this.bookExportService = bookExportService;
    }

    @GetMapping("/{bookId}/export")
    public ResponseEntity<String> exportMarkdown(@PathVariable UUID bookId) {
        String markdown = bookExportService.exportMarkdown(bookId);
        String fileName = bookExportService.getMarkdownFileName(bookId);

        return ResponseEntity.ok()
                .contentType(MARKDOWN_MEDIA_TYPE)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.CONTENT_DISPOSITION)
                .body(markdown);
    }
}
