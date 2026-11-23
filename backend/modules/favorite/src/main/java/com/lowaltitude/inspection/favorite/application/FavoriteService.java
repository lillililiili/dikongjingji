package com.lowaltitude.inspection.favorite.application;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.lowaltitude.inspection.favorite.application.FavoriteException.Error;
import com.lowaltitude.inspection.favorite.infrastructure.persistence.FavoriteMapper;
import com.lowaltitude.inspection.favorite.infrastructure.persistence.FavoriteMapper.ItemRow;
import com.lowaltitude.inspection.favorite.infrastructure.persistence.FavoriteMapper.TypeCountRow;
import org.springframework.transaction.annotation.Transactional;

/**
 * 收藏（计划 08 S5-6，契约 contracts/openapi-favorite.yaml）。
 *
 * <ul>
 *   <li>每个账号对同一对象最多一条。收藏、取消都是幂等的。</li>
 *   <li>收藏时按当前城市读一次对象，读不到不能收藏；读到时记下标题。</li>
 *   <li>取消只记时间；10 分钟内再收藏是撤销，恢复原来那条，不再读对象（已下架的也能撤销回来）。</li>
 *   <li>列表按分组分页，每一条按当前城市读一次；读不到的只给收藏时的标题，标为不可用。</li>
 *   <li>一个账号最多 500 条。</li>
 * </ul>
 */
public class FavoriteService {

    static final int MAX_FAVORITES = 500;
    static final int MAX_PAGE_SIZE = 20;
    static final int DEFAULT_PAGE_SIZE = 10;
    static final int MAX_TITLE = 160;
    static final Duration UNDO_WINDOW = Duration.ofMinutes(10);
    private static final String CITY_CODE_PATTERN = "^[A-Z0-9]{4,16}$";

    private final FavoriteMapper mapper;
    private final Map<FavoriteTargetType, FavoritePorts.TargetReader> readers;
    private final Clock clock;

    public FavoriteService(FavoriteMapper mapper, List<FavoritePorts.TargetReader> readers) {
        this(mapper, readers, Clock.systemUTC());
    }

    FavoriteService(FavoriteMapper mapper, List<FavoritePorts.TargetReader> readers, Clock clock) {
        this.mapper = mapper;
        this.readers = new EnumMap<>(FavoriteTargetType.class);
        for (FavoritePorts.TargetReader reader : readers) {
            if (this.readers.put(reader.type(), reader) != null) {
                throw new IllegalStateException("two favorite readers for " + reader.type());
            }
        }
        for (FavoriteTargetType type : FavoriteTargetType.values()) {
            if (!this.readers.containsKey(type)) {
                throw new IllegalStateException("no favorite reader for " + type);
            }
        }
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public FavoriteStateView state(UUID accountId, String typeCode, UUID targetId) {
        FavoriteTargetType type = targetType(typeCode);
        requireIds(accountId, targetId);
        return stateOf(type, targetId, mapper.findItem(accountId, type.name(), targetId));
    }

    @Transactional
    public FavoritePut add(UUID accountId, String typeCode, UUID targetId, String cityCode) {
        FavoriteTargetType type = targetType(typeCode);
        requireIds(accountId, targetId);
        String city = requireCity(cityCode);
        mapper.lockAccount(accountId);
        ItemRow existing = mapper.findItem(accountId, type.name(), targetId);
        OffsetDateTime now = now();
        if (existing != null && existing.removedAt() == null) {
            return new FavoritePut(stateOf(type, targetId, existing), false);
        }
        if (existing != null && !existing.removedAt().plus(UNDO_WINDOW).isBefore(now)) {
            mapper.restore(accountId, type.name(), targetId, now);
            return new FavoritePut(stateOf(type, targetId, mapper.findItem(accountId, type.name(), targetId)), false);
        }
        FavoritePorts.Target target = readers.get(type).find(targetId, city)
                .orElseThrow(() -> new FavoriteException(Error.TARGET_UNAVAILABLE));
        if (mapper.countActive(accountId) >= MAX_FAVORITES) {
            throw new FavoriteException(Error.LIMIT_REACHED);
        }
        String title = clip(target.title());
        int written = existing == null
                ? mapper.insertItem(accountId, type.name(), targetId, title, now)
                : mapper.refavorite(accountId, type.name(), targetId, title, now);
        if (written != 1) {
            throw new IllegalStateException("favorite was not written");
        }
        return new FavoritePut(stateOf(type, targetId, mapper.findItem(accountId, type.name(), targetId)), true);
    }

    @Transactional
    public FavoriteStateView remove(UUID accountId, String typeCode, UUID targetId) {
        FavoriteTargetType type = targetType(typeCode);
        requireIds(accountId, targetId);
        mapper.remove(accountId, type.name(), targetId, now());
        return new FavoriteStateView(type, targetId, false, null);
    }

    @Transactional(readOnly = true)
    public FavoritePageView list(UUID accountId, String groupCode, String cityCode, int page, int size) {
        FavoriteGroup group = parse(FavoriteGroup.class, groupCode, "group");
        if (accountId == null) {
            throw new IllegalArgumentException("account is required");
        }
        String city = requireCity(cityCode);
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page is invalid");
        }
        Map<FavoriteGroup, Long> counts = new EnumMap<>(FavoriteGroup.class);
        for (FavoriteGroup each : FavoriteGroup.values()) {
            counts.put(each, 0L);
        }
        for (TypeCountRow row : mapper.countByType(accountId)) {
            FavoriteGroup owner = FavoriteTargetType.valueOf(row.targetType()).group();
            counts.merge(owner, row.itemCount(), Long::sum);
        }
        List<FavoriteTargetType> types = java.util.Arrays.stream(FavoriteTargetType.values())
                .filter(type -> type.group() == group)
                .toList();
        String first = types.get(0).name();
        String second = types.get(types.size() - 1).name();
        long total = counts.get(group);
        List<FavoriteItemView> items = mapper.findPage(accountId, first, second, size, (long) (page - 1) * size)
                .stream()
                .map(row -> item(row, city))
                .toList();
        return new FavoritePageView(page, size, total, (long) page * size < total,
                new FavoriteCountsView(counts.get(FavoriteGroup.GOODS), counts.get(FavoriteGroup.SERVICE),
                        counts.get(FavoriteGroup.EVENT), counts.get(FavoriteGroup.CONTENT)),
                items);
    }

    private FavoriteItemView item(ItemRow row, String city) {
        FavoriteTargetType type = FavoriteTargetType.valueOf(row.targetType());
        return readers.get(type).find(row.targetId(), city)
                .map(target -> new FavoriteItemView(type, row.targetId(), row.favoritedAt(), true,
                        clip(target.title()), blankToNull(target.meta()), blankToNull(target.tag()),
                        target.price() == null ? null : new FavoritePriceView(target.price().mode(),
                                target.price().amountMinor(), target.price().from(), blankToNull(target.price().unitLabel())),
                        blankToNull(target.coverUrl()),
                        type == FavoriteTargetType.PRODUCT ? blankToNull(target.productCategory()) : null))
                .orElseGet(() -> new FavoriteItemView(type, row.targetId(), row.favoritedAt(), false, row.title(),
                        null, null, null, null, null));
    }

    private static FavoriteStateView stateOf(FavoriteTargetType type, UUID targetId, ItemRow row) {
        boolean favorited = row != null && row.removedAt() == null;
        return new FavoriteStateView(type, targetId, favorited, favorited ? row.favoritedAt() : null);
    }

    private static FavoriteTargetType targetType(String code) {
        return parse(FavoriteTargetType.class, code, "target type");
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String code, String what) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException(what + " is required");
        }
        try {
            return Enum.valueOf(type, code.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(what + " is invalid");
        }
    }

    private static void requireIds(UUID accountId, UUID targetId) {
        if (accountId == null || targetId == null) {
            throw new IllegalArgumentException("account and target are required");
        }
    }

    private static String requireCity(String cityCode) {
        String city = cityCode == null ? null : cityCode.trim();
        if (city == null || !city.matches(CITY_CODE_PATTERN)) {
            throw new IllegalArgumentException("city code is invalid");
        }
        return city;
    }

    private static String clip(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalStateException("favorite target has no title");
        }
        String value = title.trim();
        return value.codePointCount(0, value.length()) <= MAX_TITLE
                ? value
                : value.substring(0, value.offsetByCodePoints(0, MAX_TITLE));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
