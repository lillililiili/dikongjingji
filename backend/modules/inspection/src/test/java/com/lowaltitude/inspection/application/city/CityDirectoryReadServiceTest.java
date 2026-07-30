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
    void returnsSixDeterministicHotCitiesWhenAllSearchCountsAreZero() {
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

        assertThat(view.hotCities()).extracting(CityDirectoryReadService.CityView::code)
                .containsExactly("370500", "340100", "370100", "371300", "370200", "370700");
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
