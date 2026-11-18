package com.lowaltitude.inspection.account.application;

/** 新增或修改地址时提交的内容。省份由服务端按城市编码补上，客户端不用传。 */
public record AddressForm(
        String recipientName,
        String phone,
        String cityCode,
        String detail,
        Boolean isDefault
) {
}
