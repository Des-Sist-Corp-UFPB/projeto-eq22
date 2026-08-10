package com.iwrite.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iwrite.support.PostgresIntegrationTest;
import com.iwrite.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A {@link DataIntegrityViolationException} unrelated to {@code uk_users_email} is impractical to
 * trigger for real over HTTP against {@code POST /api/auth/register}: every other constraint on
 * {@code users} is already refused by application-level validation before the insert is even
 * attempted. {@link UserRepository#saveAndFlush} is mocked to throw one directly so the
 * translation rule in {@link RegistrationService} is still proven end to end — real HTTP, real
 * Spring Security filter chain, real {@link GlobalExceptionHandler} — rather than only at the unit
 * level (see {@code RegistrationServiceTest}).
 */
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RegistrationEmailIntegrityIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    void violacaoDeIntegridadeNaoRelacionadaNuncaViraConflito409() throws Exception {
        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());
        when(userRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("chk_some_other_constraint"));

        Map<String, Object> body = Map.of(
                "displayName", "Alguém",
                "email", "qualquer@iwrite.local",
                "password", "senha-valida-1",
                "passwordConfirmation", "senha-valida-1",
                "primaryPersona", "WRITER",
                "timeZone", "America/Sao_Paulo"
        );

        String responseBody = mockMvc.perform(withCsrf(post("/api/auth/register"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isInternalServerError())
                .andReturn().getResponse().getContentAsString();

        assertThat(responseBody).doesNotContain(RegistrationMessages.EMAIL_ALREADY_IN_USE);
        verify(userRepository, times(1)).saveAndFlush(any());
    }

    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) throws Exception {
        Cookie token = mockMvc.perform(get("/api/auth/csrf"))
                .andReturn()
                .getResponse()
                .getCookie("XSRF-TOKEN");
        assertThat(token).isNotNull();
        return request.cookie(token).header("X-XSRF-TOKEN", token.getValue());
    }
}
