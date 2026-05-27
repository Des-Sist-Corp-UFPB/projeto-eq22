package com.iwrite.dashboard.controller;

import com.iwrite.dashboard.dto.BookDashboardResponse;
import com.iwrite.dashboard.service.BookDashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/books")
public class BookDashboardController {

    private final BookDashboardService bookDashboardService;

    public BookDashboardController(BookDashboardService bookDashboardService) {
        this.bookDashboardService = bookDashboardService;
    }

    @GetMapping("/{bookId}/dashboard")
    public BookDashboardResponse getDashboard(@PathVariable UUID bookId) {
        return bookDashboardService.getDashboard(bookId);
    }
}
