package com.lowaltitude.inspection.mall.application;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.lowaltitude.inspection.mall.infrastructure.persistence.MallCartMapper;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallInventoryMapper;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 所有库存写入与交易订单状态变更处于同一数据库事务。 */
@Transactional(propagation = Propagation.MANDATORY)
public class MallInventoryService {
    private final MallInventoryMapper mapper;
    private final MallCartMapper carts;

    public MallInventoryService(MallInventoryMapper mapper, MallCartMapper carts) {
        this.mapper = mapper;
        this.carts = carts;
    }

    /** 先锁购物车行及供给，再读取价格、商家、城市、配送和库存，直至订单事务结束。 */
    public void lockForCheckout(UUID accountId, List<UUID> cartItemIds, UUID directOfferId) {
        if (directOfferId != null) {
            requirePurchasable(directOfferId);
            return;
        }
        if (cartItemIds == null || cartItemIds.isEmpty()) {
            throw new MallCartException(MallCartException.Error.ITEM_UNAVAILABLE);
        }
        carts.lockOwner(MallCartService.LOCK_NAME, accountId);
        List<UUID> selected = mapper.lockCartOffers(accountId, cartItemIds);
        if (selected.size() != cartItemIds.size()) {
            throw new MallCartException(MallCartException.Error.ITEM_UNAVAILABLE);
        }
        selected.stream().distinct().sorted(Comparator.comparing(UUID::toString)).forEach(this::requirePurchasable);
    }

    private MallInventoryMapper.Offer requirePurchasable(UUID offerId) {
        var offer = mapper.lockPurchasable(offerId);
        if (offer == null) {
            throw new MallCartException(MallCartException.Error.ITEM_UNAVAILABLE);
        }
        return offer;
    }

    public void reserve(UUID orderId, Map<UUID, Integer> quantities) {
        if (orderId == null || quantities == null || quantities.isEmpty()
                || quantities.entrySet().stream().anyMatch(line ->
                        line.getKey() == null || line.getValue() == null || line.getValue() < 1)) {
            throw new IllegalArgumentException("order ID and positive offer quantities are required");
        }
        // PG 的 UUID 排序是无符号字节序，与标准字符串排序一致。多供给的预占和释放应按同一顺序加锁。
        var lines = quantities.entrySet().stream().sorted(Map.Entry.comparingByKey(
                Comparator.comparing(UUID::toString))).toList();
        String signature = lines.stream().map(line -> line.getKey() + "=" + line.getValue())
                .collect(Collectors.joining(","));
        if (mapper.begin(orderId, signature) == 0) {
            if (!signature.equals(mapper.signature(orderId))) {
                throw new IllegalStateException("stock reservation lines changed for the same order");
            }
            return;
        }
        for (var line : lines) {
            UUID offerId = line.getKey();
            var offer = requirePurchasable(offerId);
            if (offer.stockQuantity() != null) {
                int quantity = line.getValue();
                if (mapper.deduct(offerId, quantity) != 1) {
                    throw new MallCartException(MallCartException.Error.ITEM_UNAVAILABLE);
                }
                mapper.hold(orderId, offerId, quantity);
            }
        }
    }

    /** false 表示未付关闭已经释放，迟到的付款不能再次占用这批货。旧订单和不限库存不需要确认库存。 */
    public boolean confirm(UUID orderId) {
        List<MallInventoryMapper.Hold> holds = mapper.lockHolds(orderId);
        if (holds.stream().anyMatch(hold -> "RELEASED".equals(hold.status()))) {
            return false;
        }
        if (holds.stream().anyMatch(hold -> "HELD".equals(hold.status()))) {
            mapper.settle(orderId, "SOLD");
        }
        return true;
    }

    public void release(UUID orderId) {
        var held = mapper.lockHolds(orderId).stream().filter(hold -> "HELD".equals(hold.status())).toList();
        if (held.isEmpty()) {
            return;
        }
        for (var hold : held) {
            // 按原预占数退回当前可售数，保留商家其间的盘点更正；若现在不限库存，保留 NULL。
            mapper.restore(hold.offerId(), hold.quantity());
        }
        mapper.settle(orderId, "RELEASED");
    }
}
