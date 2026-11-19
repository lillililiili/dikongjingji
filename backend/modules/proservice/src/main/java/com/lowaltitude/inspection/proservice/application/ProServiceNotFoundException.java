package com.lowaltitude.inspection.proservice.application;

/** 服务不存在、已下架、不在这个城市上架，或服务商不存在、没有在用的服务商资料。对外是通用的 404。 */
public final class ProServiceNotFoundException extends RuntimeException {

    public ProServiceNotFoundException() {
        super("pro service or provider not found");
    }
}
