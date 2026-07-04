package com.shopee.monolith.modules.chat.repository;

import com.shopee.monolith.modules.chat.entity.ChatMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    Page<ChatMessage> findAllByRoomIdOrderByCreatedAtDesc(UUID roomId, Pageable pageable);

    Optional<ChatMessage> findFirstByRoomIdOrderByCreatedAtDesc(UUID roomId);

    long countByRoomIdAndSenderIdNot(UUID roomId, UUID senderId);

    long countByRoomIdAndSenderIdNotAndCreatedAtAfter(UUID roomId, UUID senderId, Instant after);
}
