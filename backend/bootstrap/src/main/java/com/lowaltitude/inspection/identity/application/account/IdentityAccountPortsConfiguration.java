package com.lowaltitude.inspection.identity.application.account;

import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.lowaltitude.inspection.account.application.AccountAddressService;
import com.lowaltitude.inspection.activity.application.ActivityEnrollmentService;
import com.lowaltitude.inspection.application.city.CityOperationsService;
import com.lowaltitude.inspection.bootstrap.infrastructure.LocalDataExportStore;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper;
import com.lowaltitude.inspection.trade.application.TradeEnterpriseAccess;
import com.lowaltitude.inspection.trade.application.TradeOrderService;
import com.lowaltitude.inspection.trade.application.TradePayerPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * 账号与企业组织（S5-7、S5-8）的端口接到外部服务和别的模块；身份模块不读别的模块的表。
 *
 * <ul>
 *   <li>短信：identity.sms.provider = fake 时是本机模拟服务（只在 application-dev.yml），其他值一律不可用（503）。</li>
 *   <li>导出存储：配置了 identity.data-export.local-dir（只在 application-dev.yml）时用本机目录，否则不可用。</li>
 *   <li>导出的数据源：参与人（活动）、收货地址（账号）、订单（交易）；注销条件：进行中的订单（交易）、待参加的报名（活动）；
 *       注销到期去标识：收货地址、参与人、订单配送快照。</li>
 *   <li>交易的付款主体和企业订单的成员校验接到企业服务。</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class IdentityAccountPortsConfiguration {

    @Bean
    AccountPorts.SmsGateway accountSmsGateway(
            @Value("${identity.sms.provider:none}") String provider, AccountMapper mapper, Clock identityClock) {
        return "fake".equals(provider) ? new FakeSmsGateway(mapper, identityClock) : new FakeSmsGateway.Unavailable();
    }

    @Bean
    AccountPorts.ExportFiles accountExportFiles(@Value("${identity.data-export.local-dir:}") String localDirectory) {
        return localDirectory == null || localDirectory.isBlank()
                ? new LocalDataExportStore.Unavailable()
                : new LocalDataExportStore(Path.of(localDirectory));
    }

    @Bean
    AccountPorts.CityNames accountCityNames(CityOperationsService cities) {
        return codes -> {
            Map<String, String> names = new LinkedHashMap<>();
            for (String code : codes) {
                cities.cityName(code).ifPresent(name -> names.put(code, name));
            }
            return names;
        };
    }

    @Bean
    AccountPorts.JsonWriter accountJsonWriter(ObjectMapper objectMapper) {
        ObjectMapper writer = objectMapper.copy()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return value -> {
            try {
                return writer.writeValueAsBytes(value);
            } catch (JsonProcessingException failure) {
                throw new IllegalStateException("export section could not be written", failure);
            }
        };
    }

    // ---- 导出的数据源（顺序即页面和文件的顺序） ----

    @Bean
    @Order(10)
    AccountPorts.PersonalDataSource participantsDataSource(ObjectProvider<ActivityEnrollmentService> enrollments) {
        return source("PARTICIPANTS", "参与人", "姓名、关系、出生年月",
                account -> (long) enrollments.getObject().exportParticipants(account).size(),
                account -> enrollments.getObject().exportParticipants(account));
    }

    @Bean
    @Order(20)
    AccountPorts.PersonalDataSource addressesDataSource(ObjectProvider<AccountAddressService> addresses) {
        return source("ADDRESSES", "收货地址", "收货人、手机号、详细地址",
                account -> (long) addresses.getObject().list(account).items().size(),
                account -> addresses.getObject().list(account).items());
    }

    @Bean
    @Order(30)
    AccountPorts.PersonalDataSource ordersDataSource(ObjectProvider<TradeOrderService> orders) {
        return source("ORDERS", "订单记录", "商品、服务和报名订单",
                account -> (long) orders.getObject().exportOrders(account).size(),
                account -> orders.getObject().exportOrders(account));
    }

    // ---- 注销条件 ----

    @Bean
    @Order(10)
    AccountPorts.ClosureCondition openOrdersCondition(ObjectProvider<TradeOrderService> orders) {
        return condition("NO_OPEN_ORDERS", "没有进行中的订单", account -> {
            long open = orders.getObject().countOpenOrders(account);
            return open == 0 ? null : open + " 笔订单未完成";
        });
    }

    @Bean
    @Order(20)
    AccountPorts.ClosureCondition pendingEnrollmentsCondition(ObjectProvider<ActivityEnrollmentService> enrollments) {
        return condition("NO_PENDING_ENROLLMENTS", "没有待参加的报名", account -> {
            long upcoming = enrollments.getObject().countUpcomingEnrollments(account);
            return upcoming == 0 ? null : upcoming + " 个报名待参加";
        });
    }

    // ---- 注销到期去标识 ----

    @Bean
    AccountPorts.PersonalDataEraser addressesEraser(ObjectProvider<AccountAddressService> addresses) {
        return account -> addresses.getObject().eraseForClosure(account);
    }

    @Bean
    AccountPorts.PersonalDataEraser participantsEraser(ObjectProvider<ActivityEnrollmentService> enrollments) {
        return account -> enrollments.getObject().anonymizeForClosure(account);
    }

    @Bean
    AccountPorts.PersonalDataEraser orderDeliveriesEraser(ObjectProvider<TradeOrderService> orders) {
        return account -> orders.getObject().anonymizeBuyer(account);
    }

    // ---- 交易 ----

    @Bean
    TradePayerPort tradePayerPort(ObjectProvider<EnterpriseService> enterprises,
                                  ObjectProvider<AccountClosureService> closures) {
        // 交易模块和业务模块（活动、场馆、租赁）互相依赖，这里延后取，免得构造时成环。
        return buyerId -> {
            AccountViews.Payer payer = enterprises.getObject().payer(buyerId, closures.getObject().isClosing(buyerId));
            return new TradePayerPort.Payer(payer.type(), payer.organizationId(), payer.name(), payer.closing());
        };
    }

    @Bean
    TradeEnterpriseAccess tradeEnterpriseAccess(EnterpriseService enterprises) {
        return new TradeEnterpriseAccess() {
            @Override
            public boolean isActiveMember(UUID accountId, UUID enterpriseId) {
                return enterprises.isActiveMember(accountId, enterpriseId);
            }

            @Override
            public Map<UUID, String> displayNames(List<UUID> accountIds) {
                return enterprises.displayNames(accountIds);
            }
        };
    }

    private static AccountPorts.PersonalDataSource source(
            String code, String title, String description,
            java.util.function.Function<UUID, Long> count,
            java.util.function.Function<UUID, Object> export
    ) {
        return new AccountPorts.PersonalDataSource() {
            @Override
            public String code() {
                return code;
            }

            @Override
            public String title() {
                return title;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public Long count(UUID accountId) {
                return count.apply(accountId);
            }

            @Override
            public Object export(UUID accountId) {
                return export.apply(accountId);
            }
        };
    }

    private static AccountPorts.ClosureCondition condition(
            String code, String title, java.util.function.Function<UUID, String> unmet) {
        return new AccountPorts.ClosureCondition() {
            @Override
            public String code() {
                return code;
            }

            @Override
            public String title() {
                return title;
            }

            @Override
            public String unmetDetail(UUID accountId) {
                return unmet.apply(accountId);
            }
        };
    }
}
