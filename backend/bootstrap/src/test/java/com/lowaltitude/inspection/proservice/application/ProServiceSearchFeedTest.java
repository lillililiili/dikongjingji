package com.lowaltitude.inspection.proservice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.IntStream;

import com.lowaltitude.inspection.search.application.SearchPorts;
import org.junit.jupiter.api.Test;

class ProServiceSearchFeedTest {

    private final ProServiceReadService service = mock(ProServiceReadService.class);

    private ProServicePageView page(ProServiceType type, long total, int shown) {
        List<ProServiceCardView> items = IntStream.range(0, shown).mapToObj(index -> mock(ProServiceCardView.class)).toList();
        return new ProServicePageView(type, "370500", "东营市", true, null, ProServiceSort.DEFAULT, 1, Math.max(1, shown),
                total, total > shown, items);
    }

    @Test
    void readsTheFourTypesInOrderUntilTheLimitIsFilledAndAddsUpTheTotals() {
        when(service.list(eq("AERIAL"), eq("370500"), eq("测绘"), any(), any(), any(), eq(1), anyInt()))
                .thenReturn(page(ProServiceType.AERIAL, 1, 1));
        when(service.list(eq("SURVEY"), eq("370500"), eq("测绘"), any(), any(), any(), eq(1), anyInt()))
                .thenReturn(page(ProServiceType.SURVEY, 4, 2));
        when(service.list(eq("MAINTENANCE"), eq("370500"), eq("测绘"), any(), any(), any(), eq(1), anyInt()))
                .thenReturn(page(ProServiceType.MAINTENANCE, 0, 0));
        when(service.list(eq("TECH_SUPPORT"), eq("370500"), eq("测绘"), any(), any(), any(), eq(1), anyInt()))
                .thenReturn(page(ProServiceType.TECH_SUPPORT, 2, 1));

        SearchPorts.Found found = new ProServiceSearchFeed(service).search("370500", "测绘", 3);

        assertThat(found.total()).isEqualTo(7);
        assertThat(found.items()).hasSize(3);
        verify(service).list("AERIAL", "370500", "测绘", null, null, null, 1, 3);
        verify(service).list("SURVEY", "370500", "测绘", null, null, null, 1, 2);
        // 凑满之后只读一条拿个数。
        verify(service).list("MAINTENANCE", "370500", "测绘", null, null, null, 1, 1);
        verify(service).list("TECH_SUPPORT", "370500", "测绘", null, null, null, 1, 1);
    }
}
