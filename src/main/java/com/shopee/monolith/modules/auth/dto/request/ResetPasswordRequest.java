package com.shopee.monolith.modules.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;

@Builder
@Schema(description = "Request payload to complete a password reset")
public record ResetPasswordRequest(
        @NotBlank(message = "Token is required")
        @Schema(description = "Opaque password reset token sent to email", example = "<opaque-reset-token>")
        String token,

        @NotBlank(message = "New password is required")
        @Size(min = 6, max = 100, message = "Password must be between 6 and 100 characters")
        @Schema(description = "New password for the account", example = "<new-password>")
        String newPassword
) {}
