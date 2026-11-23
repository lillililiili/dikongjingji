package com.lowaltitude.inspection.mall.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import com.lowaltitude.inspection.mall.infrastructure.persistence.MallCartMapper;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallCartMapper.OfferRow;
import org.junit.jupiter.api.Test;

/** 租赁询价（F3）读商城供给：只有已上架、在售、交易方式是租赁的供给能租；城市为空表示全国。 */
class MallRentalOffersTest {

    private static final UUID OFFER = UUID.fromString("7344eb2d-bf1d-aa17-7e27-fb2351e99dee");
    private static final UUID MERCHANT = UUID.fromString("c1000000-0000-4000-8000-000000000001");
    private static final UUID PRODUCT = UUID.fromString("c2000000-0000-4000-8000-000000000401");

    private final MallCartMapper mapper = mock(MallCartMapper.class);
    private final MallRentalOffers offers = new MallRentalOffers(mapper);

    @Test
    void aPublishedRentOfferIsRentable() {
        when(mapper.findOffer(OFFER)).thenReturn(row("RENT", "PUBLISHED", "ACTIVE", "ACTIVE", " 370500 "));

        assertThat(offers.find(OFFER)).contains(new MallRentalOffers.RentalOffer(OFFER, PRODUCT, "巡检机器狗", "标准版",
                null, MERCHANT, "示例机器人商家", "FACTORY_DIRECT", "370500", 38000L, "DAY", "示例机器人商家 东营仓", true));
    }

    @Test
    void anythingElseIsNotRentable() {
        for (OfferRow row : new OfferRow[] {
                row("BUY", "PUBLISHED", "ACTIVE", "ACTIVE", "370500"),
                row("RENT", "OFFLINE", "ACTIVE", "ACTIVE", "370500"),
                row("RENT", "PUBLISHED", "DISABLED", "ACTIVE", "370500"),
                row("RENT", "PUBLISHED", "ACTIVE", "DISABLED", "370500")}) {
            when(mapper.findOffer(OFFER)).thenReturn(row);
            assertThat(offers.find(OFFER)).hasValueSatisfying(offer -> assertThat(offer.rentable()).isFalse());
        }
        when(mapper.findOffer(OFFER)).thenReturn(row("RENT", "PUBLISHED", "ACTIVE", "ACTIVE", null));
        assertThat(offers.find(OFFER)).hasValueSatisfying(offer -> assertThat(offer.cityCode()).isNull());
        assertThat(offers.find(UUID.randomUUID())).isEmpty();
        assertThat(offers.find(null)).isEmpty();
    }

    private static OfferRow row(String saleMode, String productStatus, String skuStatus, String offerStatus,
                                String city) {
        return new OfferRow(OFFER, offerStatus, city, 38000L, "CNY", "DAY", null, "示例机器人商家 东营仓", true, null, 0,
                "FACTORY_DIRECT", MERCHANT, "示例机器人商家", UUID.randomUUID(), "标准版", skuStatus, PRODUCT, "巡检机器狗",
                null, saleMode, productStatus, null);
    }
}
