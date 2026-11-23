package com.lowaltitude.inspection.favorite.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.favorite.application.FavoriteException.Error;
import com.lowaltitude.inspection.favorite.infrastructure.persistence.FavoriteMapper;
import org.junit.jupiter.api.Test;

/**
 * 收藏的规则测试（计划 08 S5-6）。mapper 换成内存实现（按 FavoriteMapper 的 SQL 条件和 V001 的约束写），各模块的读取换成替身，
 * 时钟可以往前拨。SQL 文本由 FavoriteMapperContractTest 检查。
 */
class FavoriteServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-22T02:00:00Z");
    private static final UUID CUSTOMER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = UUID.fromString("11111111-1111-1111-1111-222222222222");
    private static final UUID KIT = UUID.fromString("a1000000-0000-4000-8000-000000000001");
    private static final UUID ARM = UUID.fromString("a1000000-0000-4000-8000-000000000002");
    private static final UUID PARK = UUID.fromString("b1000000-0000-4000-8000-000000000001");
    private static final UUID AERIAL = UUID.fromString("b2000000-0000-4000-8000-000000000001");
    private static final UUID CONTEST = UUID.fromString("c1000000-0000-4000-8000-000000000001");
    private static final UUID HALL = UUID.fromString("c2000000-0000-4000-8000-000000000001");
    private static final UUID CASE = UUID.fromString("d1000000-0000-4000-8000-000000000001");
    private static final String CITY = "DONGYING";

    private final InMemoryMapper mapper = new InMemoryMapper();
    /** 各类对象在这个城市读得到的：类型 + ID → 内容。不在里面的读不到。 */
    private final Map<String, FavoritePorts.Target> targets = new HashMap<>();
    private final List<String> reads = new ArrayList<>();
    private Instant now = NOW;
    private final FavoriteService service = new FavoriteService(mapper, readers(), new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    });

    {
        targets.put(key(FavoriteTargetType.PRODUCT, KIT), new FavoritePorts.Target("编程机器人探索套装", "3 家在售", null,
                FavoritePorts.Price.amount(129_900, true, null), "https://img.example.com/kit.jpg", "ROBOT"));
        targets.put(key(FavoriteTargetType.PRODUCT, ARM), new FavoritePorts.Target("桌面六轴机械臂 教学版", "2 家在售", null,
                FavoritePorts.Price.amount(980_000, true, null), null, "ROBOT"));
        targets.put(key(FavoriteTargetType.INSPECTION_SERVICE, PARK), new FavoritePorts.Target("园区安防巡检", "鹰眼智能巡检",
                null, FavoritePorts.Price.quote(), null, null));
        targets.put(key(FavoriteTargetType.PRO_SERVICE, AERIAL), new FavoritePorts.Target("园区航拍", "示例航拍服务商", null,
                FavoritePorts.Price.amount(80_000, true, "/天"), null, null));
        targets.put(key(FavoriteTargetType.ACTIVITY, CONTEST), new FavoritePorts.Target("青少年无人机编程挑战赛（东营站）",
                "11/15", null, FavoritePorts.Price.amount(12_000, false, "/人"), null, null));
        targets.put(key(FavoriteTargetType.VENUE, HALL), new FavoritePorts.Target("东营低空体验馆", "示例运营机构", "场馆",
                FavoritePorts.Price.free(), null, null));
        targets.put(key(FavoriteTargetType.CONTENT, CASE), new FavoritePorts.Target("一台机器狗如何完成园区夜间巡逻",
                "示例机器人商家", "案例", null, null, null));
    }

    @Test
    void addingIsIdempotentAndRemembersTheTitle() {
        FavoritePut first = service.add(CUSTOMER, "PRODUCT", KIT, CITY);
        now = NOW.plusSeconds(30);
        FavoritePut again = service.add(CUSTOMER, "product", KIT, CITY);

        assertThat(first.created()).isTrue();
        assertThat(first.state()).isEqualTo(new FavoriteStateView(FavoriteTargetType.PRODUCT, KIT, true, at(NOW)));
        assertThat(again.created()).isFalse();
        assertThat(again.state().favoritedAt()).isEqualTo(at(NOW));
        assertThat(mapper.rows).hasSize(1);
        assertThat(mapper.rows.get(0).title).isEqualTo("编程机器人探索套装");
        // 第二次不再读对象。
        assertThat(reads).containsExactly("PRODUCT " + KIT + " " + CITY);
        assertThat(service.state(CUSTOMER, "PRODUCT", KIT).favorited()).isTrue();
        assertThat(service.state(OTHER, "PRODUCT", KIT).favorited()).isFalse();
    }

    @Test
    void anUnavailableTargetCannotBeFavorited() {
        assertThatThrownBy(() -> service.add(CUSTOMER, "PRODUCT", UUID.randomUUID(), CITY))
                .isInstanceOfSatisfying(FavoriteException.class,
                        failure -> assertThat(failure.error()).isEqualTo(Error.TARGET_UNAVAILABLE));
        assertThat(mapper.rows).isEmpty();
    }

    @Test
    void removingIsIdempotentAndHidesTheFavorite() {
        service.add(CUSTOMER, "ACTIVITY", CONTEST, CITY);

        FavoriteStateView removed = service.remove(CUSTOMER, "ACTIVITY", CONTEST);
        FavoriteStateView again = service.remove(CUSTOMER, "ACTIVITY", CONTEST);
        FavoriteStateView never = service.remove(CUSTOMER, "VENUE", HALL);

        assertThat(removed).isEqualTo(new FavoriteStateView(FavoriteTargetType.ACTIVITY, CONTEST, false, null));
        assertThat(again.favorited()).isFalse();
        assertThat(never.favorited()).isFalse();
        assertThat(service.state(CUSTOMER, "ACTIVITY", CONTEST)).isEqualTo(removed);
        assertThat(service.list(CUSTOMER, "EVENT", CITY, 1, 10).items()).isEmpty();
    }

    @Test
    void addingAgainWithinTenMinutesUndoesTheRemovalEvenWhenTheTargetIsGone() {
        service.add(CUSTOMER, "PRODUCT", KIT, CITY);
        now = NOW.plus(Duration.ofHours(1));
        service.add(CUSTOMER, "PRODUCT", ARM, CITY);
        now = NOW.plus(Duration.ofHours(2));
        service.remove(CUSTOMER, "PRODUCT", KIT);
        targets.remove(key(FavoriteTargetType.PRODUCT, KIT));
        reads.clear();
        now = NOW.plus(Duration.ofHours(2)).plus(FavoriteService.UNDO_WINDOW);

        FavoritePut undone = service.add(CUSTOMER, "PRODUCT", KIT, CITY);

        assertThat(undone.created()).isFalse();
        assertThat(undone.state().favoritedAt()).isEqualTo(at(NOW));
        assertThat(reads).isEmpty();
        // 原来的位置：比 ARM 早收藏，排在它后面；已下架的显示收藏时的标题。
        assertThat(service.list(CUSTOMER, "GOODS", CITY, 1, 10).items())
                .extracting(FavoriteItemView::targetId, FavoriteItemView::available, FavoriteItemView::title)
                .containsExactly(tuple(ARM, true, "桌面六轴机械臂 教学版"), tuple(KIT, false, "编程机器人探索套装"));
    }

    @Test
    void addingAgainAfterTheUndoWindowIsAFreshFavoriteThatReadsTheTarget() {
        service.add(CUSTOMER, "PRODUCT", KIT, CITY);
        now = NOW.plus(Duration.ofHours(1));
        service.remove(CUSTOMER, "PRODUCT", KIT);
        Instant later = NOW.plus(Duration.ofHours(1)).plus(FavoriteService.UNDO_WINDOW).plusSeconds(1);
        now = later;
        targets.put(key(FavoriteTargetType.PRODUCT, KIT), new FavoritePorts.Target("编程机器人探索套装 2 代", null, null,
                null, null, "ROBOT"));

        FavoritePut again = service.add(CUSTOMER, "PRODUCT", KIT, CITY);

        assertThat(again.created()).isTrue();
        assertThat(again.state().favoritedAt()).isEqualTo(at(later));
        assertThat(mapper.rows).hasSize(1);
        assertThat(mapper.rows.get(0).title).isEqualTo("编程机器人探索套装 2 代");

        service.remove(CUSTOMER, "PRODUCT", KIT);
        now = later.plus(FavoriteService.UNDO_WINDOW).plusSeconds(1);
        targets.remove(key(FavoriteTargetType.PRODUCT, KIT));
        assertThatThrownBy(() -> service.add(CUSTOMER, "PRODUCT", KIT, CITY))
                .isInstanceOfSatisfying(FavoriteException.class,
                        failure -> assertThat(failure.error()).isEqualTo(Error.TARGET_UNAVAILABLE));
    }

    @Test
    void theListIsOneGroupNewestFirstWithTheCountsOfAllGroups() {
        service.add(CUSTOMER, "INSPECTION_SERVICE", PARK, CITY);
        now = NOW.plusSeconds(60);
        service.add(CUSTOMER, "PRO_SERVICE", AERIAL, CITY);
        now = NOW.plusSeconds(120);
        service.add(CUSTOMER, "ACTIVITY", CONTEST, CITY);
        service.add(CUSTOMER, "VENUE", HALL, CITY);
        service.add(CUSTOMER, "CONTENT", CASE, CITY);
        service.add(CUSTOMER, "PRODUCT", KIT, CITY);
        service.add(OTHER, "PRODUCT", ARM, CITY);

        FavoritePageView services = service.list(CUSTOMER, "service", CITY, 1, 10);

        assertThat(services.counts()).isEqualTo(new FavoriteCountsView(1, 2, 2, 1));
        assertThat(services.total()).isEqualTo(2);
        assertThat(services.hasMore()).isFalse();
        assertThat(services.items()).extracting(FavoriteItemView::targetType, FavoriteItemView::title,
                        FavoriteItemView::meta)
                .containsExactly(tuple(FavoriteTargetType.PRO_SERVICE, "园区航拍", "示例航拍服务商"),
                        tuple(FavoriteTargetType.INSPECTION_SERVICE, "园区安防巡检", "鹰眼智能巡检"));
        assertThat(services.items().get(0).price())
                .isEqualTo(new FavoritePriceView(FavoritePorts.PriceMode.AMOUNT, 80_000L, true, "/天"));
        assertThat(services.items().get(1).price())
                .isEqualTo(new FavoritePriceView(FavoritePorts.PriceMode.QUOTE, null, false, null));

        FavoritePageView goods = service.list(CUSTOMER, "GOODS", CITY, 1, 10);
        assertThat(goods.items()).singleElement().satisfies(item -> {
            assertThat(item.available()).isTrue();
            assertThat(item.coverUrl()).isEqualTo("https://img.example.com/kit.jpg");
            assertThat(item.productCategory()).isEqualTo("ROBOT");
        });
        assertThat(service.list(CUSTOMER, "CONTENT", CITY, 1, 10).items()).singleElement()
                .satisfies(item -> {
                    assertThat(item.tag()).isEqualTo("案例");
                    assertThat(item.price()).isNull();
                    assertThat(item.productCategory()).isNull();
                });
    }

    @Test
    void theListPagesAndReadsEachTargetForTheCurrentCity() {
        for (int index = 0; index < 3; index++) {
            UUID product = UUID.fromString("a1000000-0000-4000-8000-00000000010" + index);
            targets.put(key(FavoriteTargetType.PRODUCT, product), new FavoritePorts.Target("商品 " + index, null, null,
                    null, null, "DRONE"));
            now = NOW.plusSeconds(index);
            service.add(CUSTOMER, "PRODUCT", product, CITY);
        }
        reads.clear();

        FavoritePageView first = service.list(CUSTOMER, "GOODS", "JINAN", 1, 2);
        FavoritePageView second = service.list(CUSTOMER, "GOODS", "JINAN", 2, 2);

        assertThat(first.items()).extracting(FavoriteItemView::title).containsExactly("商品 2", "商品 1");
        assertThat(first.hasMore()).isTrue();
        assertThat(second.items()).extracting(FavoriteItemView::title).containsExactly("商品 0");
        assertThat(second.hasMore()).isFalse();
        assertThat(reads).hasSize(3).allSatisfy(read -> assertThat(read).endsWith(" JINAN"));
    }

    @Test
    void anAccountKeepsAtMostFiveHundredFavorites() {
        for (int index = 0; index < FavoriteService.MAX_FAVORITES; index++) {
            mapper.rows.add(new Row(CUSTOMER, "CONTENT", UUID.randomUUID(), "旧内容", at(NOW), null));
        }

        assertThatThrownBy(() -> service.add(CUSTOMER, "PRODUCT", KIT, CITY))
                .isInstanceOfSatisfying(FavoriteException.class,
                        failure -> assertThat(failure.error()).isEqualTo(Error.LIMIT_REACHED));
        // 已经收藏的再收藏一次不算新的一条。
        mapper.rows.add(new Row(CUSTOMER, "PRODUCT", ARM, "桌面六轴机械臂 教学版", at(NOW), null));
        assertThat(service.add(CUSTOMER, "PRODUCT", ARM, CITY).created()).isFalse();
    }

    @Test
    void longTitlesAreClippedToTheColumn() {
        targets.put(key(FavoriteTargetType.CONTENT, CASE), new FavoritePorts.Target("长".repeat(200), null, null, null,
                null, null));

        service.add(CUSTOMER, "CONTENT", CASE, CITY);

        assertThat(mapper.rows.get(0).title).hasSize(FavoriteService.MAX_TITLE);
    }

    @Test
    void badCodesAndCitiesAreRejected() {
        assertThatThrownBy(() -> service.add(CUSTOMER, "COURSE", KIT, CITY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.add(CUSTOMER, "PRODUCT", KIT, "dongying"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.state(CUSTOMER, null, KIT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list(CUSTOMER, "ALL", CITY, 1, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list(CUSTOMER, "GOODS", CITY, 0, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list(CUSTOMER, "GOODS", CITY, 1, FavoriteService.MAX_PAGE_SIZE + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyTargetTypeNeedsExactlyOneReader() {
        List<FavoritePorts.TargetReader> missing = new ArrayList<>(readers());
        missing.remove(0);
        assertThatThrownBy(() -> new FavoriteService(mapper, missing)).isInstanceOf(IllegalStateException.class);
        List<FavoritePorts.TargetReader> doubled = new ArrayList<>(readers());
        doubled.add(readers().get(0));
        assertThatThrownBy(() -> new FavoriteService(mapper, doubled)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theGroupsCoverEveryTypeOnceAndHoldAtMostTwo() {
        Map<FavoriteGroup, Set<FavoriteTargetType>> grouped = new HashMap<>();
        for (FavoriteTargetType type : FavoriteTargetType.values()) {
            grouped.computeIfAbsent(type.group(), group -> new HashSet<>()).add(type);
        }
        assertThat(grouped.keySet()).containsExactlyInAnyOrder(FavoriteGroup.values());
        // findPage 用两个参数表示一个分组里的类型。
        assertThat(grouped.values()).allSatisfy(types -> assertThat(types).hasSizeBetween(1, 2));
    }

    // ---- 替身 ----

    private List<FavoritePorts.TargetReader> readers() {
        return Arrays.stream(FavoriteTargetType.values()).map(type -> (FavoritePorts.TargetReader) new FavoritePorts.TargetReader() {
            @Override
            public FavoriteTargetType type() {
                return type;
            }

            @Override
            public Optional<FavoritePorts.Target> find(UUID targetId, String cityCode) {
                reads.add(type + " " + targetId + " " + cityCode);
                return Optional.ofNullable(targets.get(key(type, targetId)));
            }
        }).toList();
    }

    private static String key(FavoriteTargetType type, UUID id) {
        return type + ":" + id;
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static final class Row {
        final UUID accountId;
        final String targetType;
        final UUID targetId;
        String title;
        OffsetDateTime favoritedAt;
        OffsetDateTime removedAt;
        final long id;
        private static long sequence;

        Row(UUID accountId, String targetType, UUID targetId, String title, OffsetDateTime favoritedAt,
            OffsetDateTime removedAt) {
            this.accountId = accountId;
            this.targetType = targetType;
            this.targetId = targetId;
            this.title = title;
            this.favoritedAt = favoritedAt;
            this.removedAt = removedAt;
            this.id = ++sequence;
        }

        FavoriteMapper.ItemRow view() {
            return new FavoriteMapper.ItemRow(targetType, targetId, title, favoritedAt, removedAt);
        }

        boolean is(UUID account, String type, UUID target) {
            return accountId.equals(account) && targetType.equals(type) && targetId.equals(target);
        }
    }

    /** 按 FavoriteMapper 的 SQL 条件写的内存实现。 */
    private static final class InMemoryMapper implements FavoriteMapper {

        final List<Row> rows = new ArrayList<>();

        private Optional<Row> find(UUID accountId, String type, UUID targetId) {
            return rows.stream().filter(row -> row.is(accountId, type, targetId)).findFirst();
        }

        @Override
        public Integer lockAccount(UUID accountId) {
            return 1;
        }

        @Override
        public ItemRow findItem(UUID accountId, String targetType, UUID targetId) {
            return find(accountId, targetType, targetId).map(Row::view).orElse(null);
        }

        @Override
        public int insertItem(UUID accountId, String targetType, UUID targetId, String title, OffsetDateTime at) {
            if (find(accountId, targetType, targetId).isPresent()) {
                return 0;
            }
            rows.add(new Row(accountId, targetType, targetId, title, at, null));
            return 1;
        }

        @Override
        public int refavorite(UUID accountId, String targetType, UUID targetId, String title, OffsetDateTime at) {
            return find(accountId, targetType, targetId).filter(row -> row.removedAt != null).map(row -> {
                row.removedAt = null;
                row.title = title;
                row.favoritedAt = at;
                return 1;
            }).orElse(0);
        }

        @Override
        public int restore(UUID accountId, String targetType, UUID targetId, OffsetDateTime at) {
            return find(accountId, targetType, targetId).filter(row -> row.removedAt != null).map(row -> {
                row.removedAt = null;
                return 1;
            }).orElse(0);
        }

        @Override
        public int remove(UUID accountId, String targetType, UUID targetId, OffsetDateTime at) {
            return find(accountId, targetType, targetId).filter(row -> row.removedAt == null).map(row -> {
                row.removedAt = at;
                return 1;
            }).orElse(0);
        }

        @Override
        public long countActive(UUID accountId) {
            return rows.stream().filter(row -> row.accountId.equals(accountId) && row.removedAt == null).count();
        }

        @Override
        public List<TypeCountRow> countByType(UUID accountId) {
            Map<String, Long> counts = new HashMap<>();
            rows.stream().filter(row -> row.accountId.equals(accountId) && row.removedAt == null)
                    .forEach(row -> counts.merge(row.targetType, 1L, Long::sum));
            return counts.entrySet().stream().map(entry -> new TypeCountRow(entry.getKey(), entry.getValue())).toList();
        }

        @Override
        public List<ItemRow> findPage(UUID accountId, String firstType, String secondType, int limit, long offset) {
            return rows.stream()
                    .filter(row -> row.accountId.equals(accountId) && row.removedAt == null)
                    .filter(row -> row.targetType.equals(firstType) || row.targetType.equals(secondType))
                    .sorted(Comparator.comparing((Row row) -> row.favoritedAt).thenComparing(row -> row.id).reversed())
                    .skip(offset)
                    .limit(limit)
                    .map(Row::view)
                    .toList();
        }
    }
}
