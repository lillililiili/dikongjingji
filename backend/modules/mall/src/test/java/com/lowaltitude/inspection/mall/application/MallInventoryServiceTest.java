package com.lowaltitude.inspection.mall.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lowaltitude.inspection.mall.infrastructure.persistence.MallCartMapper;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallInventoryMapper;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallInventoryMapper.Hold;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallInventoryMapper.Offer;
import org.junit.jupiter.api.Test;

class MallInventoryServiceTest {
    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID FIRST = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID LAST = UUID.fromString("f0000000-0000-4000-8000-000000000002");
    private final MallInventoryMapper mapper = mock(MallInventoryMapper.class);
    private final MallCartMapper carts = mock(MallCartMapper.class);
    private final MallInventoryService inventory = new MallInventoryService(mapper, carts);

    @Test
    void checkoutLocksOnlyOwnedCartRowsThenOffersInUuidOrder() {
        var lines = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(mapper.lockCartOffers(ACCOUNT, lines)).thenReturn(List.of(LAST, FIRST));
        when(mapper.lockPurchasable(FIRST)).thenReturn(new Offer(FIRST, 3));
        when(mapper.lockPurchasable(LAST)).thenReturn(new Offer(LAST, null));
        inventory.lockForCheckout(ACCOUNT, lines, null);
        var ordered = inOrder(mapper, carts);
        ordered.verify(carts).lockOwner("mall-cart:", ACCOUNT);
        ordered.verify(mapper).lockCartOffers(ACCOUNT, lines);
        ordered.verify(mapper).lockPurchasable(FIRST);
        ordered.verify(mapper).lockPurchasable(LAST);
        when(mapper.lockCartOffers(ACCOUNT, lines)).thenReturn(List.of(FIRST));
        assertThatThrownBy(() -> inventory.lockForCheckout(ACCOUNT, lines, null))
                .isInstanceOf(MallCartException.class);
    }

    @Test
    void reservesOnlyFiniteStockAndRetriesNeverReallocate() {
        String signature = FIRST + "=1," + LAST + "=2";
        when(mapper.begin(ORDER, signature)).thenReturn(1, 0);
        when(mapper.signature(ORDER)).thenReturn(signature);
        when(mapper.lockPurchasable(FIRST)).thenReturn(new Offer(FIRST, null));
        when(mapper.lockPurchasable(LAST)).thenReturn(new Offer(LAST, 5));
        when(mapper.deduct(LAST, 2)).thenReturn(1);
        inventory.reserve(ORDER, Map.of(LAST, 2, FIRST, 1));
        inventory.reserve(ORDER, Map.of(FIRST, 1, LAST, 2));
        verify(mapper, never()).deduct(eq(FIRST), anyInt());
        verify(mapper).deduct(LAST, 2);
        verify(mapper).hold(ORDER, LAST, 2);
        assertThatThrownBy(() -> inventory.reserve(ORDER, Map.of(FIRST, 2, LAST, 2)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void soldOutOrDelistedOfferRejectsTheOrderWithCheckoutError() {
        when(mapper.begin(eq(ORDER), anyString())).thenReturn(1);
        when(mapper.lockPurchasable(FIRST)).thenReturn(new Offer(FIRST, 0));
        for (UUID id : List.of(FIRST, LAST)) {
            assertThatThrownBy(() -> inventory.reserve(ORDER, Map.of(id, 1)))
                    .isInstanceOfSatisfying(MallCartException.class, e ->
                            assertThat(e.error()).isEqualTo(MallCartException.Error.ITEM_UNAVAILABLE));
        }
        verify(mapper, never()).hold(any(), any(), anyInt());
    }

    @Test
    void confirmationNeverDeductsAgainAndOldOrdersHaveNoStockMutation() {
        when(mapper.lockHolds(ORDER)).thenReturn(List.of(new Hold(FIRST, 2, "HELD")),
                List.of(new Hold(FIRST, 2, "SOLD")), List.of(), List.of());
        assertThat(inventory.confirm(ORDER)).isTrue();
        assertThat(inventory.confirm(ORDER)).isTrue();
        assertThat(inventory.confirm(ORDER)).isTrue();
        inventory.release(ORDER);
        verify(mapper).settle(ORDER, "SOLD");
        verify(mapper, never()).deduct(any(), anyInt());
        verify(mapper, never()).restore(any(), anyInt());
    }

    @Test
    void unpaidClosureReturnsOriginalHeldUnitsOnlyOnceAndRejectsLatePayment() {
        when(mapper.lockHolds(ORDER)).thenReturn(List.of(new Hold(FIRST, 2, "HELD")),
                List.of(new Hold(FIRST, 2, "RELEASED")), List.of(new Hold(FIRST, 2, "RELEASED")));
        inventory.release(ORDER);
        inventory.release(ORDER);
        assertThat(inventory.confirm(ORDER)).isFalse();
        verify(mapper).restore(FIRST, 2);
        verify(mapper).settle(ORDER, "RELEASED");
        verify(mapper, never()).settle(ORDER, "SOLD");
    }
}
