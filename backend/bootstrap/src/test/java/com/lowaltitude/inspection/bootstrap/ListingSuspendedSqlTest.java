package com.lowaltitude.inspection.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityCatalogMapper;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityEnrollmentMapper;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityOrganizerMapper;
import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityWaitlistMapper;
import com.lowaltitude.inspection.course.infrastructure.persistence.CourseMapper;
import com.lowaltitude.inspection.experience.infrastructure.persistence.ExperienceCatalogMapper;
import com.lowaltitude.inspection.experience.infrastructure.persistence.VenueBookingMapper;
import com.lowaltitude.inspection.infrastructure.persistence.InspectionProviderDirectoryMapper;
import com.lowaltitude.inspection.infrastructure.persistence.InspectionServiceCatalogMapper;
import com.lowaltitude.inspection.infrastructure.persistence.ReservationPersistenceMapper;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallCartMapper;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallCatalogMapper;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallInquiryMapper;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallMerchantMapper;
import com.lowaltitude.inspection.proservice.infrastructure.persistence.ProServiceMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * 资质没审核通过的机构在 App 不可见（身份迁移 V013）：各模块的公开读取、能不能下单 / 预约 / 报名的判断，SQL 里都看
 * listing_suspended；已成交之后的读取（订单、凭证、核销）不看。新加公开读取时记得加到这里。
 */
class ListingSuspendedSqlTest {

    private static final Map<Class<?>, List<String>> PUBLIC_READS = Map.ofEntries(
            Map.entry(MallCatalogMapper.class, List.of("countProducts", "findProducts", "findOffers")),
            Map.entry(MallCartMapper.class, List.of("findOffer", "findCart")),
            Map.entry(MallInquiryMapper.class, List.of("findInquiryOffers", "findOffers")),
            Map.entry(MallMerchantMapper.class, List.of("findListedOrganizationName")),
            Map.entry(InspectionServiceCatalogMapper.class, List.of("countMatching", "findMatchingPage",
                    "existsVisibleInCity", "findMostCompleted", "findServiceMethodsInCity", "findPriceModesInCity",
                    "existsReportServiceInCity", "findActiveDetail")),
            Map.entry(ReservationPersistenceMapper.class, List.of("findActiveService", "lockActiveService")),
            Map.entry(InspectionProviderDirectoryMapper.class, List.of("findProvidersIn", "findServicesOf")),
            Map.entry(ProServiceMapper.class, List.of("countCatalog", "findCatalog", "countOfferedInCity",
                    "findVisible", "findProviderServices", "findProviderIdsIn", "isListingSuspended")),
            Map.entry(ActivityCatalogMapper.class, List.of("countListed", "findListedPage", "findSessionDates",
                    "findPublishedDetail")),
            Map.entry(ActivityOrganizerMapper.class, List.of("countUpcoming", "findUpcoming")),
            Map.entry(ActivityEnrollmentMapper.class, List.of("lockSession", "findSession")),
            Map.entry(ActivityWaitlistMapper.class, List.of("findSessionSeats")),
            Map.entry(ExperienceCatalogMapper.class, List.of("countVenues", "findVenues", "findVenue",
                    "findProductVenues")),
            Map.entry(VenueBookingMapper.class, List.of("lockSession", "findSession")),
            Map.entry(CourseMapper.class, List.of("findListedCourses", "isListingSuspended")));

    /** 已成交之后的读取：机构被挂起也照常。 */
    private static final Map<Class<?>, List<String>> AFTER_THE_DEAL = Map.of(
            ReservationPersistenceMapper.class, List.of("findReservation"),
            ActivityEnrollmentMapper.class, List.of("lockTicketForOrganizer"),
            CourseMapper.class, List.of("findCourse", "lockCourse", "findBundledCourses"));

    @TestFactory
    List<DynamicTest> publicReadsLookAtListingSuspended() {
        return each(PUBLIC_READS, sql -> assertThat(sql).contains("listing_suspended").doesNotContain("${"));
    }

    @TestFactory
    List<DynamicTest> readsAfterTheDealDoNot() {
        return each(AFTER_THE_DEAL, sql -> assertThat(sql).doesNotContain("listing_suspended"));
    }

    private static List<DynamicTest> each(Map<Class<?>, List<String>> methods,
                                          java.util.function.Consumer<String> check) {
        return methods.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream().map(name -> DynamicTest.dynamicTest(
                        entry.getKey().getSimpleName() + "." + name, () -> check.accept(sql(entry.getKey(), name)))))
                .toList();
    }

    private static String sql(Class<?> mapper, String name) {
        Method method = Arrays.stream(mapper.getMethods()).filter(candidate -> candidate.getName().equals(name))
                .findFirst().orElseThrow(() -> new AssertionError(mapper.getSimpleName() + " has no " + name));
        return String.join("\n", method.getAnnotation(Select.class).value());
    }
}
