package com.lowaltitude.inspection.rental.application;

import java.time.Clock;

import com.lowaltitude.inspection.rental.infrastructure.persistence.RentalMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 设备租赁（F3）。商城供给、收货地址、交易付款、出租方资质由启动模块接上（RentalPorts）。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
@MapperScan(basePackageClasses = RentalMapper.class)
public class RentalApplicationConfiguration {

    @Bean
    RentalService rentalService(
            RentalMapper mapper,
            RentalPorts.Offers offers,
            RentalPorts.Addresses addresses,
            RentalPorts.Payments payments,
            RentalPorts.Qualifications qualifications
    ) {
        return new RentalService(mapper, offers, addresses, payments, qualifications, Clock.systemUTC());
    }
}
