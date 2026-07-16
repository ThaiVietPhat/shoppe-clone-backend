package com.shopee.monolith.modules.chat.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
@Schema(name = "ChatRoomResponse", description = "One buyer-shop chat room visible to the caller")
public record ChatRoomResponse(
        @Schema(description = "Room ID")
        UUID id,

        @Schema(description = "Buyer participant user ID")
        UUID buyerId,

        @Schema(description = "Buyer display identifier (email) — the seller side has no other way "
                + "to tell rooms apart, since every room's shopName is their own shop's name")
        String buyerEmail,

        @Schema(description = "Shop participant ID")
        UUID shopId,

        @Schema(description = "Shop display name", example = "Demo Official Store")
        String shopName,

        @Schema(description = "Last time the buyer read this room (read receipt)")
        Instant buyerLastReadAt,

        @Schema(description = "Last time the seller read this room (read receipt)")
        Instant sellerLastReadAt,

        @Schema(description = "Content of the most recent message in the room (null if no messages yet)")
        String lastMessageContent,

        @Schema(description = "Sender of the most recent message (null if no messages yet)")
        UUID lastMessageSenderId,

        @Schema(description = "Timestamp of the most recent message (null if no messages yet)")
        Instant lastMessageAt,

        @Schema(description = "Number of messages not yet read by the caller, excluding the caller's own messages")
        long unreadCount,

        @Schema(description = "Timestamp when created")
        Instant createdAt
) {
}
