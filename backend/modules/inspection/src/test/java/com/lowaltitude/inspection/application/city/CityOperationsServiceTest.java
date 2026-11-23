package com.lowaltitude.inspection.application.city;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** 运营端“城市开通”的规则（S3-1）：按版本号改状态、状态没变不改、每次改留一条记录、筛选参数由服务端校验。 */
class CityOperationsServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-22T02:00:00Z");
    private static final UUID OPERATOR = UUID.fromString("90000000-0000-4000-8000-0000000000b1");

    private final FakeStore store = new FakeStore();
    private final CityOperationsService service = new CityOperationsService(store, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void changesTheStatusWithTheCurrentVersionAndRecordsWhoAndWhy() {
        store.put("370100", "济南市", "PREPARING", 3);

        CityOperationsService.OpsCityView changed = service.changeStatus("370100", "open", "  首批商家已入驻  ", 3,
                OPERATOR, "平台运营（示例）");

        assertThat(changed.serviceStatus()).isEqualTo(CityServiceStatus.OPEN);
        assertThat(changed.version()).isEqualTo(4);
        assertThat(changed.serviceStatusChangedAt()).isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        assertThat(store.changes).singleElement().satisfies(change -> {
            assertThat(change.fromStatus()).isEqualTo("PREPARING");
            assertThat(change.toStatus()).isEqualTo("OPEN");
            assertThat(change.note()).isEqualTo("首批商家已入驻");
            assertThat(change.operatorName()).isEqualTo("平台运营（示例）");
        });
        assertThat(service.changes("370100")).singleElement()
                .satisfies(view -> assertThat(view.toStatus()).isEqualTo(CityServiceStatus.OPEN));
    }

    @Test
    void refusesAStaleVersionAnUnchangedStatusAndAnUnknownCity() {
        store.put("370500", "东营市", "OPEN", 1);

        assertRejected(() -> service.changeStatus("370500", "CLOSED", "暂停", 0, OPERATOR, "运营"),
                CityOperationException.Error.VERSION_CONFLICT);
        assertRejected(() -> service.changeStatus("370500", "OPEN", "再开一次", 1, OPERATOR, "运营"),
                CityOperationException.Error.STATUS_UNCHANGED);
        assertRejected(() -> service.changeStatus("999999", "OPEN", "不存在", 0, OPERATOR, "运营"),
                CityOperationException.Error.NOT_FOUND);
        // 另一个人在读和写之间改了：更新一行也没改到，同样按版本冲突。
        store.failNextUpdate = true;
        assertRejected(() -> service.changeStatus("370500", "PREPARING", "回到筹备", 1, OPERATOR, "运营"),
                CityOperationException.Error.VERSION_CONFLICT);
        assertThat(store.changes).isEmpty();
    }

    @Test
    void validatesEveryInput() {
        store.put("370500", "东营市", "OPEN", 1);

        assertInvalid(() -> service.changeStatus("3705", "CLOSED", "x", 1, OPERATOR, "运营"));
        assertInvalid(() -> service.changeStatus("370500", "SUSPENDED", "x", 1, OPERATOR, "运营"));
        assertInvalid(() -> service.changeStatus("370500", "CLOSED", " ", 1, OPERATOR, "运营"));
        assertInvalid(() -> service.changeStatus("370500", "CLOSED", "长".repeat(201), 1, OPERATOR, "运营"));
        assertInvalid(() -> service.changeStatus("370500", "CLOSED", "x", null, OPERATOR, "运营"));
        assertInvalid(() -> service.changeStatus("370500", "CLOSED", "x", 1, null, "运营"));
        assertInvalid(() -> service.cities(null, "UNKNOWN", null, 1, 20));
        assertInvalid(() -> service.cities(null, null, "37", 1, 20));
        assertInvalid(() -> service.cities(null, null, null, 0, 20));
        assertInvalid(() -> service.cities(null, null, null, 1, 51));
        assertInvalid(() -> service.cities("一二三四五六七八九十一二三四五六七八九十一", null, null, 1, 20));
    }

    @Test
    void listsWithCleanedFiltersAndPages() {
        store.put("370500", "东营市", "OPEN", 1);
        store.put("370100", "济南市", "PREPARING", 0);

        CityOperationsService.OpsCityPageView page = service.cities(" 济%南_ ", "preparing", "370000", 1, 1);

        assertThat(store.lastKeyword).isEqualTo("济南");
        assertThat(store.lastStatus).isEqualTo("PREPARING");
        assertThat(store.lastProvince).isEqualTo("370000");
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.hasMore()).isTrue();
        assertThat(service.cities("  ", null, " ", 1, 20).items()).hasSize(2);
        assertThat(store.lastKeyword).isNull();
        assertThat(store.lastProvince).isNull();
        assertThat(service.cityName("370500")).contains("东营市");
        assertThat(service.cityName("x")).isEmpty();
    }

    private static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                       CityOperationException.Error error) {
        assertThatThrownBy(call).isInstanceOfSatisfying(CityOperationException.class,
                exception -> assertThat(exception.error()).isEqualTo(error));
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(IllegalArgumentException.class);
    }

    private static final class FakeStore implements CityOperationsService.Store {

        final Map<String, CityOperationsService.Row> rows = new LinkedHashMap<>();
        final List<CityOperationsService.ChangeRow> changes = new ArrayList<>();
        boolean failNextUpdate;
        String lastKeyword;
        String lastStatus;
        String lastProvince;

        void put(String code, String name, String status, int version) {
            rows.put(code, new CityOperationsService.Row(code, name, "370000", "山东省", status, null, version));
        }

        @Override
        public long count(String keyword, String serviceStatus, String provinceCode) {
            lastKeyword = keyword;
            lastStatus = serviceStatus;
            lastProvince = provinceCode;
            return rows.size();
        }

        @Override
        public List<CityOperationsService.Row> page(String keyword, String serviceStatus, String provinceCode,
                                                    int limit, int offset) {
            return rows.values().stream().skip(offset).limit(limit).toList();
        }

        @Override
        public Optional<CityOperationsService.Row> find(String cityCode) {
            return Optional.ofNullable(rows.get(cityCode));
        }

        @Override
        public int updateStatus(String cityCode, String serviceStatus, int version, OffsetDateTime at) {
            CityOperationsService.Row row = rows.get(cityCode);
            if (failNextUpdate || row == null || row.version() != version) {
                failNextUpdate = false;
                return 0;
            }
            rows.put(cityCode, new CityOperationsService.Row(cityCode, row.cityName(), row.provinceCode(),
                    row.provinceName(), serviceStatus, at, version + 1));
            return 1;
        }

        @Override
        public void insertChange(String cityCode, String fromStatus, String toStatus, String note, UUID operatorId,
                                 String operatorName, OffsetDateTime at) {
            changes.add(new CityOperationsService.ChangeRow(UUID.randomUUID(), fromStatus, toStatus, note,
                    operatorName, at));
        }

        @Override
        public List<CityOperationsService.ChangeRow> changes(String cityCode, int limit) {
            return changes.stream().limit(limit).toList();
        }
    }
}
