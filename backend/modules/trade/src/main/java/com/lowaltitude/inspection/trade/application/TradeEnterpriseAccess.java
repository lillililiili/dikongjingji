package com.lowaltitude.inspection.trade.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 企业订单列表要问身份模块的两件事（S5-7）：这个账号是不是这家企业的有效成员，以及经办人的显示名。 */
public interface TradeEnterpriseAccess {

    boolean isActiveMember(UUID accountId, UUID enterpriseId);

    Map<UUID, String> displayNames(List<UUID> accountIds);
}
