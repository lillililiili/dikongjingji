package com.lowaltitude.inspection.application.city;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.transaction.annotation.Transactional;

/**
 * 运营端“城市开通”（S3-1，契约 contracts/openapi-ops-city.yaml 1.0.0）：列出可选城市和开通状态，按版本号改状态，
 * 每次改都留一条记录（谁、什么时候、从什么改成什么、为什么）。只有平台运营能调，角色由控制器检查。
 */
public class CityOperationsService {

    public static final int MAX_PAGE_SIZE = 50;
    public static final int MAX_NOTE = 200;
    public static final int MAX_KEYWORD = 20;
    public static final int CHANGE_HISTORY_LIMIT = 50;
    private static final Pattern CITY_CODE = Pattern.compile("^[0-9]{6}$");

    private final Store store;
    private final Clock clock;

    public CityOperationsService(Store store) {
        this(store, Clock.systemUTC());
    }

    CityOperationsService(Store store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public OpsCityPageView cities(String keyword, String serviceStatus, String provinceCode, int page, int size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page is invalid");
        }
        String filterKeyword = keyword(keyword);
        String filterStatus = serviceStatus == null || serviceStatus.isBlank() ? null : status(serviceStatus).name();
        String filterProvince = provinceCode == null || provinceCode.isBlank() ? null : cityCode(provinceCode);
        long total = store.count(filterKeyword, filterStatus, filterProvince);
        List<OpsCityView> items = store.page(filterKeyword, filterStatus, filterProvince, size,
                        Math.multiplyExact(page - 1, size)).stream()
                .map(CityOperationsService::view)
                .toList();
        return new OpsCityPageView(page, size, total, (long) page * size < total, items);
    }

    /** 可选城市的名称（机构入驻校验城市用）；编码不对、不存在或不可选时为空。 */
    @Transactional(readOnly = true)
    public Optional<String> cityName(String cityCode) {
        if (cityCode == null || !CITY_CODE.matcher(cityCode).matches()) {
            return Optional.empty();
        }
        return store.find(cityCode).map(Row::cityName);
    }

    @Transactional(readOnly = true)
    public List<OpsCityStatusChangeView> changes(String cityCode) {
        String code = cityCode(cityCode);
        if (store.find(code).isEmpty()) {
            throw new CityOperationException(CityOperationException.Error.NOT_FOUND);
        }
        return store.changes(code, CHANGE_HISTORY_LIMIT).stream()
                .map(row -> new OpsCityStatusChangeView(row.id(), CityServiceStatus.valueOf(row.fromStatus()),
                        CityServiceStatus.valueOf(row.toStatus()), row.note(), row.operatorName(), row.changedAt()))
                .toList();
    }

    /**
     * 改开通状态。version 是页面读到的版本，和当前不一致时拒绝（别人刚改过）；状态没变也拒绝，免得留一条空记录。
     * 说明必填（1–200 字），和操作人一起写进改动记录。
     */
    @Transactional
    public OpsCityView changeStatus(String cityCode, String serviceStatus, String note, Integer version,
                                    UUID operatorId, String operatorName) {
        String code = cityCode(cityCode);
        CityServiceStatus target = status(serviceStatus);
        String reason = note(note);
        if (version == null || version < 0) {
            throw new IllegalArgumentException("version is required");
        }
        if (operatorId == null || operatorName == null || operatorName.isBlank()) {
            throw new IllegalArgumentException("operator is required");
        }
        Row current = store.find(code)
                .orElseThrow(() -> new CityOperationException(CityOperationException.Error.NOT_FOUND));
        if (current.version() != version) {
            throw new CityOperationException(CityOperationException.Error.VERSION_CONFLICT);
        }
        if (CityServiceStatus.valueOf(current.serviceStatus()) == target) {
            throw new CityOperationException(CityOperationException.Error.STATUS_UNCHANGED);
        }
        OffsetDateTime at = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (store.updateStatus(code, target.name(), version, at) != 1) {
            throw new CityOperationException(CityOperationException.Error.VERSION_CONFLICT);
        }
        store.insertChange(code, current.serviceStatus(), target.name(), reason, operatorId,
                clip(operatorName.strip(), 80), at);
        return view(store.find(code).orElseThrow());
    }

    private static OpsCityView view(Row row) {
        return new OpsCityView(row.cityCode(), row.cityName(), row.provinceCode(), row.provinceName(),
                CityServiceStatus.valueOf(row.serviceStatus()), row.serviceStatusChangedAt(), row.version());
    }

    private static String cityCode(String code) {
        if (code == null || !CITY_CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("city code is invalid");
        }
        return code;
    }

    private static CityServiceStatus status(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("service status is required");
        }
        try {
            return CityServiceStatus.valueOf(code.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("service status is invalid", unknown);
        }
    }

    /** 关键词去掉 LIKE 通配符，最多 20 个字；空白当作不筛选。 */
    static String keyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String cleaned = keyword.replaceAll("[%_\\\\]", "").strip();
        if (cleaned.isEmpty()) {
            return null;
        }
        if (cleaned.codePointCount(0, cleaned.length()) > MAX_KEYWORD) {
            throw new IllegalArgumentException("keyword is too long");
        }
        return cleaned;
    }

    private static String note(String note) {
        String trimmed = note == null ? "" : note.strip();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > MAX_NOTE
                || trimmed.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("note is invalid");
        }
        return trimmed;
    }

    private static String clip(String value, int max) {
        return value.codePointCount(0, value.length()) <= max
                ? value
                : value.substring(0, value.offsetByCodePoints(0, max));
    }

    /** 城市目录的读写；实现见 PostgresCityOperationsStore。 */
    public interface Store {

        long count(String keyword, String serviceStatus, String provinceCode);

        List<Row> page(String keyword, String serviceStatus, String provinceCode, int limit, int offset);

        Optional<Row> find(String cityCode);

        int updateStatus(String cityCode, String serviceStatus, int version, OffsetDateTime at);

        void insertChange(String cityCode, String fromStatus, String toStatus, String note, UUID operatorId,
                          String operatorName, OffsetDateTime at);

        List<ChangeRow> changes(String cityCode, int limit);
    }

    public record Row(String cityCode, String cityName, String provinceCode, String provinceName,
                      String serviceStatus, OffsetDateTime serviceStatusChangedAt, int version) {
    }

    public record ChangeRow(UUID id, String fromStatus, String toStatus, String note, String operatorName,
                            OffsetDateTime changedAt) {
    }

    // ---- 对外的形状（契约 openapi-ops-city.yaml） ----

    public record OpsCityView(String cityCode, String cityName, String provinceCode, String provinceName,
                              CityServiceStatus serviceStatus, OffsetDateTime serviceStatusChangedAt, int version) {
    }

    public record OpsCityPageView(int page, int size, long total, boolean hasMore, List<OpsCityView> items) {
    }

    public record OpsCityStatusChangeView(UUID changeId, CityServiceStatus fromStatus, CityServiceStatus toStatus,
                                          String note, String operatorName, OffsetDateTime changedAt) {
    }
}
