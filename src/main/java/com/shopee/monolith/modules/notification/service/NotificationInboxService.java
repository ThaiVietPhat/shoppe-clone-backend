package com.shopee.monolith.modules.notification.service;

import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.modules.notification.dto.response.NotificationResponse;
import com.shopee.monolith.modules.notification.model.NotificationType;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface NotificationInboxService {

    PagedResponse<NotificationResponse> listNotifications(UUID userId, boolean unreadOnly, Pageable pageable);

    long countUnread(UUID userId);

    /** Marks one own notification read; idempotent. */
    NotificationResponse markRead(UUID userId, UUID notificationId);

    /** Marks all unread notifications read; returns the number updated. */
    int markAllRead(UUID userId);

    /** Internal write path used by event listeners. */
    void createNotification(UUID userId, NotificationType type, String title, String body,
                            String refType, UUID refId);
}
