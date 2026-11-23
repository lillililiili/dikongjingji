package com.lowaltitude.inspection.identity.application.account;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 账号功能用到的外部服务和别的模块，由启动模块接上；本模块不读别的模块的表，也不接任何第三方 SDK。
 */
public final class AccountPorts {

    private AccountPorts() {
    }

    /**
     * 短信发送（S5-8）。开发环境是本机模拟服务（验证码写日志和 identity_dev_sms_outbox），其他环境没配置短信服务商时抛
     * {@link SmsUnavailableException}，不假装发出。content 只含验证码和用途，不含账号信息。
     */
    public interface SmsGateway {

        void send(String phone, String content);
    }

    public static final class SmsUnavailableException extends RuntimeException {

        public SmsUnavailableException(String message) {
            super(message);
        }
    }

    /** 导出文件的受控存储：开发环境本机目录，其他环境没配置时抛 {@link ExportStoreUnavailableException}。 */
    public interface ExportFiles {

        void put(String key, byte[] content);

        byte[] read(String key);

        void delete(String key);
    }

    public static final class ExportStoreUnavailableException extends RuntimeException {

        public ExportStoreUnavailableException(String message) {
            super(message);
        }

        public ExportStoreUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 别的模块保存的个人信息（导出用）：参与人（活动）、收货地址（账号）、订单（交易）。code 是 DataSectionCode。
     * count 是平台保存的条数（单条的为 null），export 是要写进导出文件的内容（会被转成 JSON）。
     */
    public interface PersonalDataSource {

        String code();

        String title();

        String description();

        Long count(UUID accountId);

        Object export(UUID accountId);
    }

    /** 注销前必须满足的条件（没有进行中的订单、没有待参加的报名）。detail 是没满足时说明还差什么。 */
    public interface ClosureCondition {

        String code();

        String title();

        /** 为空表示满足；否则是给人看的说明，如“2 笔订单未完成”。 */
        String unmetDetail(UUID accountId);
    }

    /** 注销到期时去掉别的模块里能对应到人的资料（删除收货地址、把订单配送快照里的姓名和电话打码等），业务记录保留。 */
    public interface PersonalDataEraser {

        void erase(UUID accountId);
    }

    /** 把导出内容写成 JSON（启动模块的 ObjectMapper；本模块不直接依赖 JSON 库）。 */
    public interface JsonWriter {

        byte[] write(Object value);
    }

    /** 平台城市目录：名称。 */
    public interface CityNames {

        Map<String, String> names(List<String> cityCodes);
    }
}
