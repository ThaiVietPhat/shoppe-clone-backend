package com.shopee.monolith.modules.chat.service;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.modules.chat.dto.response.ChatMessageResponse;
import com.shopee.monolith.modules.chat.dto.response.ChatRoomResponse;
import com.shopee.monolith.modules.chat.entity.ChatMessage;
import com.shopee.monolith.modules.chat.entity.ChatRoom;
import com.shopee.monolith.modules.chat.mapper.ChatMapper;
import com.shopee.monolith.modules.chat.repository.ChatMessageRepository;
import com.shopee.monolith.modules.chat.repository.ChatRoomRepository;
import com.shopee.monolith.modules.user.dto.internal.ShopLookupData;
import com.shopee.monolith.modules.user.service.ShopService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    /** STOMP topic prefix for room broadcasts; clients subscribe to {prefix}/{roomId}. */
    public static final String ROOM_TOPIC_PREFIX = "/topic/chat/rooms/";

    private final ChatRoomRepository chatRoomRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ShopService shopService;
    private final ChatMapper chatMapper;
    private final SimpMessagingTemplate messagingTemplate;
    private final Clock clock;

    @Override
    @Transactional
    public ChatRoomResponse openRoom(UUID buyerId, UUID shopId) {
        ShopLookupData shop = shopService.findShopLookupDataById(shopId)
                .orElseThrow(() -> new AppException(ErrorCode.SHOP_NOT_FOUND));
        if (shop.ownerId().equals(buyerId)) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }

        ChatRoom room = chatRoomRepository.findByBuyerIdAndShopId(buyerId, shopId)
                .orElseGet(() -> createRoom(buyerId, shopId));
        return buildRoomResponse(room, buyerId, shop.name());
    }

    private ChatRoom createRoom(UUID buyerId, UUID shopId) {
        try {
            return chatRoomRepository.saveAndFlush(ChatRoom.builder()
                    .buyerId(buyerId)
                    .shopId(shopId)
                    .build());
        } catch (DataIntegrityViolationException e) {
            // Concurrent open lost the unique-constraint race — reuse the winner's room
            return chatRoomRepository.findByBuyerIdAndShopId(buyerId, shopId)
                    .orElseThrow(() -> new AppException(ErrorCode.CHAT_ROOM_NOT_FOUND));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatRoomResponse> listRooms(UUID userId) {
        List<ChatRoom> rooms = new ArrayList<>(chatRoomRepository.findAllByBuyerIdOrderByUpdatedAtDesc(userId));
        shopService.findShopLookupDataByOwnerId(userId)
                .ifPresent(shop -> rooms.addAll(chatRoomRepository.findAllByShopIdOrderByUpdatedAtDesc(shop.id())));

        Map<UUID, ShopLookupData> shops = shopService.findShopLookupDataByIds(
                rooms.stream().map(ChatRoom::getShopId).distinct().toList());

        // De-dup defensively (owner chatting with own shop is rejected at open time)
        Map<UUID, ChatRoomResponse> byId = new LinkedHashMap<>();
        for (ChatRoom room : rooms) {
            ShopLookupData shop = shops.get(room.getShopId());
            byId.putIfAbsent(room.getId(), buildRoomResponse(room, userId, shop != null ? shop.name() : null));
        }
        return List.copyOf(byId.values());
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<ChatMessageResponse> getMessages(UUID userId, UUID roomId, Pageable pageable) {
        requireParticipant(userId, loadRoom(roomId));
        Page<ChatMessage> page = chatMessageRepository.findAllByRoomIdOrderByCreatedAtDesc(roomId, pageable);
        return PagedResponse.from(page, page.getContent().stream().map(chatMapper::toMessageResponse).toList());
    }

    @Override
    @Transactional
    public ChatMessageResponse sendMessage(UUID userId, UUID roomId, String content) {
        requireParticipant(userId, loadRoom(roomId));
        ChatMessage message = chatMessageRepository.save(ChatMessage.builder()
                .roomId(roomId)
                .senderId(userId)
                .content(content)
                .build());
        ChatMessageResponse response = chatMapper.toMessageResponse(message);
        messagingTemplate.convertAndSend(ROOM_TOPIC_PREFIX + roomId, response);
        return response;
    }

    @Override
    @Transactional
    public ChatRoomResponse markRead(UUID userId, UUID roomId) {
        ChatRoom room = loadRoom(roomId);
        requireParticipant(userId, room);
        if (room.getBuyerId().equals(userId)) {
            room.markBuyerRead(clock.instant());
        } else {
            room.markSellerRead(clock.instant());
        }
        room = chatRoomRepository.save(room);
        String shopName = shopService.findShopLookupDataById(room.getShopId())
                .map(ShopLookupData::name).orElse(null);
        return buildRoomResponse(room, userId, shopName);
    }

    private ChatRoomResponse buildRoomResponse(ChatRoom room, UUID viewerId, String shopName) {
        Instant myLastReadAt = room.getBuyerId().equals(viewerId)
                ? room.getBuyerLastReadAt()
                : room.getSellerLastReadAt();
        long unreadCount = myLastReadAt == null
                ? chatMessageRepository.countByRoomIdAndSenderIdNot(room.getId(), viewerId)
                : chatMessageRepository.countByRoomIdAndSenderIdNotAndCreatedAtAfter(room.getId(), viewerId, myLastReadAt);
        ChatMessage lastMessage = chatMessageRepository.findFirstByRoomIdOrderByCreatedAtDesc(room.getId()).orElse(null);
        return chatMapper.toRoomResponse(
                room,
                shopName,
                lastMessage != null ? lastMessage.getContent() : null,
                lastMessage != null ? lastMessage.getSenderId() : null,
                lastMessage != null ? lastMessage.getCreatedAt() : null,
                unreadCount);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isParticipant(UUID userId, UUID roomId) {
        return chatRoomRepository.findById(roomId)
                .map(room -> isParticipantOf(userId, room))
                .orElse(false);
    }

    private ChatRoom loadRoom(UUID roomId) {
        return chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new AppException(ErrorCode.CHAT_ROOM_NOT_FOUND));
    }

    private void requireParticipant(UUID userId, ChatRoom room) {
        if (!isParticipantOf(userId, room)) {
            throw new AppException(ErrorCode.CHAT_ROOM_ACCESS_DENIED);
        }
    }

    private boolean isParticipantOf(UUID userId, ChatRoom room) {
        if (room.getBuyerId().equals(userId)) {
            return true;
        }
        return shopService.findShopLookupDataById(room.getShopId())
                .map(shop -> shop.ownerId().equals(userId))
                .orElse(false);
    }
}
