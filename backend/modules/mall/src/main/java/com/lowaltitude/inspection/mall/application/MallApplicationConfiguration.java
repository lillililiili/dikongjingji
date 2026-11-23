package com.lowaltitude.inspection.mall.application;

import java.time.Clock;

import com.lowaltitude.inspection.mall.infrastructure.persistence.MallCartMapper;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallCatalogOpsMapper;
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

    /** 运营端“商品与供给”（S3-1）：城市、商家、图片存储的端口由启动模块接上。 */
    @Bean
    MallCatalogOpsService mallCatalogOpsService(MallCatalogOpsMapper mapper, MallOpsPorts.Cities cities,
                                                MallOpsPorts.Merchants merchants,
                                                MallInquiryPorts.MerchantPayments payments,
                                                MallOpsPorts.MediaStore media) {
        return new MallCatalogOpsService(mapper, cities, merchants, payments, media, Clock.systemUTC());
    }

    /** 商家主页与商家资料（S5-2）。活动、内容由启动模块经端口接上；没接时两个页签是空的。 */
    @Bean
    MallMerchantService mallMerchantService(
            com.lowaltitude.inspection.mall.infrastructure.persistence.MallMerchantMapper mapper,
            MallCatalogMapper catalog,
            MallInquiryPorts.MerchantQualifications qualifications,
            org.springframework.beans.factory.ObjectProvider<MallMerchantPorts.Activities> activities,
            org.springframework.beans.factory.ObjectProvider<MallMerchantPorts.Contents> contents,
            org.springframework.transaction.PlatformTransactionManager transactionManager
    ) {
        return new MallMerchantService(mapper, catalog, qualifications,
                activities.getIfAvailable(() -> MallMerchantPorts.Activities.NONE),
                contents.getIfAvailable(() -> MallMerchantPorts.Contents.NONE),
                new org.springframework.transaction.support.TransactionTemplate(transactionManager),
                Clock.systemUTC());
    }

    @Bean
    MallRentalOffers mallRentalOffers(MallCartMapper mapper) {
        return new MallRentalOffers(mapper);
    }
}
