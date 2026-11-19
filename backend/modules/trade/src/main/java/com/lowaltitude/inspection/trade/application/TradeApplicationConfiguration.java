package com.lowaltitude.inspection.trade.application;

import java.security.SecureRandom;
import java.time.Clock;

import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeOrderMapper;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradePaymentMapper;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeReconciliationMapper;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeRefundMapper;
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

    @Bean
    TradeOrderService tradeOrderService(TradeOrderMapper mapper, TradeCheckoutPort checkout) {
        SecureRandom random = new SecureRandom();
        return new TradeOrderService(
                mapper,
                checkout,
                Clock.systemUTC(),
                () -> String.format("%08d", random.nextInt(100_000_000)));
    }

    /** 没有配置支付渠道（trade.wechat.enabled 不是 true）时 channels 给 null，发起支付、退款回“渠道不可用”。 */
    @Bean
    TradePaymentService tradePaymentService(
            TradePaymentMapper payments,
            TradeOrderMapper orders,
            TradeOrderService orderService,
            ObjectProvider<PaymentChannel> channels,
            PlatformTransactionManager transactionManager,
            TradeRefundService refunds
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
                refunds);
    }

    @Bean
    TradeRefundService tradeRefundService(
            TradeRefundMapper refunds,
            TradeOrderMapper orders,
            TradeOrderService orderService,
            ObjectProvider<PaymentChannel> channels,
            PlatformTransactionManager transactionManager
    ) {
        SecureRandom random = new SecureRandom();
        return new TradeRefundService(
                refunds,
                orders,
                orderService,
                channels::getIfAvailable,
                new TransactionTemplate(transactionManager),
                Clock.systemUTC(),
                () -> twelveDigits(random));
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

    private static String twelveDigits(SecureRandom random) {
        return String.format("%012d", Math.floorMod(random.nextLong(), 1_000_000_000_000L));
    }
}
