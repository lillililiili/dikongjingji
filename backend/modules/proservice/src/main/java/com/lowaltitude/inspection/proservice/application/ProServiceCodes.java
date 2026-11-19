package com.lowaltitude.inspection.proservice.application;

import java.util.List;

// 专业服务的稳定编码（契约 contracts/openapi-proservice.yaml）。中文名只用于展示，不作业务判断。

/** 四类专业服务。编码和需求（F1 的 DemandType）一致，详情页“发布需求”可以直接预选。 */
enum ProServiceType {
    AERIAL("航拍", "成果类型", List.of(ProServiceFacet.VIDEO_FOOTAGE, ProServiceFacet.PHOTOS)),
    SURVEY("测绘", "成果类型", List.of(ProServiceFacet.ORTHOPHOTO, ProServiceFacet.MODEL_3D)),
    MAINTENANCE("设备维护", "服务方式", List.of(ProServiceFacet.ON_SITE, ProServiceFacet.MAIL_IN)),
    TECH_SUPPORT("技术支持", "支持方式", List.of(ProServiceFacet.REMOTE, ProServiceFacet.STATIONED));

    private final String label;
    private final String facetLabel;
    private final List<ProServiceFacet> facets;

    ProServiceType(String label, String facetLabel, List<ProServiceFacet> facets) {
        this.label = label;
        this.facetLabel = facetLabel;
        this.facets = facets;
    }

    String label() {
        return label;
    }

    /** 目录筛选弹层第一组的标题（“成果类型”“服务方式”“支持方式”）。 */
    String facetLabel() {
        return facetLabel;
    }

    /** 这一类的筛选项，按页面顺序。 */
    List<ProServiceFacet> facets() {
        return facets;
    }
}

/** 目录筛选项：航拍、测绘按成果类型，设备维护、技术支持按服务方式。成果类型的编码和 F1 的交付物一致。 */
enum ProServiceFacet {
    VIDEO_FOOTAGE("视频素材"),
    PHOTOS("照片"),
    ORTHOPHOTO("正射影像"),
    MODEL_3D("三维模型"),
    ON_SITE("上门"),
    MAIL_IN("送修"),
    REMOTE("远程"),
    STATIONED("驻场");

    private final String label;

    ProServiceFacet(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }
}

/** 场景分类：没有授权照片时 App 按它画场景图标。 */
enum ProServiceCategory {
    ENGINEERING("工程"),
    ENERGY("能源"),
    TOURISM("文旅"),
    EVENT("活动"),
    MODELING("建模"),
    PARK("园区"),
    TERRAIN("地形"),
    CORRIDOR("线路"),
    GENERAL("综合"),
    DRONE("无人机"),
    ROBOT("机器人"),
    ROBOT_DOG("机器狗"),
    FLIGHT("飞行"),
    SOFTWARE("软件");

    private final String label;

    ProServiceCategory(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }
}

/** PRICE_FROM 起步价；PROJECT 按项目报价（方案确认后定价）。 */
enum ProServicePriceMode {
    PRICE_FROM,
    PROJECT
}

/** DEFAULT 综合；PRICE_ASC、PRICE_DESC 按起步价，按项目报价的总在最后。 */
enum ProServiceSort {
    DEFAULT,
    PRICE_ASC,
    PRICE_DESC
}
