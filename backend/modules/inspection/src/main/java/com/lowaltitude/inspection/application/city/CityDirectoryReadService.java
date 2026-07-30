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
            .comparing(ProvinceView::initials)
            .thenComparing(ProvinceView::pinyin)
            .thenComparing(ProvinceView::code);
    private static final Comparator<StoredCity> CITY_ORDER = Comparator
            .comparing(StoredCity::cityInitials)
            .thenComparing(StoredCity::cityPinyin)
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
        return new DirectoryView(hot, provinces);
    }

    public boolean recordSearchSelection(String cityCode) {
        if (cityCode == null || !CITY_CODE.matcher(cityCode).matches()) {
            throw new IllegalArgumentException("city code is invalid");
        }
        return store.incrementSearchCount(cityCode) == 1;
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
                city.provinceName());
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
            long searchCount) {
    }

    public record DirectoryView(
            List<CityView> hotCities,
            List<ProvinceView> provinces) {
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
            String provinceName) {
    }
}
