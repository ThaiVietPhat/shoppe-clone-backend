package com.shopee.monolith.modules.user.service;

import com.shopee.monolith.modules.user.dto.command.RegisterUserCommand;
import com.shopee.monolith.modules.user.dto.internal.UserAuthenticationData;
import com.shopee.monolith.modules.user.dto.response.UserResponse;

import java.util.Optional;
import java.util.UUID;

public interface UserService {
    
    UserResponse getUserById(UUID id);
    
    UserResponse getUserByEmail(String email);

    Optional<UserAuthenticationData> findAuthenticationDataByEmail(String email);

    Optional<UserAuthenticationData> findAuthenticationDataById(UUID id);

    UserResponse registerUser(RegisterUserCommand command);

    void activateUser(UUID userId);

    void promoteToSeller(UUID userId);

    void lockUser(UUID userId);

    void banUser(UUID userId);

    void unbanUser(UUID userId);

    Optional<UserAuthenticationData> findAuthenticationDataByOAuth(String provider, String providerUserId);

    UserResponse registerOAuthUser(String provider, String providerUserId, String email);
}

