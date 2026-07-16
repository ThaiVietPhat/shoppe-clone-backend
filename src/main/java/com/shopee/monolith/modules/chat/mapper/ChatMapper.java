package com.shopee.monolith.modules.chat.mapper;

import com.shopee.monolith.modules.chat.dto.response.ChatMessageResponse;
import com.shopee.monolith.modules.chat.dto.response.ChatRoomResponse;
import com.shopee.monolith.modules.chat.entity.ChatMessage;
import com.shopee.monolith.modules.chat.entity.ChatRoom;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ChatMapper {

    @Mapping(target = "id", source = "room.id")
    @Mapping(target = "createdAt", source = "room.createdAt")
    @Mapping(target = "lastMessageContent", source = "lastMessageContent")
    @Mapping(target = "lastMessageSenderId", source = "lastMessageSenderId")
    @Mapping(target = "lastMessageAt", source = "lastMessageAt")
    @Mapping(target = "unreadCount", source = "unreadCount")
    ChatRoomResponse toRoomResponse(ChatRoom room, String shopName, String buyerEmail, String lastMessageContent,
                                     java.util.UUID lastMessageSenderId, java.time.Instant lastMessageAt,
                                     long unreadCount);

    ChatMessageResponse toMessageResponse(ChatMessage message);
}
