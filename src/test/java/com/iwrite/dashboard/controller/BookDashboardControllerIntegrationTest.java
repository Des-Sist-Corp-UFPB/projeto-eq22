package com.iwrite.dashboard.controller;

import com.iwrite.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class BookDashboardControllerIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void invalidProgressPeriodReturnsBadRequest() throws Exception {
        var book = createBook("invalid progress period");

        mockMvc.perform(get("/api/books/{bookId}/dashboard", book.id())
                        .param("progressPeriod", "weekly"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.messages", hasItem(containsString("progressPeriod"))));
    }
}
