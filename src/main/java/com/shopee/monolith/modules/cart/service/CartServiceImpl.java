package com.shopee.monolith.modules.cart.service;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.modules.cart.config.CartProperties;
import com.shopee.monolith.modules.cart.dto.internal.CartSnapshot;
import com.shopee.monolith.modules.cart.dto.internal.CartSnapshotItem;
import com.shopee.monolith.modules.cart.dto.request.AddCartItemRequest;
import com.shopee.monolith.modules.cart.dto.request.UpdateCartItemRequest;
import com.shopee.monolith.modules.cart.dto.response.CartItemResponse;
import com.shopee.monolith.modules.cart.dto.response.CartResponse;
import com.shopee.monolith.modules.product.dto.internal.VariantCartSummaryData;
import com.shopee.monolith.modules.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class CartServiceImpl implements CartService {

    private final StringRedisTemplate stringRedisTemplate;
    private final ProductService productService;
    private final CartProperties cartProperties;

    // KEYS[1]=items KEYS[2]=version KEYS[3]=selected
    // ARGV[1]=version_to_match
    private static final RedisScript<Long> CLEAR_CART_SCRIPT = RedisScript.of(
            "local cur = redis.call('get', KEYS[2])\n"
            + "if cur == ARGV[1] then\n"
            + "    redis.call('del', KEYS[1], KEYS[2], KEYS[3])\n"
            + "    return 1\n"
            + "else\n"
            + "    return 0\n"
            + "end",
            Long.class
    );

    // KEYS[1]=items KEYS[2]=version KEYS[3]=selected
    // ARGV[1]=ttl
    private static final RedisScript<Long> FULL_CLEAR_CART_SCRIPT = RedisScript.of(
            "redis.call('del', KEYS[1], KEYS[3])\n"
            + "redis.call('incr', KEYS[2])\n"
            + "redis.call('expire', KEYS[2], tonumber(ARGV[1]))\n"
            + "return 1",
            Long.class
    );

    // KEYS[1]=items KEYS[2]=version KEYS[3]=selected
    // ARGV[1]=variantId ARGV[2]=qty ARGV[3]=ttl
    private static final RedisScript<Long> UPDATE_ITEM_SCRIPT = RedisScript.of(
            "redis.call('hset', KEYS[1], ARGV[1], ARGV[2])\n"
            + "redis.call('incr', KEYS[2])\n"
            + "redis.call('expire', KEYS[1], tonumber(ARGV[3]))\n"
            + "redis.call('expire', KEYS[2], tonumber(ARGV[3]))\n"
            + "redis.call('expire', KEYS[3], tonumber(ARGV[3]))\n"
            + "return 1",
            Long.class
    );

    // KEYS[1]=items KEYS[2]=version KEYS[3]=selected
    // ARGV[1]=variantId ARGV[2]=qty ARGV[3]=maxQty ARGV[4]=ttl
    private static final RedisScript<Long> ADD_ITEM_SCRIPT = RedisScript.of(
            "local cur = tonumber(redis.call('hget', KEYS[1], ARGV[1]) or '0')\n"
            + "local nw = cur + tonumber(ARGV[2])\n"
            + "if nw > tonumber(ARGV[3]) then return -1 end\n"
            + "redis.call('hset', KEYS[1], ARGV[1], tostring(nw))\n"
            + "redis.call('incr', KEYS[2])\n"
            + "redis.call('expire', KEYS[1], ARGV[4])\n"
            + "redis.call('expire', KEYS[2], ARGV[4])\n"
            + "redis.call('expire', KEYS[3], ARGV[4])\n"
            + "return nw",
            Long.class
    );

    // KEYS[1]=items KEYS[2]=selected KEYS[3]=version
    // ARGV[1]=ttl ARGV[2..n]=variantIds
    // Returns -1 if any variantId not in items hash, else 1
    private static final RedisScript<Long> SELECT_ITEMS_SCRIPT = RedisScript.of(
            "local ttl = tonumber(ARGV[1])\n"
            + "for i=2,#ARGV do\n"
            + "    if redis.call('hexists', KEYS[1], ARGV[i]) == 0 then return -1 end\n"
            + "end\n"
            + "for i=2,#ARGV do redis.call('sadd', KEYS[2], ARGV[i]) end\n"
            + "redis.call('incr', KEYS[3])\n"
            + "redis.call('expire', KEYS[1], ttl)\n"
            + "redis.call('expire', KEYS[2], ttl)\n"
            + "redis.call('expire', KEYS[3], ttl)\n"
            + "return 1",
            Long.class
    );

    // KEYS[1]=selected KEYS[2]=version KEYS[3]=items
    // ARGV[1]=ttl ARGV[2..n]=variantIds
    private static final RedisScript<Long> DESELECT_ITEMS_SCRIPT = RedisScript.of(
            "local ttl = tonumber(ARGV[1])\n"
            + "for i=2,#ARGV do redis.call('srem', KEYS[1], ARGV[i]) end\n"
            + "redis.call('incr', KEYS[2])\n"
            + "redis.call('expire', KEYS[1], ttl)\n"
            + "redis.call('expire', KEYS[2], ttl)\n"
            + "redis.call('expire', KEYS[3], ttl)\n"
            + "return 1",
            Long.class
    );

    // KEYS[1]=items KEYS[2]=selected KEYS[3]=version
    // ARGV[1]=variantId ARGV[2]=ttl
    private static final RedisScript<Long> REMOVE_ITEM_SCRIPT = RedisScript.of(
            "redis.call('hdel', KEYS[1], ARGV[1])\n"
            + "redis.call('srem', KEYS[2], ARGV[1])\n"
            + "redis.call('incr', KEYS[3])\n"
            + "redis.call('expire', KEYS[1], ARGV[2])\n"
            + "redis.call('expire', KEYS[2], ARGV[2])\n"
            + "redis.call('expire', KEYS[3], ARGV[2])\n"
            + "return 1",
            Long.class
    );

    private String getItemsKey(UUID userId) {
        return "cart:" + userId + ":items";
    }

    private String getVersionKey(UUID userId) {
        return "cart:" + userId + ":version";
    }

    private String getSelectedKey(UUID userId) {
        return "cart:" + userId + ":selected";
    }

    @Override
    public CartResponse getCart(UUID userId) {
        if (userId == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        try {
            String itemsKey = getItemsKey(userId);
            String versionKey = getVersionKey(userId);
            String selectedKey = getSelectedKey(userId);

            Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(itemsKey);
            String versionStr = stringRedisTemplate.opsForValue().get(versionKey);
            long version = versionStr != null ? Long.parseLong(versionStr) : 0L;

            if (entries.isEmpty()) {
                return CartResponse.builder().items(List.of()).totalItems(0).version(version).build();
            }

            Set<String> selectedSet = stringRedisTemplate.opsForSet().members(selectedKey);
            Set<String> selected = selectedSet != null ? selectedSet : Set.of();

            List<UUID> variantIds = entries.keySet().stream()
                    .map(k -> UUID.fromString((String) k))
                    .toList();
            Map<UUID, VariantCartSummaryData> summaries = productService.findVariantCartSummariesByIds(variantIds);

            List<CartItemResponse> items = new ArrayList<>();
            for (Map.Entry<Object, Object> entry : entries.entrySet()) {
                UUID variantId = UUID.fromString((String) entry.getKey());
                int quantity = Integer.parseInt((String) entry.getValue());

                VariantCartSummaryData summary = summaries.get(variantId);
                if (summary == null) {
                    continue;
                }

                items.add(CartItemResponse.builder()
                        .variantId(summary.variantId())
                        .productId(summary.productId())
                        .shopId(summary.shopId())
                        .shopName(summary.shopName())
                        .productName(summary.productName())
                        .variantName(summary.variantName())
                        .optionLabels(summary.optionLabels())
                        .sku(summary.sku())
                        .price(summary.price())
                        .coverImageUrl(summary.coverImageUrl())
                        .availableStock(summary.availableStock())
                        .checkoutEligible(summary.checkoutEligible())
                        .quantity(quantity)
                        .selected(selected.contains(variantId.toString()))
                        .build());
            }

            return CartResponse.builder().items(items).totalItems(items.size()).version(version).build();

        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public CartResponse addItem(UUID userId, AddCartItemRequest request) {
        if (userId == null || request == null || request.variantId() == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        if (request.quantity() == null || request.quantity() <= 0) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        var variantOpt = productService.findActiveVariantLookupDataById(request.variantId());
        if (variantOpt.isEmpty()) {
            throw new AppException(ErrorCode.VARIANT_NOT_FOUND);
        }
        try {
            Long result = stringRedisTemplate.execute(
                    ADD_ITEM_SCRIPT,
                    List.of(getItemsKey(userId), getVersionKey(userId), getSelectedKey(userId)),
                    request.variantId().toString(),
                    String.valueOf(request.quantity()),
                    String.valueOf(cartProperties.getMaxQuantityPerItem()),
                    String.valueOf(cartProperties.getTtl().toSeconds())
            );
            if (result == null) {
                throw new AppException(ErrorCode.SERVICE_UNAVAILABLE);
            }
            if (result == -1) {
                throw new AppException(ErrorCode.INVALID_REQUEST);
            }
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        return getCart(userId);
    }

    @Override
    public CartResponse updateItem(UUID userId, UUID variantId, UpdateCartItemRequest request) {
        if (userId == null || variantId == null || request == null || request.quantity() == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        int quantity = request.quantity();
        if (quantity < 0) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        if (quantity == 0) {
            removeItem(userId, variantId);
            return getCart(userId);
        }
        var variantOpt = productService.findActiveVariantLookupDataById(variantId);
        if (variantOpt.isEmpty()) {
            throw new AppException(ErrorCode.VARIANT_NOT_FOUND);
        }
        if (quantity > cartProperties.getMaxQuantityPerItem()) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }

        try {
            stringRedisTemplate.execute(
                    UPDATE_ITEM_SCRIPT,
                    List.of(getItemsKey(userId), getVersionKey(userId), getSelectedKey(userId)),
                    variantId.toString(),
                    String.valueOf(quantity),
                    String.valueOf(cartProperties.getTtl().toSeconds())
            );
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        return getCart(userId);
    }

    @Override
    public void removeItem(UUID userId, UUID variantId) {
        if (userId == null || variantId == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        try {
            stringRedisTemplate.execute(
                    REMOVE_ITEM_SCRIPT,
                    List.of(getItemsKey(userId), getSelectedKey(userId), getVersionKey(userId)),
                    variantId.toString(),
                    String.valueOf(cartProperties.getTtl().toSeconds())
            );
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public void clearCart(UUID userId) {
        if (userId == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        try {
            stringRedisTemplate.execute(
                    FULL_CLEAR_CART_SCRIPT,
                    List.of(getItemsKey(userId), getVersionKey(userId), getSelectedKey(userId)),
                    String.valueOf(cartProperties.getTtl().toSeconds())
            );
        } catch (Exception e) {
            throw new AppException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public CartResponse selectItems(UUID userId, List<UUID> variantIds) {
        if (userId == null || variantIds == null || variantIds.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        try {
            List<String> args = Stream.concat(
                    Stream.of(String.valueOf(cartProperties.getTtl().toSeconds())),
                    variantIds.stream().map(UUID::toString)
            ).collect(Collectors.toList());

            Long result = stringRedisTemplate.execute(
                    SELECT_ITEMS_SCRIPT,
                    List.of(getItemsKey(userId), getSelectedKey(userId), getVersionKey(userId)),
                    args.toArray(new String[0])
            );
            if (result == null) {
                throw new AppException(ErrorCode.SERVICE_UNAVAILABLE);
            }
            if (result == -1L) {
                throw new AppException(ErrorCode.INVALID_REQUEST);
            }
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        return getCart(userId);
    }

    @Override
    public CartResponse deselectItems(UUID userId, List<UUID> variantIds) {
        if (userId == null || variantIds == null || variantIds.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        try {
            List<String> args = Stream.concat(
                    Stream.of(String.valueOf(cartProperties.getTtl().toSeconds())),
                    variantIds.stream().map(UUID::toString)
            ).collect(Collectors.toList());

            Long result = stringRedisTemplate.execute(
                    DESELECT_ITEMS_SCRIPT,
                    List.of(getSelectedKey(userId), getVersionKey(userId), getItemsKey(userId)),
                    args.toArray(new String[0])
            );
            if (result == null) {
                throw new AppException(ErrorCode.SERVICE_UNAVAILABLE);
            }
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            throw new AppException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        return getCart(userId);
    }

    @Override
    public CartSnapshot getSnapshot(UUID userId) {
        if (userId == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        try {
            Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(getItemsKey(userId));
            String versionStr = stringRedisTemplate.opsForValue().get(getVersionKey(userId));
            long version = versionStr != null ? Long.parseLong(versionStr) : 0L;

            List<CartSnapshotItem> items = new ArrayList<>();
            for (Map.Entry<Object, Object> entry : entries.entrySet()) {
                items.add(new CartSnapshotItem(
                        UUID.fromString((String) entry.getKey()),
                        Integer.parseInt((String) entry.getValue())
                ));
            }
            return CartSnapshot.builder().userId(userId).items(items).version(version).build();
        } catch (Exception e) {
            throw new AppException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public CartSnapshot getSelectedSnapshot(UUID userId) {
        if (userId == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        try {
            Set<String> selectedMembers = stringRedisTemplate.opsForSet().members(getSelectedKey(userId));
            String versionStr = stringRedisTemplate.opsForValue().get(getVersionKey(userId));
            long version = versionStr != null ? Long.parseLong(versionStr) : 0L;

            if (selectedMembers == null || selectedMembers.isEmpty()) {
                return CartSnapshot.builder().userId(userId).items(List.of()).version(version).build();
            }

            List<String> memberList = new ArrayList<>(selectedMembers);
            List<Object> quantities = stringRedisTemplate.opsForHash().multiGet(
                    getItemsKey(userId), new ArrayList<>(memberList));
            List<CartSnapshotItem> items = new ArrayList<>();
            for (int i = 0; i < memberList.size(); i++) {
                Object qtyObj = quantities.get(i);
                if (qtyObj != null) {
                    items.add(new CartSnapshotItem(UUID.fromString(memberList.get(i)),
                            Integer.parseInt(qtyObj.toString())));
                }
            }
            return CartSnapshot.builder().userId(userId).items(items).version(version).build();
        } catch (Exception e) {
            throw new AppException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public void clearSnapshotIfVersionUnchanged(UUID userId, long version) {
        if (userId == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        try {
            stringRedisTemplate.execute(
                    CLEAR_CART_SCRIPT,
                    List.of(getItemsKey(userId), getVersionKey(userId), getSelectedKey(userId)),
                    String.valueOf(version)
            );
        } catch (Exception e) {
            throw new AppException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}
