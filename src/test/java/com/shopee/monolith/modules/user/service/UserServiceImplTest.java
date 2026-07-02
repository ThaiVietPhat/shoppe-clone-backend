package com.shopee.monolith.modules.user.service;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.modules.user.dto.command.RegisterUserCommand;
import com.shopee.monolith.modules.user.dto.internal.UserAuthenticationData;
import com.shopee.monolith.modules.user.dto.response.UserResponse;
import com.shopee.monolith.modules.user.entity.User;
import com.shopee.monolith.modules.user.mapper.UserMapper;
import com.shopee.monolith.modules.user.model.Role;
import com.shopee.monolith.modules.user.model.UserStatus;
import com.shopee.monolith.modules.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private UserServiceImpl userService;

    @Captor
    private ArgumentCaptor<User> userCaptor;

    private User testUser;
    private UserResponse testResponse;
    private final UUID testId = UUID.randomUUID();
    private final String testEmail = "test@shopee.com";

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .id(testId)
                .email(testEmail)
                .role(Role.BUYER)
                .status(UserStatus.PENDING_VERIFICATION)
                .build();

        testResponse = UserResponse.builder()
                .id(testId)
                .email(testEmail)
                .role(Role.BUYER)
                .build();
    }

    @Test
    void getUserByIdWhenUserExistsShouldReturnUser() {
        when(userRepository.findById(testId)).thenReturn(Optional.of(testUser));
        when(userMapper.toResponse(testUser)).thenReturn(testResponse);

        UserResponse result = userService.getUserById(testId);

        assertEquals(testResponse, result);
    }

    @Test
    void getUserByIdWhenUserDoesNotExistShouldThrowException() {
        when(userRepository.findById(testId)).thenReturn(Optional.empty());

        AppException exception = assertThrows(AppException.class, () -> userService.getUserById(testId));

        assertEquals(ErrorCode.USER_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void getUserByEmailWhenUserExistsShouldReturnUser() {
        when(userRepository.findByNormalizedEmail(testEmail)).thenReturn(Optional.of(testUser));
        when(userMapper.toResponse(testUser)).thenReturn(testResponse);

        UserResponse result = userService.getUserByEmail(testEmail);

        assertEquals(testResponse, result);
    }

    @Test
    void getUserByEmailWhenUserDoesNotExistShouldThrowException() {
        when(userRepository.findByNormalizedEmail(testEmail)).thenReturn(Optional.empty());

        AppException exception = assertThrows(AppException.class, () -> userService.getUserByEmail(testEmail));

        assertEquals(ErrorCode.USER_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void getUserByEmailWhenEmailHasWhitespaceAndUppercaseShouldNormalizeEmailAndReturnUser() {
        String inputEmail = "  TEST@Shopee.COM  ";
        String normalizedEmail = "test@shopee.com";

        when(userRepository.findByNormalizedEmail(normalizedEmail)).thenReturn(Optional.of(testUser));
        when(userMapper.toResponse(testUser)).thenReturn(testResponse);

        UserResponse result = userService.getUserByEmail(inputEmail);

        assertEquals(testResponse, result);
    }

    @Test
    void findAuthenticationDataByEmailWhenUserExistsShouldReturnData() {
        UserAuthenticationData authData = UserAuthenticationData.builder()
                .id(testId)
                .email(testEmail)
                .passwordHash("hashedPwd")
                .role(Role.BUYER)
                .status(UserStatus.PENDING_VERIFICATION)
                .build();

        when(userRepository.findByNormalizedEmail(testEmail)).thenReturn(Optional.of(testUser));
        when(userMapper.toAuthenticationData(testUser)).thenReturn(authData);

        Optional<UserAuthenticationData> result = userService.findAuthenticationDataByEmail(testEmail);

        assertTrue(result.isPresent());
        assertEquals(authData, result.get());
    }

    @Test
    void findAuthenticationDataByEmailWhenUserDoesNotExistShouldReturnEmpty() {
        when(userRepository.findByNormalizedEmail(testEmail)).thenReturn(Optional.empty());

        Optional<UserAuthenticationData> result = userService.findAuthenticationDataByEmail(testEmail);

        assertFalse(result.isPresent());
    }

    @Test
    void findAuthenticationDataByEmailWhenEmailHasWhitespaceAndUppercaseShouldNormalizeEmail() {
        String inputEmail = "  TEST@Shopee.COM  ";
        String normalizedEmail = "test@shopee.com";

        UserAuthenticationData authData = UserAuthenticationData.builder()
                .id(testId)
                .email(normalizedEmail)
                .passwordHash("hashedPwd")
                .role(Role.BUYER)
                .status(UserStatus.PENDING_VERIFICATION)
                .build();

        when(userRepository.findByNormalizedEmail(normalizedEmail)).thenReturn(Optional.of(testUser));
        when(userMapper.toAuthenticationData(testUser)).thenReturn(authData);

        Optional<UserAuthenticationData> result = userService.findAuthenticationDataByEmail(inputEmail);

        assertTrue(result.isPresent());
        assertEquals(authData, result.get());
    }

    @Test
    void registerUserWhenEmailIsNewShouldNormalizeEmailAndReturnUser() {
        String inputEmail = "  NewUser@Shopee.COM  ";
        String normalizedEmail = "newuser@shopee.com";
        String pwdHash = "hashed_password";

        RegisterUserCommand command = RegisterUserCommand.builder()
                .email(inputEmail)
                .passwordHash(pwdHash)
                .build();

        when(userRepository.existsByNormalizedEmail(normalizedEmail)).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userMapper.toResponse(any(User.class))).thenReturn(testResponse);

        UserResponse response = userService.registerUser(command);

        assertEquals(testResponse, response);

        verify(userRepository).saveAndFlush(userCaptor.capture());
        User savedUser = userCaptor.getValue();

        assertEquals(inputEmail, savedUser.getEmail());
        assertEquals(normalizedEmail, savedUser.getNormalizedEmail());
        assertEquals(pwdHash, savedUser.getPasswordHash());
        assertEquals(Role.BUYER, savedUser.getRole());
        assertEquals(UserStatus.PENDING_VERIFICATION, savedUser.getStatus());
    }

    @Test
    void registerUserWhenEmailExistsShouldThrowException() {
        RegisterUserCommand command = RegisterUserCommand.builder()
                .email(testEmail)
                .passwordHash("hash")
                .build();

        when(userRepository.existsByNormalizedEmail(testEmail)).thenReturn(true);

        AppException exception = assertThrows(AppException.class, () -> userService.registerUser(command));

        assertEquals(ErrorCode.EMAIL_ALREADY_EXISTS, exception.getErrorCode());
        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    void registerUserWhenSaveThrowsDataIntegrityViolationShouldThrowEmailAlreadyExists() {
        RegisterUserCommand command = RegisterUserCommand.builder()
                .email(testEmail)
                .passwordHash("hash")
                .build();

        when(userRepository.existsByNormalizedEmail(testEmail)).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(DataIntegrityViolationException.class);

        AppException exception = assertThrows(AppException.class, () -> userService.registerUser(command));

        assertEquals(ErrorCode.EMAIL_ALREADY_EXISTS, exception.getErrorCode());
    }

    @Test
    void activateUserWhenUserExistsShouldActivateUser() {
        when(userRepository.findById(testId)).thenReturn(Optional.of(testUser));

        userService.activateUser(testId);

        assertEquals(UserStatus.ACTIVE, testUser.getStatus());
    }

    @Test
    void activateUserWhenUserDoesNotExistShouldThrowException() {
        when(userRepository.findById(testId)).thenReturn(Optional.empty());

        AppException exception = assertThrows(AppException.class, () -> userService.activateUser(testId));

        assertEquals(ErrorCode.USER_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void promoteToSellerWhenUserIsBuyerShouldUpgradeRole() {
        when(userRepository.findById(testId)).thenReturn(Optional.of(testUser));

        userService.promoteToSeller(testId);

        assertEquals(Role.SELLER, testUser.getRole());
    }

    @Test
    void promoteToSellerWhenUserDoesNotExistShouldThrowException() {
        when(userRepository.findById(testId)).thenReturn(Optional.empty());

        AppException exception = assertThrows(AppException.class, () -> userService.promoteToSeller(testId));

        assertEquals(ErrorCode.USER_NOT_FOUND, exception.getErrorCode());
    }
}
