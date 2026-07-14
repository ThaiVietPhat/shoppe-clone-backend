package com.shopee.monolith.modules.auth.service;

import com.shopee.monolith.modules.auth.dto.internal.IssuedTokenPair;
import com.shopee.monolith.modules.auth.dto.request.LoginRequest;

public interface AuthService {
    IssuedTokenPair login(LoginRequest request);

    com.shopee.monolith.modules.user.dto.response.UserResponse register(com.shopee.monolith.modules.auth.dto.request.RegisterRequest request);

    void verify(com.shopee.monolith.modules.auth.dto.request.VerifyRequest request);

    IssuedTokenPair exchangeOAuth2Code(String code);

    void forgotPassword(com.shopee.monolith.modules.auth.dto.request.ForgotPasswordRequest request);

    void resetPassword(com.shopee.monolith.modules.auth.dto.request.ResetPasswordRequest request);
}
