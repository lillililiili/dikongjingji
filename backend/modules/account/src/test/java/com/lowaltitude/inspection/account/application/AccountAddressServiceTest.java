package com.lowaltitude.inspection.account.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.account.application.RegionDirectory.Region;
import com.lowaltitude.inspection.account.infrastructure.persistence.AccountAddressMapper;
import com.lowaltitude.inspection.account.infrastructure.persistence.AccountAddressMapper.AddressRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 地址簿的规则测试。mapper 换成内存实现（按 SQL 的条件和 V001 的约束写，含“每个账号最多一条默认”的部分唯一索引），
 * 地区目录换成两个城市的假实现，时钟固定。SQL 文本本身由 AccountAddressMapperContractTest 检查。
 */
class AccountAddressServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-17T04:00:00Z");
    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_OWNER = UUID.fromString("11111111-1111-1111-1111-222222222222");

    private final InMemoryMapper mapper = new InMemoryMapper();

    // 同一账号的写操作排队：并发新增不能突破 20 条，并发换默认不会撞上唯一约束。
    @Test
    void everyWriteTakesTheOwnerLockFirst() {
        AddressView first = service.create(OWNER, form("王女士", null));
        service.update(OWNER, first.addressId(), form("王女士", true));
        service.delete(OWNER, first.addressId());

        assertThat(mapper.locks).containsExactly(
                "account-address:" + OWNER, "account-address:" + OWNER, "account-address:" + OWNER);
    }
    private final FakeRegions regions = new FakeRegions();
    private final AccountAddressService service =
            new AccountAddressService(mapper, regions, Clock.fixed(NOW, ZoneOffset.UTC));

    // ---- 新增与默认 ----

    @Test
    void theFirstAddressIsTheDefaultEvenWhenItIsNotAskedFor() {
        AddressView first = service.create(OWNER, form("王女士", null));

        assertThat(first.isDefault()).isTrue();
        assertThat(first.recipientName()).isEqualTo("王女士");
        assertThat(first.phone()).isEqualTo("13800138000");
        assertThat(first.detail()).isEqualTo("东营区示例路 18 号");
        // 省份由服务端按城市补上，客户端只传城市编码。
        assertThat(first.region()).isEqualTo(new AddressRegionView("370000", "山东省", "370500", "东营市"));
        assertThat(mapper.rows).singleElement().satisfies(row -> {
            assertThat(row.ownerId).isEqualTo(OWNER);
            assertThat(row.createdAt).isEqualTo(NOW);
            assertThat(row.updatedAt).isEqualTo(NOW);
        });
        assertThat(service.create(OTHER_OWNER, form("李先生", false)).isDefault())
                .as("另一个账号的第一条也是默认")
                .isTrue();
    }

    @Test
    void aLaterAddressBecomesTheDefaultOnlyWhenAskedAndTakesItOver() {
        AddressView first = service.create(OWNER, form("王女士", null));
        AddressView second = service.create(OWNER, form("李先生", null));
        AddressView third = service.create(OWNER, form("张先生", false));

        assertThat(second.isDefault()).isFalse();
        assertThat(third.isDefault()).isFalse();

        AddressView fourth = service.create(OWNER, form("赵女士", true));

        assertThat(fourth.isDefault()).isTrue();
        assertThat(defaults(OWNER)).containsExactly(fourth.addressId());
        assertThat(mapper.find(first.addressId()).isDefault).isFalse();
    }

    @Test
    void theListPutsTheDefaultFirstAndTheRestInTheOrderTheyWereAdded() {
        AddressView first = service.create(OWNER, form("王女士", null));
        AddressView second = service.create(OWNER, form("李先生", null));
        AddressView third = service.create(OWNER, form("张先生", true));
        service.create(OTHER_OWNER, form("别人", null));

        assertThat(service.list(OWNER).items())
                .extracting(AddressView::addressId, AddressView::isDefault)
                .containsExactly(
                        tuple(third.addressId(), true),
                        tuple(first.addressId(), false),
                        tuple(second.addressId(), false));
        assertThat(service.list(UUID.randomUUID()).items()).isEmpty();
    }

    @Test
    void valuesAreTrimmedAndTheBoundaryLengthsAreAccepted() {
        AddressView view = service.create(OWNER, new AddressForm(
                "  " + "名".repeat(20) + " ", " 13912345678 ", " 370100 ", " " + "址".repeat(80) + "  ", null));

        assertThat(view.recipientName()).isEqualTo("名".repeat(20));
        assertThat(view.phone()).isEqualTo("13912345678");
        assertThat(view.detail()).isEqualTo("址".repeat(80));
        assertThat(view.region()).isEqualTo(new AddressRegionView("370000", "山东省", "370100", "济南市"));
    }

    @Test
    void savedRegionNamesStayWhenTheDirectoryRenamesTheCityLater() {
        AddressView saved = service.create(OWNER, form("王女士", null));
        regions.cities.put("370500", new Region("370000", "山东", "370500", "东营"));

        assertThat(service.list(OWNER).items()).singleElement()
                .extracting(AddressView::region)
                .isEqualTo(saved.region());
    }

    @Test
    void theTwentyFirstAddressIsRefusedAndNothingIsWritten() {
        for (int i = 0; i < AccountAddressService.MAX_ADDRESSES; i++) {
            service.create(OWNER, form("收货人" + i, null));
        }

        assertThatThrownBy(() -> service.create(OWNER, form("第二十一", true)))
                .isInstanceOf(AddressLimitException.class);
        assertThat(service.list(OWNER).items()).hasSize(20);
        assertThat(defaults(OWNER)).hasSize(1);
        // 另一个账号不受这个账号的数量影响。
        assertThat(service.create(OTHER_OWNER, form("别人", null))).isNotNull();

        // 删掉一条后又能添加。
        service.delete(OWNER, service.list(OWNER).items().get(5).addressId());
        assertThat(service.create(OWNER, form("第二十一", null)).recipientName()).isEqualTo("第二十一");
    }

    // ---- 校验 ----

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "12345678901", "138001380000", "12800138000", "23800138000",
            "1380013800a", "138 0013 8000", "+8613800138000"})
    void aPhoneThatIsNotAMainlandMobileNumberIsRefused(String phone) {
        assertInvalid(new AddressForm("王女士", phone, "370500", "东营区示例路 18 号", null));
    }

    @Test
    void aMissingOrMalformedFieldIsRefusedBeforeAnythingIsWritten() {
        assertInvalid(null);
        assertInvalid(new AddressForm(null, "13800138000", "370500", "示例路", null));
        assertInvalid(new AddressForm("  ", "13800138000", "370500", "示例路", null));
        assertInvalid(new AddressForm("名".repeat(21), "13800138000", "370500", "示例路", null));
        assertInvalid(new AddressForm("王\n女士", "13800138000", "370500", "示例路", null));
        assertInvalid(new AddressForm("王女士", null, "370500", "示例路", null));
        assertInvalid(new AddressForm("王女士", "13800138000", "370500", null, null));
        assertInvalid(new AddressForm("王女士", "13800138000", "370500", " ", null));
        assertInvalid(new AddressForm("王女士", "13800138000", "370500", "址".repeat(81), null));
        assertInvalid(new AddressForm("王女士", "13800138000", "370500", "示例路\t18 号", null));
        // 城市只认平台目录里的地级市；区县编码和未知编码都不行。
        assertInvalid(new AddressForm("王女士", "13800138000", null, "示例路", null));
        assertInvalid(new AddressForm("王女士", "13800138000", "370502", "示例路", null));
        assertInvalid(new AddressForm("王女士", "13800138000", "", "示例路", null));

        assertThat(mapper.rows).isEmpty();
    }

    @Test
    void anAccountAndAnAddressIdAreRequired() {
        AddressView saved = service.create(OWNER, form("王女士", null));

        assertThatThrownBy(() -> service.list(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(null, form("王女士", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(null, saved.addressId(), form("王女士", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(OWNER, null, form("王女士", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.delete(OWNER, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.delete(null, saved.addressId()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.findForUse(null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anInvalidUpdateLeavesTheAddressAsItWas() {
        AddressView saved = service.create(OWNER, form("王女士", null));

        assertThatThrownBy(() -> service.update(
                OWNER, saved.addressId(), new AddressForm("王女士", "1380013800", "370100", "新地址", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.list(OWNER).items()).containsExactly(saved);
    }

    // ---- 修改 ----

    @Test
    void anUpdateReplacesEveryFieldAndTakesTheProvinceFromTheNewCity() {
        AddressView saved = service.create(OWNER, form("王女士", null));

        AddressView updated = service.update(OWNER, saved.addressId(),
                new AddressForm("李先生", "15912345678", "370100", "历下区示例路 1 号", null));

        assertThat(updated.addressId()).isEqualTo(saved.addressId());
        assertThat(updated.recipientName()).isEqualTo("李先生");
        assertThat(updated.phone()).isEqualTo("15912345678");
        assertThat(updated.detail()).isEqualTo("历下区示例路 1 号");
        assertThat(updated.region()).isEqualTo(new AddressRegionView("370000", "山东省", "370100", "济南市"));
        assertThat(mapper.find(saved.addressId()).version).isEqualTo(1);
    }

    @Test
    void theDefaultCannotBeSwitchedOffDirectly() {
        AddressView first = service.create(OWNER, form("王女士", null));
        service.create(OWNER, form("李先生", null));

        assertThat(service.update(OWNER, first.addressId(), form("王女士", false)).isDefault()).isTrue();
        assertThat(service.update(OWNER, first.addressId(), form("王女士", null)).isDefault()).isTrue();
        assertThat(defaults(OWNER)).containsExactly(first.addressId());
    }

    @Test
    void makingAnotherAddressTheDefaultMovesTheDefaultToIt() {
        AddressView first = service.create(OWNER, form("王女士", null));
        AddressView second = service.create(OWNER, form("李先生", null));

        assertThat(service.update(OWNER, second.addressId(), form("李先生", null)).isDefault()).isFalse();
        assertThat(service.update(OWNER, second.addressId(), form("李先生", false)).isDefault()).isFalse();
        assertThat(defaults(OWNER)).containsExactly(first.addressId());

        AddressView promoted = service.update(OWNER, second.addressId(), form("李先生", true));

        assertThat(promoted.isDefault()).isTrue();
        assertThat(defaults(OWNER)).containsExactly(second.addressId());
        // 已经是默认的再设一次默认，不去掉自己。
        assertThat(service.update(OWNER, second.addressId(), form("李先生", true)).isDefault()).isTrue();
        assertThat(defaults(OWNER)).containsExactly(second.addressId());
    }

    // ---- 删除 ----

    @Test
    void deletingTheDefaultPromotesTheOldestRemainingAddress() {
        AddressView first = service.create(OWNER, form("王女士", null));
        AddressView second = service.create(OWNER, form("李先生", null));
        AddressView third = service.create(OWNER, form("张先生", null));
        AddressView fourth = service.create(OWNER, form("赵女士", true));

        service.delete(OWNER, fourth.addressId());

        assertThat(defaults(OWNER)).containsExactly(first.addressId());
        assertThat(mapper.find(fourth.addressId()).status).isEqualTo("DELETED");
        assertThat(mapper.find(fourth.addressId()).isDefault).isFalse();

        service.delete(OWNER, first.addressId());

        assertThat(defaults(OWNER)).containsExactly(second.addressId());
        assertThat(service.list(OWNER).items())
                .extracting(AddressView::addressId)
                .containsExactly(second.addressId(), third.addressId());
    }

    @Test
    void deletingAnotherAddressKeepsTheDefault() {
        AddressView first = service.create(OWNER, form("王女士", null));
        AddressView second = service.create(OWNER, form("李先生", null));

        service.delete(OWNER, second.addressId());

        assertThat(defaults(OWNER)).containsExactly(first.addressId());
        assertThat(mapper.find(first.addressId()).version).isZero();
    }

    @Test
    void deletingTheLastAddressLeavesAnEmptyBookAndTheNextAddressIsTheDefaultAgain() {
        AddressView only = service.create(OWNER, form("王女士", null));

        service.delete(OWNER, only.addressId());

        assertThat(service.list(OWNER).items()).isEmpty();
        assertThat(service.findForUse(OWNER, null)).isEmpty();
        assertThat(service.create(OWNER, form("李先生", false)).isDefault()).isTrue();
    }

    @Test
    void aDeletedAddressIsGoneForEveryOperation() {
        AddressView first = service.create(OWNER, form("王女士", null));
        AddressView second = service.create(OWNER, form("李先生", null));
        service.delete(OWNER, second.addressId());

        assertThatThrownBy(() -> service.delete(OWNER, second.addressId()))
                .isInstanceOf(AddressNotFoundException.class);
        assertThatThrownBy(() -> service.update(OWNER, second.addressId(), form("李先生", null)))
                .isInstanceOf(AddressNotFoundException.class);
        assertThatThrownBy(() -> service.findForUse(OWNER, second.addressId()))
                .isInstanceOf(AddressNotFoundException.class);
        assertThat(service.list(OWNER).items()).extracting(AddressView::addressId)
                .containsExactly(first.addressId());
    }

    // ---- 账号隔离 ----

    @Test
    void anotherAccountsAddressIsNotFoundAndStaysUntouched() {
        AddressView theirs = service.create(OTHER_OWNER, form("别人", null));
        service.create(OWNER, form("王女士", null));

        assertThatThrownBy(() -> service.update(OWNER, theirs.addressId(), form("改掉", true)))
                .isInstanceOf(AddressNotFoundException.class);
        assertThatThrownBy(() -> service.delete(OWNER, theirs.addressId()))
                .isInstanceOf(AddressNotFoundException.class);
        assertThatThrownBy(() -> service.findForUse(OWNER, theirs.addressId()))
                .isInstanceOf(AddressNotFoundException.class);

        assertThat(service.list(OTHER_OWNER).items()).containsExactly(theirs);
        assertThat(service.list(OWNER).items()).extracting(AddressView::recipientName).containsExactly("王女士");
        assertThat(mapper.find(theirs.addressId()).version).isZero();
    }

    // ---- 确认订单用 ----

    @Test
    void findForUseReturnsTheNamedAddressOrElseTheDefaultOrElseNothing() {
        assertThat(service.findForUse(OWNER, null)).isEmpty();

        AddressView first = service.create(OWNER, form("王女士", null));
        AddressView second = service.create(OWNER, form("李先生", null));

        assertThat(service.findForUse(OWNER, second.addressId())).contains(second);
        assertThat(service.findForUse(OWNER, null)).contains(first);
        assertThatThrownBy(() -> service.findForUse(OWNER, UUID.randomUUID()))
                .isInstanceOf(AddressNotFoundException.class);
        // 别的账号读不到这个账号的默认地址。
        assertThat(service.findForUse(OTHER_OWNER, null)).isEmpty();
    }

    // ---- 辅助 ----

    private void assertInvalid(AddressForm form) {
        assertThatThrownBy(() -> service.create(OWNER, form)).isInstanceOf(IllegalArgumentException.class);
    }

    private List<UUID> defaults(UUID ownerId) {
        return mapper.rows.stream()
                .filter(row -> row.ownerId.equals(ownerId) && row.active() && row.isDefault)
                .map(row -> row.id)
                .toList();
    }

    private static AddressForm form(String name, Boolean isDefault) {
        return new AddressForm(name, "13800138000", "370500", "东营区示例路 18 号", isDefault);
    }

    /** 平台城市目录里只有两个地级市。 */
    private static final class FakeRegions implements RegionDirectory {

        final Map<String, Region> cities = new HashMap<>(Map.of(
                "370500", new Region("370000", "山东省", "370500", "东营市"),
                "370100", new Region("370000", "山东省", "370100", "济南市")));

        @Override
        public Optional<Region> findCity(String cityCode) {
            return Optional.ofNullable(cities.get(cityCode));
        }
    }

    /** 按 AccountAddressMapper 的 SQL 写的内存实现；插入或改动后检查“每个账号最多一条默认”。 */
    private static final class InMemoryMapper implements AccountAddressMapper {
        // S5-8 注销去标识：这个账号的地址全部标记删除。
        @Override
        public int eraseAll(UUID ownerId, java.time.Instant at) {
            return 0;
        }

        final List<String> locks = new java.util.ArrayList<>();

        final List<Row> rows = new ArrayList<>();
        private long sequence;

        Row find(UUID id) {
            return rows.stream().filter(row -> row.id.equals(id)).findFirst().orElseThrow();
        }

        @Override
        public List<AddressRow> findAll(UUID ownerId) {
            return active(ownerId).stream()
                    .sorted(Comparator.comparing((Row row) -> !row.isDefault)
                            .thenComparing(row -> row.sequence))
                    .map(Row::toRow)
                    .toList();
        }

        @Override
        public AddressRow findOne(UUID ownerId, UUID addressId) {
            return active(ownerId).stream()
                    .filter(row -> row.id.equals(addressId))
                    .findFirst()
                    .map(Row::toRow)
                    .orElse(null);
        }

        @Override
        public AddressRow findDefault(UUID ownerId) {
            return active(ownerId).stream().filter(row -> row.isDefault).findFirst().map(Row::toRow).orElse(null);
        }

        @Override
        public Integer lockOwner(String lockName, UUID ownerId) {
            locks.add(lockName + ownerId);
            return 1;
        }

        @Override
        public int count(UUID ownerId) {
            return active(ownerId).size();
        }

        @Override
        public int clearDefault(UUID ownerId, Instant at) {
            int changed = 0;
            for (Row row : active(ownerId)) {
                if (row.isDefault) {
                    row.isDefault = false;
                    row.touch(at);
                    changed++;
                }
            }
            return changed;
        }

        @Override
        public UUID insert(
                UUID ownerId,
                String recipientName,
                String phone,
                String provinceCode,
                String provinceName,
                String cityCode,
                String cityName,
                String detail,
                boolean isDefault,
                Instant at
        ) {
            Row row = new Row();
            row.id = UUID.randomUUID();
            row.ownerId = ownerId;
            row.sequence = ++sequence;
            row.createdAt = at;
            row.updatedAt = at;
            row.set(recipientName, phone, provinceCode, provinceName, cityCode, cityName, detail, isDefault);
            rows.add(row);
            checkConstraints(ownerId);
            return row.id;
        }

        @Override
        public int update(
                UUID ownerId,
                UUID addressId,
                String recipientName,
                String phone,
                String provinceCode,
                String provinceName,
                String cityCode,
                String cityName,
                String detail,
                boolean isDefault,
                Instant at
        ) {
            int changed = 0;
            for (Row row : active(ownerId)) {
                if (row.id.equals(addressId)) {
                    row.set(recipientName, phone, provinceCode, provinceName, cityCode, cityName, detail, isDefault);
                    row.touch(at);
                    changed++;
                }
            }
            checkConstraints(ownerId);
            return changed;
        }

        @Override
        public int delete(UUID ownerId, UUID addressId, Instant at) {
            int changed = 0;
            for (Row row : active(ownerId)) {
                if (row.id.equals(addressId)) {
                    row.status = "DELETED";
                    row.isDefault = false;
                    row.touch(at);
                    changed++;
                }
            }
            return changed;
        }

        @Override
        public int promoteOldestToDefault(UUID ownerId, Instant at) {
            List<Row> active = active(ownerId);
            if (active.isEmpty() || active.stream().anyMatch(row -> row.isDefault)) {
                return 0;
            }
            Row oldest = active.stream().min(Comparator.comparing(row -> row.sequence)).orElseThrow();
            oldest.isDefault = true;
            oldest.touch(at);
            checkConstraints(ownerId);
            return 1;
        }

        private List<Row> active(UUID ownerId) {
            return rows.stream().filter(row -> row.ownerId.equals(ownerId) && row.active()).toList();
        }

        /** uq_account_address_default 和 phone 的 CHECK。 */
        private void checkConstraints(UUID ownerId) {
            if (active(ownerId).stream().filter(row -> row.isDefault).count() > 1) {
                throw new IllegalStateException("duplicate key value violates uq_account_address_default");
            }
            if (rows.stream().anyMatch(row -> !row.phone.matches("^1[3-9][0-9]{9}$"))) {
                throw new IllegalStateException("phone check violated");
            }
        }
    }

    private static final class Row {
        UUID id;
        UUID ownerId;
        long sequence;
        String recipientName;
        String phone;
        String provinceCode;
        String provinceName;
        String cityCode;
        String cityName;
        String detail;
        boolean isDefault;
        String status = "ACTIVE";
        int version;
        Instant createdAt;
        Instant updatedAt;

        boolean active() {
            return "ACTIVE".equals(status);
        }

        void set(
                String recipientName,
                String phone,
                String provinceCode,
                String provinceName,
                String cityCode,
                String cityName,
                String detail,
                boolean isDefault
        ) {
            this.recipientName = recipientName;
            this.phone = phone;
            this.provinceCode = provinceCode;
            this.provinceName = provinceName;
            this.cityCode = cityCode;
            this.cityName = cityName;
            this.detail = detail;
            this.isDefault = isDefault;
        }

        void touch(Instant at) {
            version++;
            updatedAt = at;
        }

        AddressRow toRow() {
            return new AddressRow(
                    id, recipientName, phone, provinceCode, provinceName, cityCode, cityName, detail, isDefault);
        }
    }
}
