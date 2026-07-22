package com.iwrite.dashboard.controller;

import com.iwrite.dashboard.dto.UserDashboardResponse;
import com.iwrite.dashboard.service.UserDashboardService;
import com.iwrite.writingprogress.service.WritingProgressPeriod;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class UserDashboardController {

    private final UserDashboardService userDashboardService;

    public UserDashboardController(UserDashboardService userDashboardService) {
        this.userDashboardService = userDashboardService;
    }

    @GetMapping("/me")
    public UserDashboardResponse getCurrentUserDashboard(@RequestParam(required = false) String progressPeriod) {
        return userDashboardService.getCurrentUserDashboard(WritingProgressPeriod.fromRequestValue(progressPeriod));
    }
}
