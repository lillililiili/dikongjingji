package com.lowaltitude.inspection.application.city;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class CityDirectoryReadService {

    private static final Pattern CITY_CODE = Pattern.compile("^[0-9]{6}$");
    private static final Comparator<ProvinceView> PROVINCE_ORDER = Comparator
            .comparing(ProvinceView::pinyin)
            .thenComparing(ProvinceView::code);
    private static final Comparator<StoredCity> CITY_ORDER = Comparator
            .comparing(StoredCity::cityPinyin)
            .thenComparing(StoredCity::cityCode);

    private final CityDirectoryStore store;

    public CityDirectoryReadService(CityDirectoryStore store) {
        this.store = store;
    }

    public DirectoryView directory() {
        List<StoredCity> rows = store.findActiveCities();
        List<CityView> hot = rows.stream()
                .sorted(Comparator.comparingLong(StoredCity::searchCount).reversed()
                        .thenComparing(StoredCity::cityPinyin)
                        .thenComparing(StoredCity::cityCode))
                .limit(6)
                .map(CityDirectoryReadService::toCity)
                .toList();
        Map<String, List<StoredCity>> grouped = rows.stream()
                .collect(Collectors.groupingBy(
                        StoredCity::provinceCode,
                        LinkedHashMap::new,
                        Collectors.toList()));
        List<ProvinceView> provinces = grouped.values().stream()
                .map(CityDirectoryReadService::toProvince)
                .sorted(PROVINCE_ORDER)
                .toList();
        // S3-1：App 城市面板按开通状态分两组（画板 CitySheet），同一组按城市拼音排；未开通的城市只在搜索里出现。
        List<CityView> open = byServiceStatus(rows, CityServiceStatus.OPEN);
        List<CityView> preparing = byServiceStatus(rows, CityServiceStatus.PREPARING);
        return new DirectoryView(hot, provinces, open, preparing);
    }

    public boolean recordSearchSelection(String cityCode) {
        if (cityCode == null || !CITY_CODE.matcher(cityCode).matches()) {
            throw new IllegalArgumentException("city code is invalid");
        }
        return store.incrementSearchCount(cityCode) == 1;
    }

    private static List<CityView> byServiceStatus(List<StoredCity> rows, CityServiceStatus status) {
        return rows.stream()
                .filter(city -> city.serviceStatus() == status)
                .sorted(CITY_ORDER)
                .map(CityDirectoryReadService::toCity)
                .toList();
    }

    private static ProvinceView toProvince(List<StoredCity> cities) {
        StoredCity province = cities.getFirst();
        return new ProvinceView(
                province.provinceCode(),
                province.provinceName(),
                province.provincePinyin(),
                province.provinceInitials(),
                cities.stream().sorted(CITY_ORDER).map(CityDirectoryReadService::toCity).toList());
    }

    private static CityView toCity(StoredCity city) {
        return new CityView(
                city.cityCode(),
                city.cityName(),
                city.cityPinyin(),
                city.cityInitials(),
                city.provinceCode(),
                city.provinceName(),
                city.serviceStatus());
    }

    public interface CityDirectoryStore {
        List<StoredCity> findActiveCities();

        int incrementSearchCount(String cityCode);
    }

    public record StoredCity(
            String cityCode,
            String cityName,
            String cityPinyin,
            String cityInitials,
            String provinceCode,
            String provinceName,
            String provincePinyin,
            String provinceInitials,
            long searchCount,
            CityServiceStatus serviceStatus) {

        public StoredCity {
            serviceStatus = serviceStatus == null ? CityServiceStatus.CLOSED : serviceStatus;
        }

        /** 没有开通状态的旧写法（测试用）：当作未开通。 */
        public StoredCity(String cityCode, String cityName, String cityPinyin, String cityInitials,
                          String provinceCode, String provinceName, String provincePinyin, String provinceInitials,
                          long searchCount) {
            this(cityCode, cityName, cityPinyin, cityInitials, provinceCode, provinceName, provincePinyin,
                    provinceInitials, searchCount, CityServiceStatus.CLOSED);
        }
    }

    /**
     * hotCities、provinces 是 1.0.0 就有的（全部可选城市）；openCities、preparingCities 是 1.1.0 加的，
     * App 城市面板只显示这两组。
     */
    public record DirectoryView(
            List<CityView> hotCities,
            List<ProvinceView> provinces,
            List<CityView> openCities,
            List<CityView> preparingCities) {

        /** 只有全部城市、不分开通状态的旧写法（别的模块的测试用）。 */
        public DirectoryView(List<CityView> hotCities, List<ProvinceView> provinces) {
            this(hotCities, provinces, List.of(), List.of());
        }
    }

    public record ProvinceView(
            String code,
            String name,
            String pinyin,
            String initials,
            List<CityView> cities) {
    }

    public record CityView(
            String code,
            String name,
            String pinyin,
            String initials,
            String provinceCode,
            String provinceName,
            CityServiceStatus serviceStatus) {

        /** 没有开通状态的旧写法（别的模块的测试用）：当作未开通。 */
        public CityView(String code, String name, String pinyin, String initials, String provinceCode,
                        String provinceName) {
            this(code, name, pinyin, initials, provinceCode, provinceName, CityServiceStatus.CLOSED);
        }
    }
}
