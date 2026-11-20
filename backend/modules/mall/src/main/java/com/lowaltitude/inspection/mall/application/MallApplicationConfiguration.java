package com.lowaltitude.inspection.mall.application;

import java.time.Clock;

import com.lowaltitude.inspection.mall.infrastructure.persistence.MallCartMapper;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallCatalogMapper;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallInquiryMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
@MapperScan(basePackageClasses = MallCatalogMapper.class)
public class MallApplicationConfiguration {

    @Bean
    MallCatalogReadService mallCatalogReadService(MallCatalogMapper mapper,
                                                  MallInquiryPorts.MerchantPayments payments) {
        return new MallCatalogReadService(new PostgresMallCatalogReadStore(mapper), payments);
    }

    @Bean
    MallCartService mallCartService(MallCartMapper mapper, MallInquiryService inquiries) {
        return new MallCartService(mapper, Clock.systemUTC(), inquiries::orderQuote);
    }

    @Bean
    MallInquiryService mallInquiryService(MallInquiryMapper mapper, MallInquiryPorts.MerchantPayments payments,
                                          MallInquiryPorts.MerchantQualifications qualifications) {
        return new MallInquiryService(mapper, payments, qualifications, Clock.systemUTC());
    }

    @Bean
    MallRentalOffers mallRentalOffers(MallCartMapper mapper) {
        return new MallRentalOffers(mapper);
    }
}
