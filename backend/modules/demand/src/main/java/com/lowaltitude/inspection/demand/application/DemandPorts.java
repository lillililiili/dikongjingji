package com.lowaltitude.inspection.demand.application;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 需求模块用到的外部能力，由启动模块接上（§22.3）：平台城市目录、服务商名录（巡检模块的服务商资料）、
 * 站内信。需求模块不读别的模块的表。
 */
public final class DemandPorts {

    private DemandPorts() {
    }

    /** 平台城市目录（只到地级市）。 */
    public interface CityDirectory {
        /** 城市名；不在目录里或停用时为空。 */
        Optional<String> cityName(String cityCode);
    }

    /**
     * 服务商名录：有服务商资料（在用）的服务商机构，以及它们的服务覆盖哪些城市。
     * 平台匹配 = 发布城市里的这些服务商都能看到并报价。
     */
    public interface ProviderDirectory {

        /** 服务这个城市的服务商，按名称排。 */
        List<Provider> providersIn(String cityCode);

        /** 这些机构里有服务商资料的；不认识的不返回。 */
        List<Provider> find(List<UUID> providerIds);

        /** 这家服务商服务的城市；没有服务商资料时为空集。 */
        Set<String> citiesServed(UUID providerId);
    }

    /**
     * @param certificationLabel    资质说明，如“企业认证”；可以为空
     * @param description           一句话介绍（团队说明）；可以为空
     * @param qualificationReviewed 平台审核过这家服务商的资质（机构有资质审核通过时间，R6-4）
     */
    public record Provider(UUID providerId, String name, String certificationLabel, String description,
                           boolean qualificationReviewed) {
    }

    /**
     * 通用服务订单（S4-3）：航拍、测绘、设备维护、技术支持需求选定报价时，在选定的同一个事务里建单，返回订单 ID。
     * 同一份需求再建返回同一张。巡检需求不调。
     */
    public interface ServiceOrders {

        UUID open(ChosenQuote chosen);
    }

    /**
     * 建服务订单用的快照：需求内容和选定那一版报价。organizationId / organizationName 是需求的发布组织（个人时都为空）。
     */
    public record ChosenQuote(
            UUID demandId,
            UUID quoteId,
            int quoteVersion,
            UUID accountId,
            UUID organizationId,
            String organizationName,
            UUID providerId,
            String providerName,
            String type,
            String title,
            String cityCode,
            String cityName,
            String address,
            String scope,
            java.time.LocalDate dueDate,
            long amountMinor,
            String method,
            String schedule,
            int durationDays,
            String deliverables,
            String team,
            String nightWork,
            Integer reportHours,
            Integer archiveDays,
            java.time.LocalDate firstVisitDate,
            List<ChosenLine> lines
    ) {
    }

    public record ChosenLine(String name, Integer quantity, String unit, Long unitAmountMinor, long amountMinor) {
    }

    /**
     * 给发布人发站内信（“消息”页的“需求与报价”一类）。在调用方的事务里调用，和业务状态一起提交。
     * 通知里只放需求标题和稳定 ID，不放服务商的报价内容。
     */
    public interface Notifier {

        /** 一家服务商第一次报价（或撤回后重新报价）。sourceKey 保证同一件事只记一条。 */
        void quoteReceived(UUID recipientId, UUID demandId, String demandTitle, String sourceKey);

        /** 报价期内没有服务商报价，需求自动关闭。 */
        void closedWithoutQuotes(UUID recipientId, UUID demandId, String demandTitle);
    }
}
