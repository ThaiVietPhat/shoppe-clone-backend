package com.shopee.monolith.modules.admin.service;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.modules.admin.dto.response.AdminUserResponse;
import com.shopee.monolith.modules.auth.service.SessionRevocationService;
import com.shopee.monolith.modules.user.entity.User;
import com.shopee.monolith.modules.user.model.UserStatus;
import com.shopee.monolith.modules.user.repository.UserRepository;
import com.shopee.monolith.modules.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminUserServiceImpl implements AdminUserService {

    private final UserRepository userRepository;
    private final UserService userService;
    private final SessionRevocationService sessionRevocationService;

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<AdminUserResponse> listUsers(int page, int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        var userPage = userRepository.findAll(pageable).map(this::toResponse);
        return PagedResponse.from(userPage);
    }

    @Override
    @Transactional
    public void banUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        if (user.getStatus() == UserStatus.LOCKED) {
            throw new AppException(ErrorCode.USER_ALREADY_BANNED);
        }
        userService.banUser(userId);
        sessionRevocationService.logoutAll(userId);
    }

    @Override
    @Transactional
    public void unbanUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        if (user.getStatus() != UserStatus.LOCKED) {
            throw new AppException(ErrorCode.USER_ALREADY_ACTIVE);
        }
        userService.unbanUser(userId);
    }

    private AdminUserResponse toResponse(User user) {
        return AdminUserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .role(user.getRole())
                .status(user.getStatus())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
