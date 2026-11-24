package com.lowaltitude.inspection.experience.application;

import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.application.city.CityOperationsService;
import com.lowaltitude.inspection.experience.application.ops.VenueOpsPorts;
import com.lowaltitude.inspection.identity.domain.IdentityOrganization;
import com.lowaltitude.inspection.identity.domain.IdentityOrganizationPort;
import com.lowaltitude.inspection.mall.application.MallCatalogReadService;
import com.lowaltitude.inspection.mall.application.MallCatalogReadService.MallOfferSummary;
import com.lowaltitude.inspection.mall.application.MallProductNotFoundException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 运营端“场馆与设备”（S3-1）的端口：城市接巡检模块的平台城市目录，运营机构接身份模块（在用的服务商机构），
 * 设备关联的商品接商城的商品详情（在场馆所在城市在售才算有）。体验模块不读这几个模块的表。
 * S4-4 起，App 馆内设备页的商品价格和“N 家在售”也在这里接商城（{@link VenueDeviceOfferPort}）。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class VenueOpsPortsConfiguration {

    static final String PROVIDER = "PROVIDER";

    @Bean
    VenueOpsPorts.Cities venueOpsCities(CityOperationsService cities) {
        return cities::cityName;
    }

    @Bean
    VenueOpsPorts.Operators venueOpsOperators(IdentityOrganizationPort organizations) {
        return new VenueOpsPorts.Operators() {
            @Override
            public Optional<String> providerName(UUID organizationId) {
                return organizations.findActiveOrganization(organizationId)
                        .filter(organization -> PROVIDER.equals(organization.organizationType()))
                        .map(IdentityOrganization::name);
            }

            @Override
            public boolean listingSuspended(UUID organizationId) {
                return organizations.findActiveOrganization(organizationId)
                        .map(IdentityOrganization::listingSuspended)
                        .orElse(false);
            }
        };
    }

    @Bean
    VenueOpsPorts.Products venueOpsProducts(MallCatalogReadService mall) {
        return (productId, cityCode) -> {
            try {
                mall.findProduct(productId, cityCode);
                return true;
            } catch (MallProductNotFoundException | IllegalArgumentException missing) {
                return false;
            }
        };
    }

    @Bean
    VenueDeviceOfferPort venueDeviceOffers(MallCatalogReadService mall) {
        return (productId, cityCode) -> mall.findOfferSummary(productId, cityCode).map(
                VenueOpsPortsConfiguration::toDeviceOffer);
    }

    static VenueDeviceOfferPort.VenueDeviceOffer toDeviceOffer(MallOfferSummary summary) {
        return new VenueDeviceOfferPort.VenueDeviceOffer(
                VenueDeviceOfferPort.SaleMode.valueOf(summary.saleMode().name()),
                summary.fromAmountMinor(),
                VenueDeviceOfferPort.PriceUnit.valueOf(summary.priceUnit().name()),
                summary.multiplePrices(),
                summary.sellerCount());
    }
}
