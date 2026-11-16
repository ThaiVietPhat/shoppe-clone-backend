package com.shopee.monolith.common.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new DummyController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void shouldHandleAppException() throws Exception {
        mockMvc.perform(get("/dummy/app-exception"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("User not found"));
    }

    @Test
    void shouldHandleValidationException() throws Exception {
        String invalidPayload = "{\"name\":\"\"}";
        mockMvc.perform(post("/dummy/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("Name is mandatory"));
    }

    @Test
    void shouldHandleConstraintViolationException() throws Exception {
        mockMvc.perform(get("/dummy/constraint-violation"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void shouldHandleHttpMessageNotReadableException() throws Exception {
        mockMvc.perform(get("/dummy/not-readable"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("Invalid request body format"));
    }

    @Test
    void shouldHandleHttpRequestMethodNotSupportedException() throws Exception {
        mockMvc.perform(post("/dummy/app-exception")) // POST to a GET endpoint
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(405))
                .andExpect(jsonPath("$.message").value("Method not supported"));
    }

    @Test
    void shouldHandleAuthenticationException() throws Exception {
        mockMvc.perform(get("/dummy/auth-exception"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("Authentication required"));
    }

    @Test
    void shouldHandleAccessDeniedException() throws Exception {
        mockMvc.perform(get("/dummy/access-denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("Access denied"));
    }

    @Test
    void shouldHandleUnexpectedException() throws Exception {
        mockMvc.perform(get("/dummy/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("Internal server error"));
    }

    @Test
    void shouldHandleMethodArgumentTypeMismatchException() throws Exception {
        mockMvc.perform(get("/dummy/uuid/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("Invalid request"));
    }

    @Test
    void shouldHandleMissingServletRequestParameterException() throws Exception {
        mockMvc.perform(get("/dummy/required-param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("Invalid request"));
    }

    @RestController
    static class DummyController {

        @GetMapping("/dummy/app-exception")
        public void throwAppException() {
            throw new AppException(ErrorCode.USER_NOT_FOUND);
        }

        @PostMapping("/dummy/validation")
        public void throwValidationException(@RequestBody @Valid DummyPayload payload) {
        }

        @GetMapping("/dummy/constraint-violation")
        public void throwConstraintViolationException() {
            throw new ConstraintViolationException("Invalid parameter", null);
        }

        @GetMapping("/dummy/not-readable")
        public void throwNotReadableException() {
            throw new HttpMessageNotReadableException("JSON parse error", new MockHttpInputMessage(new byte[0]));
        }

        @GetMapping("/dummy/auth-exception")
        public void throwAuthException() {
            throw new AuthenticationException("Auth failed") {};
        }

        @GetMapping("/dummy/access-denied")
        public void throwAccessDeniedException() {
            throw new AccessDeniedException("Access denied");
        }

        @GetMapping("/dummy/unexpected")
        public void throwUnexpectedException() throws Exception {
            throw new Exception("Unexpected error");
        }

        @GetMapping("/dummy/uuid/{id}")
        public void parseUuid(@PathVariable UUID id) {
        }

        @GetMapping("/dummy/required-param")
        public void requireParam(@RequestParam String value) {
        }
    }

    @Data
    static class DummyPayload {
        @NotBlank(message = "Name is mandatory")
        private String name;
    }
}
