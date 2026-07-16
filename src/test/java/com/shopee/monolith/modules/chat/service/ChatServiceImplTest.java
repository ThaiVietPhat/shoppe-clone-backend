package com.shopee.monolith.modules.chat.service;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.modules.chat.dto.response.ChatMessageResponse;
import com.shopee.monolith.modules.chat.dto.response.ChatRoomResponse;
import com.shopee.monolith.modules.chat.entity.ChatMessage;
import com.shopee.monolith.modules.chat.entity.ChatRoom;
import com.shopee.monolith.modules.chat.mapper.ChatMapper;
import com.shopee.monolith.modules.chat.repository.ChatMessageRepository;
import com.shopee.monolith.modules.chat.repository.ChatRoomRepository;
import com.shopee.monolith.modules.user.dto.internal.ShopLookupData;
import com.shopee.monolith.modules.user.service.ShopService;
import com.shopee.monolith.modules.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-06-12T10:00:00Z");

    @Mock
    private ChatRoomRepository chatRoomRepository;
    @Mock
    private ChatMessageRepository chatMessageRepository;
    @Mock
    private ShopService shopService;
    @Mock
    private UserService userService;
    @Mock
    private ChatMapper chatMapper;
    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private ChatServiceImpl chatService;

    private UUID buyerId;
    private UUID shopId;
    private UUID ownerId;
    private UUID roomId;
    private ChatRoom room;

    @BeforeEach
    void setUp() {
        chatService = new ChatServiceImpl(chatRoomRepository, chatMessageRepository, shopService, userService,
                chatMapper, messagingTemplate, Clock.fixed(NOW, ZoneOffset.UTC));
        buyerId = UUID.randomUUID();
        shopId = UUID.randomUUID();
        ownerId = UUID.randomUUID();
        roomId = UUID.randomUUID();
        room = ChatRoom.builder().buyerId(buyerId).shopId(shopId).build();
    }

    private ShopLookupData shop() {
        return ShopLookupData.builder().id(shopId).ownerId(ownerId).name("Demo Shop").build();
    }

    @Test
    void openRoomWhenShopMissingShouldThrowShopNotFound() {
        when(shopService.findShopLookupDataById(shopId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chatService.openRoom(buyerId, shopId))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.SHOP_NOT_FOUND);
    }

    @Test
    void openRoomWhenOwnShopShouldThrowInvalidRequest() {
        when(shopService.findShopLookupDataById(shopId)).thenReturn(Optional.of(shop()));

        assertThatThrownBy(() -> chatService.openRoom(ownerId, shopId))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void openRoomWhenRoomExistsShouldReuseIt() {
        when(shopService.findShopLookupDataById(shopId)).thenReturn(Optional.of(shop()));
        when(chatRoomRepository.findByBuyerIdAndShopId(buyerId, shopId)).thenReturn(Optional.of(room));
        when(userService.findEmailsByIds(java.util.List.of(buyerId))).thenReturn(Map.of());
        when(chatMapper.toRoomResponse(room, "Demo Shop", null, null, null, null, 0L)).thenReturn(mock(ChatRoomResponse.class));

        chatService.openRoom(buyerId, shopId);

        verify(chatRoomRepository).findByBuyerIdAndShopId(buyerId, shopId);
    }

    @Test
    void openRoomWhenConcurrentCreateLosesRaceShouldReuseWinnersRoom() {
        when(shopService.findShopLookupDataById(shopId)).thenReturn(Optional.of(shop()));
        when(chatRoomRepository.findByBuyerIdAndShopId(buyerId, shopId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(room));
        when(chatRoomRepository.saveAndFlush(any(ChatRoom.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));
        when(userService.findEmailsByIds(java.util.List.of(buyerId))).thenReturn(Map.of());
        when(chatMapper.toRoomResponse(room, "Demo Shop", null, null, null, null, 0L)).thenReturn(mock(ChatRoomResponse.class));

        chatService.openRoom(buyerId, shopId);

        verify(chatMapper).toRoomResponse(room, "Demo Shop", null, null, null, null, 0L);
    }

    @Test
    void sendMessageWhenParticipantShouldPersistAndBroadcast() {
        when(chatRoomRepository.findById(roomId)).thenReturn(Optional.of(room));
        ChatMessage message = ChatMessage.builder().roomId(roomId).senderId(buyerId).content("hi").build();
        when(chatMessageRepository.save(any(ChatMessage.class))).thenReturn(message);
        ChatMessageResponse response = mock(ChatMessageResponse.class);
        when(chatMapper.toMessageResponse(message)).thenReturn(response);

        chatService.sendMessage(buyerId, roomId, "hi");

        verify(messagingTemplate).convertAndSend(
                eq(ChatServiceImpl.ROOM_TOPIC_PREFIX + roomId), eq(response));
    }

    @Test
    void sendMessageWhenNotParticipantShouldThrowAccessDenied() {
        when(chatRoomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(shopService.findShopLookupDataById(shopId)).thenReturn(Optional.of(shop()));

        assertThatThrownBy(() -> chatService.sendMessage(UUID.randomUUID(), roomId, "hi"))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_ROOM_ACCESS_DENIED);
    }

    @Test
    void sendMessageWhenSellerOwnsShopShouldBeAllowed() {
        when(chatRoomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(shopService.findShopLookupDataById(shopId)).thenReturn(Optional.of(shop()));
        ChatMessage message = ChatMessage.builder().roomId(roomId).senderId(ownerId).content("hello").build();
        when(chatMessageRepository.save(any(ChatMessage.class))).thenReturn(message);
        when(chatMapper.toMessageResponse(message)).thenReturn(mock(ChatMessageResponse.class));

        chatService.sendMessage(ownerId, roomId, "hello");

        verify(chatMessageRepository).save(any(ChatMessage.class));
    }

    @Test
    void markReadWhenBuyerShouldStampBuyerReadReceipt() {
        when(chatRoomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(chatRoomRepository.save(room)).thenReturn(room);
        when(shopService.findShopLookupDataById(shopId)).thenReturn(Optional.of(shop()));
        when(userService.findEmailsByIds(java.util.List.of(buyerId))).thenReturn(Map.of());
        when(chatMapper.toRoomResponse(room, "Demo Shop", null, null, null, null, 0L)).thenReturn(mock(ChatRoomResponse.class));

        chatService.markRead(buyerId, roomId);

        assertThat(room.getBuyerLastReadAt()).isEqualTo(NOW);
        assertThat(room.getSellerLastReadAt()).isNull();
    }

    @Test
    void markReadWhenSellerShouldStampSellerReadReceipt() {
        when(chatRoomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(chatRoomRepository.save(room)).thenReturn(room);
        when(shopService.findShopLookupDataById(shopId)).thenReturn(Optional.of(shop()));
        when(userService.findEmailsByIds(java.util.List.of(buyerId))).thenReturn(Map.of());
        when(chatMapper.toRoomResponse(room, "Demo Shop", null, null, null, null, 0L)).thenReturn(mock(ChatRoomResponse.class));

        chatService.markRead(ownerId, roomId);

        assertThat(room.getSellerLastReadAt()).isEqualTo(NOW);
        assertThat(room.getBuyerLastReadAt()).isNull();
    }

    @Test
    void isParticipantWhenRoomMissingShouldReturnFalse() {
        when(chatRoomRepository.findById(roomId)).thenReturn(Optional.empty());

        assertThat(chatService.isParticipant(buyerId, roomId)).isFalse();
    }

    @Test
    void isParticipantWhenBuyerShouldReturnTrue() {
        when(chatRoomRepository.findById(roomId)).thenReturn(Optional.of(room));

        assertThat(chatService.isParticipant(buyerId, roomId)).isTrue();
    }
}
