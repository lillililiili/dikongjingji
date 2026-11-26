package com.lowaltitude.inspection.identity.application.account;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 本机模拟短信（S5-8，只在 identity.sms.provider = fake 时装配，application-dev.yml 打开）：不发真实短信，
 * 把内容写入 identity_dev_sms_outbox，开发与 E2E 从本地发件箱读取。
 */
public final class FakeSmsGateway implements AccountPorts.SmsGateway {

    private static final Logger LOG = LoggerFactory.getLogger(FakeSmsGateway.class);

    private final AccountMapper mapper;
    private final Clock clock;

    public FakeSmsGateway(AccountMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public void send(String phone, String content) {
        mapper.insertDevSms(phone, content, OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC));
        LOG.info("[fake-sms] queued for {}", PhoneNumbers.mask(phone));
    }

    /** 没有配置短信服务商时：一律不可用（发送验证码返回 503），不假装发出。 */
    public static final class Unavailable implements AccountPorts.SmsGateway {

        @Override
        public void send(String phone, String content) {
            throw new AccountPorts.SmsUnavailableException("sms provider is not configured");
        }
    }
}
