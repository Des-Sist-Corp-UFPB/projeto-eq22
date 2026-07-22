package com.iwrite.dashboard.controller;

import com.iwrite.dashboard.dto.BookDashboardResponse;
import com.iwrite.dashboard.dto.BookContributionDashboardResponse;
import com.iwrite.dashboard.service.BookDashboardService;
import com.iwrite.dashboard.service.UserDashboardService;
import com.iwrite.writingprogress.service.WritingProgressPeriod;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/books")
public class BookDashboardController {

    private final BookDashboardService bookDashboardService;
    private final UserDashboardService userDashboardService;

    public BookDashboardController(BookDashboardService bookDashboardService, UserDashboardService userDashboardService) {
        this.bookDashboardService = bookDashboardService;
        this.userDashboardService = userDashboardService;
    }

    @GetMapping("/{bookId}/dashboard")
    public BookDashboardResponse getDashboard(
            @PathVariable UUID bookId,
            @RequestParam(required = false) String progressPeriod
    ) {
        return bookDashboardService.getDashboard(bookId, WritingProgressPeriod.fromRequestValue(progressPeriod));
    }

    @GetMapping("/{bookId}/dashboard/contributions")
    public BookContributionDashboardResponse getContributions(
            @PathVariable UUID bookId,
            @RequestParam(required = false) String progressPeriod,
            @RequestParam(required = false) UUID contributorId
    ) {
        return userDashboardService.getBookContributions(
                bookId,
                WritingProgressPeriod.fromRequestValue(progressPeriod),
                contributorId
        );
    }
}
