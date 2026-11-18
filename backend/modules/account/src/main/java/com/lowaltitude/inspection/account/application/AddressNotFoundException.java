package com.lowaltitude.inspection.account.application;

/** 地址不存在、已删除或不属于当前账号；对外一律 404。 */
public final class AddressNotFoundException extends RuntimeException {
}
