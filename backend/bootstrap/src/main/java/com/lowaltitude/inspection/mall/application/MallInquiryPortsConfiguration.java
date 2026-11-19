package com.lowaltitude.inspection.mall.application;

import com.lowaltitude.inspection.trade.application.TradeSourceOrders;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 询价（F4）要知道商家能不能在线收款：接到统一交易模块的收款账户登记。商城不读交易的表。
 * 交易模块的提交订单反过来依赖商城的结算（按报价出价），所以这里第一次用到时才取交易模块的入口，不在构造时取，免得成环。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class MallInquiryPortsConfiguration {

    @Bean
    MallInquiryPorts.MerchantPayments mallInquiryMerchantPayments(ObjectProvider<TradeSourceOrders> orders) {
        return organizationId -> orders.getObject().merchantCanCollect(organizationId);
    }
}
