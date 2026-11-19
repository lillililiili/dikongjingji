package com.lowaltitude.inspection.trade.application;

import java.util.UUID;

/**
 * 业务模块接收自己那类订单的付款和关闭（E09，docs/redesign/03-App实现任务计划.md §20.2）。
 * 两个方法都在交易模块改订单状态的同一个事务里调用，交易订单已经锁住：业务模块要锁的行排在它后面。
 * 抛异常会让整个事务回滚（回调回失败，渠道会重发；定时任务下一轮再试），所以预料之中的情况不要抛。
 */
public interface TradeSourceListener {

    /** 订单来源，如 ACTIVITY。 */
    String source();

    /**
     * 订单刚变成已付款（包括关闭之后才到账、重新打开成已付款的）。
     *
     * @return false 表示业务这边收不下（比如报名名额已经释放、场次已经开始），交易模块随即全额原路退款
     */
    boolean onPaid(UUID orderId, UUID sourceRef);

    /** 待付款的订单关闭了（买家取消或超时）。同一张单只会关一次，但实现仍应幂等。 */
    void onClosed(UUID orderId, UUID sourceRef);
}
