package com.lowaltitude.inspection.account.application;

import java.util.Optional;

/**
 * 地区目录：按地级市编码查到它和所属省份。由启动模块用平台城市目录实现，账号模块不直接读那张表。
 */
public interface RegionDirectory {

    Optional<Region> findCity(String cityCode);

    record Region(String provinceCode, String provinceName, String cityCode, String cityName) {
    }
}
