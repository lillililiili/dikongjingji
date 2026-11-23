package com.lowaltitude.inspection.application.serviceflow;

import java.util.function.Supplier;

/**
 * 在一个数据库事务里跑一段代码（S4-1）。付款和取消要先在事务外调交易模块（关单、查单要调渠道），再在事务里改服务单，
 * 所以不能整个方法加 {@code @Transactional}。启动配置里用 TransactionTemplate 实现，测试里直接调用。
 */
@FunctionalInterface
public interface InTransaction {

    <T> T call(Supplier<T> work);
}
