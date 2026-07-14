package com.shopee.monolith.modules.user.service;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.modules.user.dto.command.RegisterUserCommand;
import com.shopee.monolith.modules.user.dto.internal.UserAuthenticationData;
import com.shopee.monolith.modules.user.dto.response.UserResponse;
import com.shopee.monolith.modules.user.entity.OAuthIdentity;
import com.shopee.monolith.modules.user.entity.User;
import com.shopee.monolith.modules.user.mapper.UserMapper;
import com.shopee.monolith.modules.user.repository.OAuthIdentityRepository;
import com.shopee.monolith.modules.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final OAuthIdentityRepository oauthIdentityRepository;
    private final UserMapper userMapper;

    @Override
    public UserResponse getUserById(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        return userMapper.toResponse(user);
    }

    @Override
    public UserResponse getUserByEmail(String email) {
        if (email == null) {
            throw new AppException(ErrorCode.USER_NOT_FOUND);
        }
        String normalizedEmail = normalizeEmail(email);
        User user = userRepository.findByNormalizedEmail(normalizedEmail)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        return userMapper.toResponse(user);
    }

    @Override
    public Optional<UserAuthenticationData> findAuthenticationDataByEmail(String email) {
        if (email == null) {
            return Optional.empty();
        }
        String normalizedEmail = normalizeEmail(email);
        return userRepository.findByNormalizedEmail(normalizedEmail)
                .map(userMapper::toAuthenticationData);
    }

    @Override
    public Optional<UserAuthenticationData> findAuthenticationDataById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return userRepository.findById(id)
                .map(userMapper::toAuthenticationData);
    }

    @Override
    @Transactional
    public UserResponse registerUser(RegisterUserCommand command) {
        String normalizedEmail = normalizeEmail(command.email());
        if (userRepository.existsByNormalizedEmail(normalizedEmail)) {
            throw new AppException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        User user = User.builder()
                .email(command.email())
                .normalizedEmail(normalizedEmail)
                .passwordHash(command.passwordHash())
                .build();

        try {
            User savedUser = userRepository.saveAndFlush(user);
            return userMapper.toResponse(savedUser);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new AppException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
    }

    @Override
    @Transactional
    public void activateUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        user.activate();
    }

    @Override
    @Transactional
    public void promoteToSeller(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        user.promoteToSeller();
    }

    @Override
    @Transactional
    public void lockUser(UUID userId) {
        // Pessimistic row lock only — used by callers (token issuance, session revocation) to
        // serialize concurrent mutations for this user. Does NOT change account status; see
        // banUser() for actually locking an account out.
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
    }

    @Override
    @Transactional
    public void banUser(UUID userId) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        user.lock();
        userRepository.save(user);
    }

    @Override
    @Transactional
    public void unbanUser(UUID userId) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        user.unlock();
        userRepository.save(user);
    }

    @Override
    public Optional<UserAuthenticationData> findAuthenticationDataByOAuth(String provider, String providerUserId) {
        if (provider == null || providerUserId == null) {
            return Optional.empty();
        }
        return oauthIdentityRepository.findByProviderAndProviderUserId(provider, providerUserId)
                .flatMap(identity -> userRepository.findById(identity.getUserId()))
                .map(userMapper::toAuthenticationData);
    }

    @Override
    @Transactional
    public UserResponse registerOAuthUser(String provider, String providerUserId, String email) {
        String normalizedEmail = normalizeEmail(email);
        if (userRepository.existsByNormalizedEmail(normalizedEmail)) {
            throw new AppException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        User user = User.builder()
                .email(email)
                .normalizedEmail(normalizedEmail)
                .passwordHash(null)
                .status(com.shopee.monolith.modules.user.model.UserStatus.ACTIVE)
                .build();

        try {
            User savedUser = userRepository.saveAndFlush(user);

            OAuthIdentity identity = OAuthIdentity.builder()
                    .userId(savedUser.getId())
                    .provider(provider)
                    .providerUserId(providerUserId)
                    .emailAtProvider(email)
                    .build();
            oauthIdentityRepository.saveAndFlush(identity);

            return userMapper.toResponse(savedUser);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("uq_oauth_identities_provider_user_id") || msg.contains("oauth_identities")) {
                throw new AppException(ErrorCode.OAUTH_IDENTITY_ALREADY_LINKED);
            }
            throw new AppException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
    }

    private String normalizeEmail(String email) {
        if (email == null) {
            return "";
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}

