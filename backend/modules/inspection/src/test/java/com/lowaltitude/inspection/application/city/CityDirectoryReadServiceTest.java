package com.lowaltitude.inspection.application.city;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class CityDirectoryReadServiceTest {

    @Test
    void groupsSortedProvincesAndLimitsHotCitiesToSix() {
        CityDirectoryReadService service = new CityDirectoryReadService(new FakeStore(rows()));

        CityDirectoryReadService.DirectoryView view = service.directory();

        assertThat(view.hotCities()).extracting(CityDirectoryReadService.CityView::code)
                .containsExactly("370100", "370200", "370500", "370600", "370700", "371300");
        assertThat(view.provinces()).extracting(CityDirectoryReadService.ProvinceView::name)
                .containsExactly("安徽省", "山东省");
        assertThat(view.provinces().get(1).cities())
                .extracting(CityDirectoryReadService.CityView::name)
                .containsExactly("东营市", "济南市", "临沂市", "青岛市", "潍坊市", "烟台市");
    }

    @Test
    void leavesHotCitiesEmptyWhenThereIsNoSearchEvidence() {
        List<CityDirectoryReadService.StoredCity> zeroCountCities = rows().stream()
                .map(city -> city(
                        city.cityCode(),
                        city.cityName(),
                        city.cityPinyin(),
                        city.cityInitials(),
                        0))
                .toList();
        CityDirectoryReadService service = new CityDirectoryReadService(new FakeStore(zeroCountCities));

        CityDirectoryReadService.DirectoryView view = service.directory();

        assertThat(view.hotCities()).isEmpty();
        assertThat(view.provinces()).hasSize(2);
    }

    @Test
    void sortsProvincesAndCitiesByFullPinyinWithinTheSameInitial() {
        CityDirectoryReadService service = new CityDirectoryReadService(new FakeStore(List.of(
                storedCity("510100", "chengdushi", "cds",
                        "510000", "sichuansheng", "scs"),
                storedCity("370500", "dongyingshi", "dys",
                        "370000", "shandongsheng", "sds"),
                storedCity("371400", "dezhoushi", "dzs",
                        "370000", "shandongsheng", "sds"),
                storedCity("310000", "shanghaishi", "shs",
                        "310000", "shanghaishi", "shs"),
                storedCity("140100", "taiyuanshi", "tys",
                        "140000", "shanxisheng", "sxs"))));

        CityDirectoryReadService.DirectoryView view = service.directory();

        assertThat(view.provinces())
                .extracting(CityDirectoryReadService.ProvinceView::pinyin)
                .containsExactly(
                        "shandongsheng",
                        "shanghaishi",
                        "shanxisheng",
                        "sichuansheng");
        assertThat(view.provinces().getFirst().cities())
                .extracting(CityDirectoryReadService.CityView::pinyin)
                .containsExactly("dezhoushi", "dongyingshi");
    }

    // S3-1：App 城市面板只列已开通、筹备中两组（各按城市拼音）；未开通的只在全部城市里，搜索用。
    @Test
    void groupsOpenAndPreparingCitiesForTheAppSheet() {
        List<CityDirectoryReadService.StoredCity> cities = rows().stream()
                .map(city -> new CityDirectoryReadService.StoredCity(city.cityCode(), city.cityName(),
                        city.cityPinyin(), city.cityInitials(), city.provinceCode(), city.provinceName(),
                        city.provincePinyin(), city.provinceInitials(), city.searchCount(),
                        switch (city.cityCode()) {
                            case "370500" -> CityServiceStatus.OPEN;
                            case "370100", "370700", "370200" -> CityServiceStatus.PREPARING;
                            default -> null;
                        }))
                .toList();

        CityDirectoryReadService.DirectoryView view = new CityDirectoryReadService(new FakeStore(cities)).directory();

        assertThat(view.openCities()).extracting(CityDirectoryReadService.CityView::name).containsExactly("东营市");
        assertThat(view.preparingCities()).extracting(CityDirectoryReadService.CityView::name)
                .containsExactly("济南市", "青岛市", "潍坊市");
        assertThat(view.provinces().get(1).cities()).hasSize(6);
        assertThat(view.provinces().get(1).cities())
                .filteredOn(city -> city.name().equals("烟台市"))
                .singleElement()
                .satisfies(city -> assertThat(city.serviceStatus()).isEqualTo(CityServiceStatus.CLOSED));
    }

    @Test
    void incrementsOnlyValidSixDigitActiveCity() {
        FakeStore store = new FakeStore(rows());
        CityDirectoryReadService service = new CityDirectoryReadService(store);

        assertThat(service.recordSearchSelection("370100")).isTrue();
        assertThat(store.incrementedCode).isEqualTo("370100");
        assertThatThrownBy(() -> service.recordSearchSelection("../bad"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.recordSearchSelection("999999")).isFalse();
    }

    private static List<CityDirectoryReadService.StoredCity> rows() {
        return List.of(
                city("370200", "青岛市", "qingdaoshi", "qds", 11),
                city("370500", "东营市", "dongyingshi", "dys", 10),
                city("370100", "济南市", "jinanshi", "jns", 12),
                city("370600", "烟台市", "yantaishi", "yts", 9),
                city("370700", "潍坊市", "weifangshi", "wfs", 8),
                city("371300", "临沂市", "linyishi", "lys", 7),
                city("340100", "合肥市", "hefeishi", "hfs", 0));
    }

    private static CityDirectoryReadService.StoredCity city(
            String cityCode,
            String cityName,
            String cityPinyin,
            String cityInitials,
            long searchCount
    ) {
        boolean isAnhui = cityCode.startsWith("34");
        return new CityDirectoryReadService.StoredCity(
                cityCode,
                cityName,
                cityPinyin,
                cityInitials,
                isAnhui ? "340000" : "370000",
                isAnhui ? "安徽省" : "山东省",
                isAnhui ? "anhuisheng" : "shandongsheng",
                isAnhui ? "ahs" : "sds",
                searchCount);
    }

    private static CityDirectoryReadService.StoredCity storedCity(
            String cityCode,
            String cityPinyin,
            String cityInitials,
            String provinceCode,
            String provincePinyin,
            String provinceInitials
    ) {
        return new CityDirectoryReadService.StoredCity(
                cityCode,
                cityPinyin,
                cityPinyin,
                cityInitials,
                provinceCode,
                provincePinyin,
                provincePinyin,
                provinceInitials,
                0);
    }

    private static final class FakeStore implements CityDirectoryReadService.CityDirectoryStore {
        private final List<CityDirectoryReadService.StoredCity> cities;
        private String incrementedCode;

        private FakeStore(List<CityDirectoryReadService.StoredCity> cities) {
            this.cities = cities;
        }

        @Override
        public List<CityDirectoryReadService.StoredCity> findActiveCities() {
            return cities;
        }

        @Override
        public int incrementSearchCount(String cityCode) {
            if (cities.stream().noneMatch(city -> city.cityCode().equals(cityCode))) {
                return 0;
            }
            incrementedCode = cityCode;
            return 1;
        }
    }
}
