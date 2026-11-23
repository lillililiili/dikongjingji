package com.lowaltitude.inspection.favorite.application;

import java.util.List;

import com.lowaltitude.inspection.favorite.infrastructure.persistence.FavoriteMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 收藏（计划 08 S5-6）。每类对象的读取由启动模块里各模块的包接上（FavoritePorts.TargetReader）。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
@MapperScan(basePackageClasses = FavoriteMapper.class)
public class FavoriteApplicationConfiguration {

    @Bean
    FavoriteService favoriteService(FavoriteMapper mapper, List<FavoritePorts.TargetReader> readers) {
        return new FavoriteService(mapper, readers);
    }
}
