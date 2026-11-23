package com.lowaltitude.inspection.opsimport.application;

/**
 * 能导入的几类，按导入顺序（后面的表引用前面的编号）。writable 为 false 的这一版只做预检（契约 OpsImportKindSpec.writable）。
 */
public enum OpsImportKind {
    ORGANIZATIONS("经营主体与操作员", true),
    MERCHANT_ACCOUNTS("商家收款账户", true),
    MALL("商品与供给", true),
    ACTIVITIES("活动与场次", false),
    VENUES("场馆与项目", false),
    SERVICES("巡检和专业服务", false),
    CONTENT("发现内容", false);

    private final String title;
    private final boolean writable;

    OpsImportKind(String title, boolean writable) {
        this.title = title;
        this.writable = writable;
    }

    public String title() {
        return title;
    }

    public boolean writable() {
        return writable;
    }
}
