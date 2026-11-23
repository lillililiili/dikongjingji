package com.lowaltitude.inspection.application.serviceflow;

import java.time.Clock;
import java.util.function.Supplier;

import com.lowaltitude.inspection.application.reservation.AesGcmSensitiveDataCipher;
import com.lowaltitude.inspection.domain.files.ObjectStoragePort;
import com.lowaltitude.inspection.infrastructure.persistence.InspectionPaymentMapper;
import com.lowaltitude.inspection.infrastructure.persistence.ServiceFlowPersistenceMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class PlanApplicationConfiguration {

    /** 交易模块没接上时（测试、没有交易模块的部署）用“不能在线收款”的端口。 */
    private static Supplier<InspectionPaymentPort> paymentPort(ObjectProvider<InspectionPaymentPort> port) {
        return () -> port.getIfAvailable(InspectionPaymentPort::unavailable);
    }

    @Bean
    ServiceOrderChangeStore serviceOrderChangeStore(
            InspectionPaymentMapper paymentMapper,
            ServiceFlowPersistenceMapper mapper,
            ObjectProvider<InspectionPaymentPort> payments
    ) {
        return new PostgresServiceOrderChangeStore(paymentMapper, mapper, paymentPort(payments));
    }

    @Bean
    PlanStore planStore(
            ServiceFlowPersistenceMapper mapper,
            ServiceOrderChangeStore changes,
            @Value("${INSPECTION_DATA_ENCRYPTION_BASE64_KEY}") String encryptionKey
    ) {
        return new PostgresPlanStore(
                mapper,
                new AesGcmSensitiveDataCipher(encryptionKey),
                changes);
    }

    @Bean
    PlanApplicationService planApplicationService(
            PlanStore store,
            ObjectProvider<InspectionPaymentPort> payments,
            @Value("${inspection.payment.nodes-enabled:false}") boolean paymentNodesEnabled
    ) {
        return new PlanApplicationService(store, Clock.systemUTC(), paymentNodesEnabled,
                new LazyPaymentPort(paymentPort(payments)));
    }

    @Bean
    InspectionPaymentService inspectionPaymentService(
            PlanStore store,
            ServiceOrderChangeStore changes,
            ObjectProvider<InspectionPaymentPort> payments,
            PlatformTransactionManager transactions
    ) {
        TransactionTemplate template = new TransactionTemplate(transactions);
        return new InspectionPaymentService(store, changes, paymentPort(payments), new InTransaction() {
            @Override
            public <T> T call(Supplier<T> work) {
                return template.execute(status -> work.get());
            }
        }, Clock.systemUTC());
    }

    @Bean
    VisitChangeService visitChangeService(
            PlanStore store,
            DeliveryStore deliveries,
            ServiceOrderChangeStore changes
    ) {
        return new VisitChangeService(store, deliveries, changes, Clock.systemUTC());
    }

    @Bean
    OrderApplicationService orderApplicationService(PlanStore store) {
        return new OrderApplicationService(store, Clock.systemUTC());
    }

    @Bean
    DeliveryStore deliveryStore(ServiceFlowPersistenceMapper mapper, InspectionPaymentMapper paymentMapper) {
        return new PostgresDeliveryStore(mapper, paymentMapper);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "inspection.infrastructure.storage",
            name = "enabled",
            havingValue = "true")
    DeliveryApplicationService deliveryApplicationService(
            PlanStore orderStore,
            DeliveryStore deliveryStore,
            ObjectStoragePort storage,
            @Value("${INSPECTION_DATA_ENCRYPTION_BASE64_KEY}") String encryptionKey
    ) {
        return new DeliveryApplicationService(
                orderStore,
                deliveryStore,
                storage,
                new AesGcmSensitiveDataCipher(encryptionKey),
                Clock.systemUTC());
    }
}

/**
 * 每次用时才取交易端口：交易模块的端口 bean 依赖巡检付款服务（回调），方案服务先建好也不会循环依赖。
 */
final class LazyPaymentPort implements InspectionPaymentPort {

    private final Supplier<InspectionPaymentPort> delegate;

    LazyPaymentPort(Supplier<InspectionPaymentPort> delegate) {
        this.delegate = delegate;
    }

    @Override
    public boolean merchantCanCollect(java.util.UUID organizationId) {
        return delegate.get().merchantCanCollect(organizationId);
    }

    @Override
    public OpenedPayment open(OpenPayment request) {
        return delegate.get().open(request);
    }

    @Override
    public void lockOrder(java.util.UUID tradeOrderId) {
        delegate.get().lockOrder(tradeOrderId);
    }

    @Override
    public java.util.UUID requestRefund(java.util.UUID tradeOrderId, long amountMinor, String reason,
                                        java.util.UUID idempotencyKey, java.util.UUID actorId) {
        return delegate.get().requestRefund(tradeOrderId, amountMinor, reason, idempotencyKey, actorId);
    }

    @Override
    public java.util.Map<java.util.UUID, RefundProgress> refundStates(
            java.util.Collection<java.util.UUID> refundIds) {
        return delegate.get().refundStates(refundIds);
    }

    @Override
    public boolean cancelUnpaid(java.util.UUID accountId, java.util.UUID tradeOrderId) {
        return delegate.get().cancelUnpaid(accountId, tradeOrderId);
    }
}
