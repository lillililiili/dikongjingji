package com.lowaltitude.inspection.application.reservation;

import java.util.UUID;

/** 已选报价和需求原文。只有能读取这笔预约的主体可见。 */
public record InspectionDemandSource(UUID demandId, String title, UUID quoteId, int quoteVersion,
                                     UUID providerId, String providerName, long amountMinor, String summary,
                                     String cityCode, String address, String scope) { }
