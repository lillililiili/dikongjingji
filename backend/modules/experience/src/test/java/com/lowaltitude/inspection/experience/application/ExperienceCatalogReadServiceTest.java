package com.lowaltitude.inspection.experience.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.experience.application.ExperienceRows.ExperienceItemDetailRow;
import com.lowaltitude.inspection.experience.application.ExperienceRows.ExperienceItemRow;
import com.lowaltitude.inspection.experience.application.ExperienceRows.ProductExperienceRow;
import com.lowaltitude.inspection.experience.application.ExperienceRows.ProductVenueRow;
import com.lowaltitude.inspection.experience.application.ExperienceRows.VenueCardRow;
import com.lowaltitude.inspection.experience.application.ExperienceRows.VenueDeviceRow;
import com.lowaltitude.inspection.experience.application.ExperienceRows.VenueInfoRow;
import com.lowaltitude.inspection.experience.application.ExperienceRows.VenueProjectRow;
import com.lowaltitude.inspection.experience.application.ExperienceRows.VenueRow;
import com.lowaltitude.inspection.experience.application.ExperienceRows.VenueSessionRow;
import org.junit.jupiter.api.Test;

class ExperienceCatalogReadServiceTest {

    /** 北京时间 2026-11-16 12:00。 */
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-16T04:00:00Z");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-16T04:00:00Z"), ZoneOffset.UTC);
    private static final UUID VENUE_ID = UUID.fromString("b2000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT_ID = UUID.fromString("b4000000-0000-4000-8000-000000000101");

    private final FakeStore store = new FakeStore();
    private final ExperienceCatalogReadService service = new ExperienceCatalogReadService(store, CLOCK);

    @Test
    void experienceItemsArePagedAndFilteredByType() {
        store.experienceTotal = 5;
        store.experienceItems = List.of(new ExperienceItemRow(
                UUID.randomUUID(), "SIMULATION", "机器人避障模拟", " ", "http://insecure.example/a.jpg", false));

        ExperienceItemPageView page = service.listExperienceItems(" SIMULATION ", 2, 2);

        assertThat(page.hasMore()).isTrue();
        assertThat(store.type).isEqualTo("SIMULATION");
        assertThat(store.limit).isEqualTo(2);
        assertThat(store.offset).isEqualTo(2);
        assertThat(page.items().get(0).type()).isEqualTo(ExperienceType.SIMULATION);
        assertThat(page.items().get(0).summary()).isNull();
        assertThat(page.items().get(0).coverImage()).isNull();

        service.listExperienceItems(null, 1, 20);
        assertThat(store.type).isEqualTo("ALL");
    }

    // S5-4：仿真页、试用页读一个项目的模板和试用时长；没有或没发布（mapper 只查已发布的）时 404。
    @Test
    void oneExperienceItemCarriesItsTemplateAndTrialLength() {
        UUID simulation = UUID.fromString("b1000000-0000-4000-8000-000000000001");
        UUID product = UUID.fromString("c2000000-0000-4000-8000-000000000101");
        store.itemDetail = new ExperienceItemDetailRow(
                simulation, "SIMULATION", "机器人避障模拟", "约 3 分钟", null, false, product, "ROBOT_OBSTACLE", null);

        ExperienceItemDetailView item = service.findExperienceItem(simulation);

        assertThat(item.simulationTemplate()).isEqualTo(SimulationTemplate.ROBOT_OBSTACLE);
        assertThat(item.trialMinutes()).isNull();
        assertThat(item.productId()).isEqualTo(product);
        assertThatThrownBy(() -> service.findExperienceItem(UUID.randomUUID()))
                .isInstanceOf(VenueNotFoundException.class);
        assertInvalid(() -> service.findExperienceItem(null));

        store.itemDetail = new ExperienceItemDetailRow(
                simulation, "SOFTWARE_TRIAL", "图形化编程软件试用", null, null, true, null, null, 25);
        assertThat(service.findExperienceItem(simulation).trialMinutes()).isEqualTo(25);
        assertThat(service.findExperienceItem(simulation).simulationTemplate()).isNull();
    }

    @Test
    void courseLinksRequireAPublishedSimulationWithATemplate() {
        UUID id = UUID.randomUUID();
        assertThat(service.isPlayableSimulation(id)).isFalse();
        store.itemDetail = new ExperienceItemDetailRow(id, "SIMULATION", "仿真", null, null, false, null, null, null);
        assertThat(service.isPlayableSimulation(id)).isFalse();
        store.itemDetail = new ExperienceItemDetailRow(id, "COURSE_TRIAL", "试听", null, null, false, null, null, 10);
        assertThat(service.isPlayableSimulation(id)).isFalse();
        store.itemDetail = new ExperienceItemDetailRow(id, "SIMULATION", "仿真", null, null, false, null, "DRONE_HOVER", null);
        assertThat(service.isPlayableSimulation(id)).isTrue();
    }

    @Test
    void rejectsInvalidPagingAndFilters() {
        assertInvalid(() -> service.listExperienceItems("GAME", 1, 20));
        assertInvalid(() -> service.listExperienceItems(null, 0, 20));
        assertInvalid(() -> service.listExperienceItems(null, 1, 51));
        assertInvalid(() -> service.listVenues(null, 1, 20));
        assertInvalid(() -> service.listVenues("x", 1, 20));
        assertInvalid(() -> service.listVenues("370500", 1, 0));
        assertInvalid(() -> service.listDevices(VENUE_ID, "370500", "CAR", 1, 20));
    }

    @Test
    void venueListCountsOnlyWhatIsLeftOfTodayInBeijingTime() {
        store.venueTotal = 1;
        store.venueCards = List.of(new VenueCardRow(
                VENUE_ID, "东营低空与机器人体验馆", "https://media.example.com/v.jpg",
                "09:30–18:00 · 周一闭馆", "东营区示例路 66 号", 3, "模拟飞行 · 机器人编程 · 厂家展区"));

        VenuePageView page = service.listVenues("370500", 1, 20);

        assertThat(store.now).isEqualTo(NOW);
        // 北京时间 11-17 00:00，也就是 UTC 11-16 16:00。
        assertThat(store.endOfToday.toInstant()).isEqualTo(Instant.parse("2026-11-16T16:00:00Z"));
        assertThat(page.hasMore()).isFalse();
        assertThat(page.items().get(0).openSessionsToday()).isEqualTo(3);
        assertThat(page.items().get(0).projectSummary()).isEqualTo("模拟飞行 · 机器人编程 · 厂家展区");
        assertThat(page.items().get(0).coverImage())
                .isEqualTo(new ExperienceMediaView("https://media.example.com/v.jpg", "东营低空与机器人体验馆"));
    }

    @Test
    void venueDetailCarriesProjectsAndInformationRows() {
        store.venue = Optional.of(venue());
        store.projects = List.of(new VenueProjectRow(
                PROJECT_ID, "机器人避障闯关", 30, 6, 8, "未满 8 岁需 1 名成人陪同", 3900, "CNY"));
        store.infoItems = List.of(new VenueInfoRow("到馆须知", "请提前 10 分钟到馆，出示核销码入场"));

        VenueDetailView view = service.findVenue(VENUE_ID, "370500");

        assertThat(view.operator().name()).isEqualTo("东营低空与机器人体验馆");
        assertThat(view.latitude()).isEqualTo(37.4487);
        assertThat(view.phone()).isEqualTo("0546-8000 0000");
        assertThat(view.deviceCount()).isEqualTo(6);
        assertThat(view.checkIn()).isEqualTo(new VenueCheckInRuleView(15, 10));
        assertThat(view.projects()).singleElement().satisfies(project -> {
            assertThat(project.priceAmountMinor()).isEqualTo(3900);
            assertThat(project.minAge()).isEqualTo(6);
            assertThat(project.currency()).isEqualTo("CNY");
        });
        assertThat(view.infoItems()).containsExactly(
                new VenueInfoItemView("到馆须知", "请提前 10 分钟到馆，出示核销码入场"));
    }

    // E10：项目的 onlinePayment 只在标价大于 0、运营机构能在线收款时为 true；标价为 0 的为 false（不用付款）。
    // 只问一次运营机构；没有要付款的项目时不问；没接交易模块的服务一律 false。
    @Test
    void projectsSayWhetherTheyCanBePaidOnline() {
        store.venue = Optional.of(venue());
        store.projects = List.of(
                new VenueProjectRow(PROJECT_ID, "机器人避障闯关", 30, 6, 8, null, 3900, "CNY"),
                new VenueProjectRow(UUID.randomUUID(), "无人机模拟飞行", 30, 8, 8, null, 3900, "CNY"),
                new VenueProjectRow(UUID.randomUUID(), "免费参观", 20, null, 30, null, 0, "CNY"));
        java.util.List<UUID> asked = new java.util.ArrayList<>();
        ExperienceCatalogReadService paying = new ExperienceCatalogReadService(store, CLOCK, operator -> {
            asked.add(operator);
            return true;
        });

        assertThat(paying.findVenue(VENUE_ID, "370500").projects())
                .extracting(VenueProjectView::onlinePayment)
                .containsExactly(true, true, false);
        assertThat(asked).containsExactly(venue().operatorId());
        assertThat(service.findVenue(VENUE_ID, "370500").projects())
                .extracting(VenueProjectView::onlinePayment)
                .containsOnly(false);

        asked.clear();
        store.projects = List.of(new VenueProjectRow(UUID.randomUUID(), "免费参观", 20, null, 30, null, 0, "CNY"));
        paying.findVenue(VENUE_ID, "370500");
        assertThat(asked).isEmpty();
    }

    @Test
    void venueIsNotFoundWhenUnpublishedOrInAnotherCity() {
        store.venue = Optional.empty();

        assertThatThrownBy(() -> service.findVenue(VENUE_ID, "370500"))
                .isInstanceOf(VenueNotFoundException.class);
        assertThatThrownBy(() -> service.listDevices(VENUE_ID, "370500", null, 1, 20))
                .isInstanceOf(VenueNotFoundException.class);
        assertInvalid(() -> service.findVenue(null, "370500"));
        assertInvalid(() -> service.findVenue(VENUE_ID, null));
    }

    @Test
    void sessionsAreOpenFullOrEndedForTheRequestedBeijingDay() {
        store.venue = Optional.of(venue());
        store.projectBelongs = true;
        store.sessions = List.of(
                session(NOW.minusHours(2), 8, 1),
                session(NOW.plusHours(2), 8, 8),
                session(NOW.plusHours(4), 8, 7),
                session(NOW, 8, 0));

        VenueSessionListView view = service.listSessions(VENUE_ID, "370500", PROJECT_ID, "2026-11-16");

        assertThat(view.date()).isEqualTo(LocalDate.parse("2026-11-16"));
        assertThat(view.sessions()).extracting(VenueSessionView::status).containsExactly(
                VenueSessionStatus.ENDED,
                VenueSessionStatus.FULL,
                VenueSessionStatus.OPEN,
                // 正好到开始时刻的场次已不能再约。
                VenueSessionStatus.ENDED);
        assertThat(view.sessions()).extracting(VenueSessionView::seatsLeft).containsExactly(7, 0, 1, 8);
        assertThat(store.dayStart.toInstant()).isEqualTo(Instant.parse("2026-11-15T16:00:00Z"));
        assertThat(store.dayEnd.toInstant()).isEqualTo(Instant.parse("2026-11-16T16:00:00Z"));
    }

    @Test
    void sessionsOfAProjectFromAnotherVenueAreNotFound() {
        store.venue = Optional.of(venue());
        store.projectBelongs = false;

        assertThatThrownBy(() -> service.listSessions(VENUE_ID, "370500", PROJECT_ID, "2026-11-16"))
                .isInstanceOf(VenueNotFoundException.class);
        assertThat(store.sessionsQueried).isFalse();
        assertInvalid(() -> service.listSessions(VENUE_ID, "370500", null, "2026-11-16"));
        assertInvalid(() -> service.listSessions(VENUE_ID, "370500", PROJECT_ID, "bad"));
        assertInvalid(() -> service.listSessions(VENUE_ID, "370500", PROJECT_ID, null));
    }

    @Test
    void devicesKeepTheirCategoryAndTrialMode() {
        store.venue = Optional.of(venue());
        store.deviceTotal = 1;
        store.devices = List.of(new VenueDeviceRow(
                UUID.randomUUID(), "ROBOT", "巡检机器狗", "园区巡逻与巡检演示", null, "DISPLAY_ONLY", null));

        VenueDevicePageView page = service.listDevices(VENUE_ID, "370500", "ROBOT", 1, 20);

        assertThat(store.deviceCategory).isEqualTo("ROBOT");
        assertThat(page.items()).singleElement().satisfies(device -> {
            assertThat(device.category()).isEqualTo(VenueDeviceCategory.ROBOT);
            assertThat(device.trialMode()).isEqualTo(VenueDeviceTrialMode.DISPLAY_ONLY);
            assertThat(device.image()).isNull();
            assertThat(device.product()).isNull();
        });
    }

    // S4-4：设备关联的商品经商城端口按场馆所在城市取价格和在售家数；这个城市没有在售时不带商品，也不问没关联的。
    @Test
    void devicesCarryTheirProductOfferInTheVenueCity() {
        UUID sold = UUID.fromString("c2000000-0000-4000-8000-000000000101");
        UUID unsold = UUID.fromString("c2000000-0000-4000-8000-000000000102");
        List<UUID> asked = new java.util.ArrayList<>();
        ExperienceCatalogReadService withMall = new ExperienceCatalogReadService(store, CLOCK, operator -> false,
                (productId, cityCode) -> {
                    asked.add(productId);
                    assertThat(cityCode).isEqualTo("370500");
                    return productId.equals(sold)
                            ? Optional.of(new VenueDeviceOfferPort.VenueDeviceOffer(
                                    VenueDeviceOfferPort.SaleMode.BUY, 129_900L,
                                    VenueDeviceOfferPort.PriceUnit.ITEM, true, 3))
                            : Optional.empty();
                });
        store.venue = Optional.of(venue());
        store.deviceTotal = 3;
        store.devices = List.of(
                new VenueDeviceRow(UUID.randomUUID(), "ROBOT", "编程机器人探索套装", null, null,
                        "ACCOMPANIED_TRIAL", sold),
                new VenueDeviceRow(UUID.randomUUID(), "DRONE", "便携航拍无人机", null, null,
                        "ACCOMPANIED_TRIAL", unsold),
                new VenueDeviceRow(UUID.randomUUID(), "PARTS", "无人机配件套装", null, null,
                        "DISPLAY_ONLY", null));

        VenueDevicePageView page = withMall.listDevices(VENUE_ID, "370500", null, 1, 20);

        assertThat(page.items().get(0).product()).isEqualTo(new VenueDeviceProductView(
                sold, VenueDeviceOfferPort.SaleMode.BUY, 129_900L, VenueDeviceOfferPort.PriceUnit.ITEM, true, 3));
        assertThat(page.items().get(1).product()).isNull();
        assertThat(page.items().get(2).product()).isNull();
        assertThat(asked).containsExactly(sold, unsold);
    }

    // R6-2 先体验：线上体验和本城场馆原样给出（空白说明当作没有），读的时候带上限；城市只用来查场馆。
    @Test
    void productExperiencesListTheItemsAndTheVenuesOfTheCity() {
        UUID product = UUID.fromString("c2000000-0000-4000-8000-000000000101");
        UUID item = UUID.fromString("b1000000-0000-4000-8000-000000000001");
        store.productExperiences = List.of(
                new ProductExperienceRow(item, "SIMULATION", "机器人避障模拟", "约 3 分钟 · 免登录可试"),
                new ProductExperienceRow(UUID.randomUUID(), "RECORDED_DEMO", "3 分钟看懂编程机器人", " "));
        store.productVenues = List.of(new ProductVenueRow(VENUE_ID, "东营低空与机器人体验馆", "编程机器人探索套装"));

        ProductExperiencesView view = service.findProductExperiences(product, "370500");

        assertThat(view.productId()).isEqualTo(product);
        assertThat(view.experiences()).extracting(ProductExperienceItemView::type)
                .containsExactly(ExperienceType.SIMULATION, ExperienceType.RECORDED_DEMO);
        assertThat(view.experiences().get(0)).isEqualTo(
                new ProductExperienceItemView(item, ExperienceType.SIMULATION, "机器人避障模拟", "约 3 分钟 · 免登录可试"));
        assertThat(view.experiences().get(1).summary()).isNull();
        assertThat(view.venues()).containsExactly(
                new ProductExperienceVenueView(VENUE_ID, "东营低空与机器人体验馆", "编程机器人探索套装"));
        assertThat(store.productId).isEqualTo(product);
        assertThat(store.productCity).isEqualTo("370500");
        assertThat(store.productExperienceLimit).isEqualTo(ExperienceCatalogReadService.MAX_PRODUCT_EXPERIENCES);
        assertThat(store.productVenueLimit).isEqualTo(ExperienceCatalogReadService.MAX_PRODUCT_VENUES);
    }

    @Test
    void aProductWithoutLinksHasTwoEmptyListsAndBadArgumentsAreRejected() {
        UUID product = UUID.fromString("c2000000-0000-4000-8000-000000000301");

        ProductExperiencesView view = service.findProductExperiences(product, "370500");

        assertThat(view.experiences()).isEmpty();
        assertThat(view.venues()).isEmpty();
        assertInvalid(() -> service.findProductExperiences(null, "370500"));
        assertInvalid(() -> service.findProductExperiences(product, null));
        assertInvalid(() -> service.findProductExperiences(product, "37-05"));
    }

    private static void assertInvalid(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOf(IllegalArgumentException.class);
    }

    private static VenueRow venue() {
        return new VenueRow(
                VENUE_ID, "东营低空与机器人体验馆", null, "09:30–18:00 · 周一闭馆", "东营区示例路 66 号",
                37.4487, 118.5823, "0546-8000 0000",
                UUID.fromString("a1000000-0000-4000-8000-000000000004"), "东营低空与机器人体验馆", 6, 15, 10);
    }

    private static VenueSessionRow session(OffsetDateTime startsAt, int capacity, int booked) {
        return new VenueSessionRow(UUID.randomUUID(), startsAt, startsAt.plusMinutes(30), capacity, booked);
    }

    private static final class FakeStore implements ExperienceCatalogReadStore {
        long experienceTotal;
        List<ExperienceItemRow> experienceItems = List.of();
        ExperienceItemDetailRow itemDetail;
        long venueTotal;
        List<VenueCardRow> venueCards = List.of();
        Optional<VenueRow> venue = Optional.empty();
        List<VenueInfoRow> infoItems = List.of();
        List<VenueProjectRow> projects = List.of();
        boolean projectBelongs;
        List<VenueSessionRow> sessions = List.of();
        long deviceTotal;
        List<VenueDeviceRow> devices = List.of();
        String type;
        String deviceCategory;
        int limit;
        int offset;
        OffsetDateTime now;
        OffsetDateTime endOfToday;
        OffsetDateTime dayStart;
        OffsetDateTime dayEnd;
        boolean sessionsQueried;
        List<ProductExperienceRow> productExperiences = List.of();
        List<ProductVenueRow> productVenues = List.of();
        UUID productId;
        String productCity;
        int productExperienceLimit;
        int productVenueLimit;

        @Override
        public long countExperienceItems(String type) {
            this.type = type;
            return experienceTotal;
        }

        @Override
        public List<ExperienceItemRow> findExperienceItems(String type, int limit, int offset) {
            this.limit = limit;
            this.offset = offset;
            return experienceItems;
        }

        @Override
        public Optional<ExperienceItemDetailRow> findExperienceItem(UUID experienceId) {
            return Optional.ofNullable(itemDetail).filter(row -> row.experienceId().equals(experienceId));
        }

        @Override
        public long countVenues(String cityCode) {
            return venueTotal;
        }

        @Override
        public List<VenueCardRow> findVenues(
                String cityCode, OffsetDateTime now, OffsetDateTime endOfToday, int limit, int offset
        ) {
            this.now = now;
            this.endOfToday = endOfToday;
            return venueCards;
        }

        @Override
        public Optional<VenueRow> findVenue(UUID venueId, String cityCode) {
            return venue;
        }

        @Override
        public List<VenueInfoRow> findInfoItems(UUID venueId) {
            return infoItems;
        }

        @Override
        public List<VenueProjectRow> findProjects(UUID venueId) {
            return projects;
        }

        @Override
        public boolean projectBelongsToVenue(UUID venueId, UUID projectId) {
            return projectBelongs;
        }

        @Override
        public List<VenueSessionRow> findSessions(
                UUID projectId, OffsetDateTime dayStart, OffsetDateTime dayEnd
        ) {
            sessionsQueried = true;
            this.dayStart = dayStart;
            this.dayEnd = dayEnd;
            return sessions;
        }

        @Override
        public long countDevices(UUID venueId, String category) {
            this.deviceCategory = category;
            return deviceTotal;
        }

        @Override
        public List<VenueDeviceRow> findDevices(UUID venueId, String category, int limit, int offset) {
            return devices;
        }

        @Override
        public List<ProductExperienceRow> findProductExperiences(UUID productId, int limit) {
            this.productId = productId;
            this.productExperienceLimit = limit;
            return productExperiences;
        }

        @Override
        public List<ProductVenueRow> findProductVenues(UUID productId, String cityCode, int limit) {
            this.productCity = cityCode;
            this.productVenueLimit = limit;
            return productVenues;
        }
    }
}
