package com.lowaltitude.inspection.trade.application;

import java.security.SecureRandom;
import java.time.Clock;

import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeOrderMapper;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradePaymentMapper;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeReconciliationMapper;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeRefundMapper;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeSourceMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
@MapperScan(basePackageClasses = TradeOrderMapper.class)
public class TradeApplicationConfiguration {

    /**
     * 业务来源（E09 收费报名等）的付款和关闭通知。监听者和退款服务都在第一次用到时才取：
     * 业务模块的监听者依赖它自己的服务，那个服务又通过 {@link TradeSourceOrders} 依赖交易模块，构造时取会成环。
     */
    @Bean
    TradeSourceEvents tradeSourceEvents(
            ObjectProvider<TradeSourceListener> listeners,
            TradeSourceMapper sources,
            ObjectProvider<TradeRefundService> refunds
    ) {
        return new TradeSourceEvents(
                () -> listeners.orderedStream().toList(),
                sources,
                refunds::getObject,
                AfterCommit.spring());
    }

    /** 发货、可以自提、退款到账告诉买家（R6-5）：启动模块接到站内信；没接时什么都不做。 */
    @Bean
    TradeBuyerEvents tradeBuyerEvents(TradeOrderMapper orders, ObjectProvider<TradeBuyerNotifier> notifier) {
        return new TradeBuyerEvents(orders, notifier.getIfAvailable(TradeBuyerNotifier::none));
    }

    /**
     * 运营端收款账户登记（S3-1）。trade.merchant-accounts.allow-placeholder-sub-mchid 只在 application-dev.yml 打开：
     * 开发种子借微信文档示例号段当占位，其他环境不接受这个号段。
     */
    @Bean
    OpsMerchantAccountService opsMerchantAccountService(
            com.lowaltitude.inspection.trade.infrastructure.persistence.TradeMerchantAccountMapper mapper,
            @org.springframework.beans.factory.annotation.Value(
                    "${trade.merchant-accounts.allow-placeholder-sub-mchid:false}") boolean allowPlaceholder
    ) {
        return new OpsMerchantAccountService(mapper, allowPlaceholder, Clock.systemUTC());
    }

    @Bean
    TradeOrderService tradeOrderService(
            TradeOrderMapper mapper,
            TradeCheckoutPort checkout,
            TradeSourceEvents events,
            TradeBuyerEvents buyerEvents
    ) {
        SecureRandom random = new SecureRandom();
        return new TradeOrderService(
                mapper,
                checkout,
                Clock.systemUTC(),
                () -> String.format("%08d", random.nextInt(100_000_000)),
                events,
                buyerEvents);
    }

    @Bean
    TradeSourceOrders tradeSourceOrders(
            TradeSourceMapper sources,
            TradeOrderMapper orders,
            TradeOrderService orderService,
            TradePaymentService payments,
            TradeRefundService refunds
    ) {
        return new TradeSourceOrders(sources, orders, orderService, payments, refunds, AfterCommit.spring(),
                Clock.systemUTC());
    }

    /** 没有配置支付渠道（trade.wechat.enabled 不是 true）时 channels 给 null，发起支付、退款回“渠道不可用”。 */
    @Bean
    TradePaymentService tradePaymentService(
            TradePaymentMapper payments,
            TradeOrderMapper orders,
            TradeOrderService orderService,
            ObjectProvider<PaymentChannel> channels,
            PlatformTransactionManager transactionManager,
            TradeRefundService refunds,
            TradeSourceEvents events,
            TradeBuyerEvents buyerEvents
    ) {
        SecureRandom random = new SecureRandom();
        return new TradePaymentService(
                payments,
                orders,
                orderService,
                channels::getIfAvailable,
                new TransactionTemplate(transactionManager),
                Clock.systemUTC(),
                () -> twelveDigits(random),
                // 取货码 6 位数字，不可预测。
                () -> String.format("%06d", random.nextInt(1_000_000)),
                refunds,
                events,
                buyerEvents);
    }

    @Bean
    TradeRefundService tradeRefundService(
            TradeRefundMapper refunds,
            TradeOrderMapper orders,
            TradeOrderService orderService,
            ObjectProvider<PaymentChannel> channels,
            PlatformTransactionManager transactionManager,
            TradeBuyerEvents buyerEvents,
            ObjectProvider<TradeAftersaleService> aftersales
    ) {
        SecureRandom random = new SecureRandom();
        TradeRefundService service = new TradeRefundService(
                refunds,
                orders,
                orderService,
                channels::getIfAvailable,
                new TransactionTemplate(transactionManager),
                Clock.systemUTC(),
                () -> twelveDigits(random),
                buyerEvents);
        // 退款到账或结束时推进售后单（S4-2）；售后服务依赖退款服务，所以第一次用到时才取。
        service.useSettlements(() -> {
            TradeAftersaleService aftersale = aftersales.getIfAvailable();
            return aftersale == null ? TradeRefundService.RefundSettlement.NONE : aftersale;
        });
        return service;
    }

    /** 商品售后（S4-2）。凭证图片的对象存储由启动模块接；没接时上传回 503。 */
    @Bean
    TradeAftersaleService tradeAftersaleService(
            com.lowaltitude.inspection.trade.infrastructure.persistence.TradeAftersaleMapper aftersales,
            TradeRefundMapper refunds,
            TradeRefundService refundService,
            ObjectProvider<TradeAftersaleImageStore> images,
            PlatformTransactionManager transactionManager
    ) {
        SecureRandom random = new SecureRandom();
        return new TradeAftersaleService(
                aftersales,
                refunds,
                refundService,
                images.getIfAvailable(TradeAftersaleImageStore::unavailable),
                new TransactionTemplate(transactionManager),
                Clock.systemUTC(),
                () -> String.format("%08d", random.nextInt(100_000_000)));
    }

    @Bean
    TradeReconciliationService tradeReconciliationService(
            TradeReconciliationMapper reconciliations,
            ObjectProvider<PaymentChannel> channels,
            PlatformTransactionManager transactionManager
    ) {
        return new TradeReconciliationService(
                reconciliations,
                channels::getIfAvailable,
                new TransactionTemplate(transactionManager),
                Clock.systemUTC());
    }

    /** 支付通道自检（S2-1）。配置自检由接渠道的一方给；没接时只说“没有开启”。 */
    @Bean
    TradeChannelCheckService tradeChannelCheckService(
            ObjectProvider<PaymentChannel> channels,
            ObjectProvider<PaymentChannelDiagnostics> diagnostics
    ) {
        SecureRandom random = new SecureRandom();
        return new TradeChannelCheckService(
                channels::getIfAvailable,
                diagnostics.getIfAvailable(PaymentChannelDiagnostics::none),
                Clock.systemUTC(),
                () -> System.currentTimeMillis() + twelveDigits(random).substring(5));
    }

    private static String twelveDigits(SecureRandom random) {
        return String.format("%012d", Math.floorMod(random.nextLong(), 1_000_000_000_000L));
    }
}
