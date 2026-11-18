package com.lowaltitude.inspection.account.application;

import java.util.UUID;

/** 一条收货地址。只返回给地址的主人，所以手机号是完整的；页面显示时打码。 */
public record AddressView(
        UUID addressId,
        String recipientName,
        String phone,
        AddressRegionView region,
        String detail,
        boolean isDefault
) {
}
