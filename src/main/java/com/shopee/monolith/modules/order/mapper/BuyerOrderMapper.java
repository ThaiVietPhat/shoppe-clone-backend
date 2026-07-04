package com.shopee.monolith.modules.order.mapper;

import com.shopee.monolith.modules.order.dto.response.BuyerOrderItemResponse;
import com.shopee.monolith.modules.order.dto.response.BuyerOrderSummaryResponse;
import com.shopee.monolith.modules.order.entity.Order;
import com.shopee.monolith.modules.order.entity.OrderItem;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface BuyerOrderMapper {

    BuyerOrderItemResponse toItemResponse(OrderItem orderItem);

    List<BuyerOrderItemResponse> toItemResponses(List<OrderItem> orderItems);

    @Mapping(source = "order.id", target = "orderId")
    @Mapping(source = "shopName", target = "shopName")
    @Mapping(source = "itemCount", target = "itemCount")
    @Mapping(source = "order.status", target = "status")
    @Mapping(source = "order.paymentStatus", target = "paymentStatus")
    @Mapping(source = "coverProductName", target = "coverProductName")
    @Mapping(source = "coverItemQuantity", target = "coverItemQuantity")
    @Mapping(source = "coverImageUrl", target = "coverImageUrl")
    BuyerOrderSummaryResponse toSummaryResponse(Order order, String shopName, int itemCount,
                                                 String coverProductName, int coverItemQuantity, String coverImageUrl);
}
