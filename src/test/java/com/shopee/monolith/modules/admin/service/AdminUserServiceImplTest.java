package com.shopee.monolith.modules.admin.service;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.modules.admin.dto.response.AdminUserResponse;
import com.shopee.monolith.modules.auth.service.SessionRevocationService;
import com.shopee.monolith.modules.user.entity.User;
import com.shopee.monolith.modules.user.model.Role;
import com.shopee.monolith.modules.user.model.UserStatus;
import com.shopee.monolith.modules.user.repository.UserRepository;
import com.shopee.monolith.modules.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceImplTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserService userService;
    @Mock
    private SessionRevocationService sessionRevocationService;

    private AdminUserServiceImpl adminUserService;

    @BeforeEach
    void setUp() {
        adminUserService = new AdminUserServiceImpl(userRepository, userService, sessionRevocationService);
    }

    private User activeUser(UUID id) {
        return User.builder().id(id).email("test@shopee.local").role(Role.BUYER).status(UserStatus.ACTIVE).build();
    }

    @Test
    void listUsersShouldMapPage() {
        User user = activeUser(UUID.randomUUID());
        Page<User> page = new PageImpl<>(List.of(user), PageRequest.of(0, 20), 1);
        when(userRepository.findAll(any(org.springframework.data.domain.Pageable.class))).thenReturn(page);

        PagedResponse<AdminUserResponse> result = adminUserService.listUsers(0, 20);

        assertEquals(1, result.items().size());
        assertEquals(user.getEmail(), result.items().get(0).email());
    }

    @Test
    void banUserWhenActiveShouldLockAndRevokeSessions() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser(userId)));

        adminUserService.banUser(userId);

        verify(userService).banUser(userId);
        verify(sessionRevocationService).logoutAll(userId);
    }

    @Test
    void banUserWhenAlreadyBannedShouldThrow() {
        UUID userId = UUID.randomUUID();
        User locked = User.builder().id(userId).email("locked@shopee.local").role(Role.BUYER).status(UserStatus.LOCKED).build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(locked));

        AppException exception = assertThrows(AppException.class, () -> adminUserService.banUser(userId));
        assertEquals(ErrorCode.USER_ALREADY_BANNED, exception.getErrorCode());
        verify(userService, never()).banUser(any());
    }

    @Test
    void unbanUserWhenLockedShouldUnlock() {
        UUID userId = UUID.randomUUID();
        User locked = User.builder().id(userId).email("locked@shopee.local").role(Role.BUYER).status(UserStatus.LOCKED).build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(locked));

        adminUserService.unbanUser(userId);

        verify(userService).unbanUser(userId);
    }

    @Test
    void unbanUserWhenNotBannedShouldThrow() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser(userId)));

        AppException exception = assertThrows(AppException.class, () -> adminUserService.unbanUser(userId));
        assertEquals(ErrorCode.USER_ALREADY_ACTIVE, exception.getErrorCode());
        verify(userService, never()).unbanUser(any());
    }

    @Test
    void banUserWhenMissingShouldThrowNotFound() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        AppException exception = assertThrows(AppException.class, () -> adminUserService.banUser(userId));
        assertEquals(ErrorCode.USER_NOT_FOUND, exception.getErrorCode());
    }
}
