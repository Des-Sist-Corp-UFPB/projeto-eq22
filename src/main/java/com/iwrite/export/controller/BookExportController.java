package com.iwrite.export.controller;

import com.iwrite.export.ExportFile;
import com.iwrite.export.ExportFormat;
import com.iwrite.export.ExportResponseFactory;
import com.iwrite.export.service.BookExportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/books")
public class BookExportController {

    private final BookExportService bookExportService;
    private final ExportResponseFactory exportResponseFactory;

    public BookExportController(BookExportService bookExportService, ExportResponseFactory exportResponseFactory) {
        this.bookExportService = bookExportService;
        this.exportResponseFactory = exportResponseFactory;
    }

    @GetMapping("/{bookId}/exports/manuscript")
    public ResponseEntity<byte[]> exportManuscript(
            @PathVariable UUID bookId,
            @RequestParam(defaultValue = "md") String format,
            @RequestParam(defaultValue = "false") boolean includeSceneTitles,
            @RequestParam(defaultValue = "false") boolean includeEmptyScenes
    ) {
        ExportFile file = bookExportService.exportManuscript(
                bookId,
                ExportFormat.parse(format),
                includeSceneTitles,
                includeEmptyScenes
        );

        return exportResponseFactory.attachment(file);
    }

    @GetMapping("/{bookId}/export/markdown")
    public ResponseEntity<byte[]> exportMarkdown(
            @PathVariable UUID bookId,
            @RequestParam(defaultValue = "false") boolean includeSceneTitles,
            @RequestParam(defaultValue = "false") boolean includeEmptyScenes
    ) {
        ExportFile file = bookExportService.exportManuscript(
                bookId,
                ExportFormat.MD,
                includeSceneTitles,
                includeEmptyScenes
        );

        return exportResponseFactory.attachment(file);
    }

    @GetMapping("/{bookId}/export/docx")
    public ResponseEntity<byte[]> exportDocx(
            @PathVariable UUID bookId,
            @RequestParam(defaultValue = "false") boolean includeSceneTitles,
            @RequestParam(defaultValue = "false") boolean includeEmptyScenes
    ) {
        ExportFile file = bookExportService.exportManuscript(
                bookId,
                ExportFormat.DOCX,
                includeSceneTitles,
                includeEmptyScenes
        );

        return exportResponseFactory.attachment(file);
    }
}
