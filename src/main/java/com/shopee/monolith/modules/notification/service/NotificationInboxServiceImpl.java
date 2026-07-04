package com.shopee.monolith.modules.notification.service;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.modules.notification.dto.response.NotificationResponse;
import com.shopee.monolith.modules.notification.entity.Notification;
import com.shopee.monolith.modules.notification.mapper.NotificationMapper;
import com.shopee.monolith.modules.notification.model.NotificationType;
import com.shopee.monolith.modules.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationInboxServiceImpl implements NotificationInboxService {

    private final NotificationRepository notificationRepository;
    private final NotificationMapper notificationMapper;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<NotificationResponse> listNotifications(UUID userId, boolean unreadOnly, Pageable pageable) {
        Page<Notification> page = unreadOnly
                ? notificationRepository.findAllByUserIdAndReadAtIsNullOrderByCreatedAtDesc(userId, pageable)
                : notificationRepository.findAllByUserIdOrderByCreatedAtDesc(userId, pageable);
        return PagedResponse.from(page, page.getContent().stream().map(notificationMapper::toResponse).toList());
    }

    @Override
    @Transactional(readOnly = true)
    public long countUnread(UUID userId) {
        return notificationRepository.countByUserIdAndReadAtIsNull(userId);
    }

    @Override
    @Transactional
    public NotificationResponse markRead(UUID userId, UUID notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new AppException(ErrorCode.NOTIFICATION_NOT_FOUND));
        if (!notification.getUserId().equals(userId)) {
            throw new AppException(ErrorCode.NOTIFICATION_NOT_FOUND);
        }
        notification.markRead(clock.instant());
        return notificationMapper.toResponse(notificationRepository.save(notification));
    }

    @Override
    @Transactional
    public int markAllRead(UUID userId) {
        return notificationRepository.markAllRead(userId, clock.instant());
    }

    @Override
    @Transactional
    public void createNotification(UUID userId, NotificationType type, String title, String body,
                                   String refType, UUID refId) {
        notificationRepository.save(Notification.builder()
                .userId(userId)
                .type(type)
                .title(title)
                .body(body)
                .refType(refType)
                .refId(refId)
                .build());
        log.info("Created {} notification for user {}", type, userId);
    }
}
