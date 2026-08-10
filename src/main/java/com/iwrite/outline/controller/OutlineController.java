package com.iwrite.outline.controller;

import com.iwrite.outline.dto.BookOutlineResponse;
import com.iwrite.outline.service.OutlineService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/books")
public class OutlineController {

    private final OutlineService outlineService;

    public OutlineController(OutlineService outlineService) {
        this.outlineService = outlineService;
    }

    @GetMapping("/{bookId}/outline")
    public BookOutlineResponse getOutline(@PathVariable UUID bookId) {
        return outlineService.getOutline(bookId);
    }
}
