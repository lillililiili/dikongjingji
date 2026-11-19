package com.lowaltitude.inspection.trade.infrastructure.wechat;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Clock;
import java.util.Base64;
import java.util.function.Supplier;

/**
 * 用服务商 API 证书私钥签名（SHA256withRSA，Base64）。
 *
 * <p>请求签名串 5 行，每行以 \n 结尾（含最后一行）：方法、去掉域名的路径（带查询串）、秒级时间戳、随机串、请求体
 * （GET 没有请求体时这一行是空串）。Authorization：{@code WECHATPAY2-SHA256-RSA2048 mchid="",nonce_str="",
 * signature="",timestamp="",serial_no=""}，mchid 是发起请求的服务商号。
 *
 * <p>App 调起支付签名串 4 行：appId、时间戳、随机串、prepay_id，每行以 \n 结尾，用下单时的同一张证书私钥。
 */
final class WechatPaySigner {

    static final String SCHEMA = "WECHATPAY2-SHA256-RSA2048";

    private final String mchid;
    private final String serialNo;
    private final PrivateKey privateKey;
    private final Clock clock;
    private final Supplier<String> nonces;

    WechatPaySigner(String mchid, String serialNo, PrivateKey privateKey, Clock clock, Supplier<String> nonces) {
        this.mchid = mchid;
        this.serialNo = serialNo;
        this.privateKey = privateKey;
        this.clock = clock;
        this.nonces = nonces;
    }

    /** @param pathAndQuery 例如 {@code /v3/pay/partner/transactions/out-trade-no/X?sp_mchid=1&sub_mchid=2}，必须和实际请求的一字不差 */
    String authorization(String method, String pathAndQuery, String body) {
        String timestamp = Long.toString(clock.instant().getEpochSecond());
        String nonce = nonces.get();
        String message = method + "\n" + pathAndQuery + "\n" + timestamp + "\n" + nonce + "\n"
                + (body == null ? "" : body) + "\n";
        return SCHEMA + " mchid=\"" + mchid + "\",nonce_str=\"" + nonce + "\",signature=\"" + sign(message)
                + "\",timestamp=\"" + timestamp + "\",serial_no=\"" + serialNo + "\"";
    }

    AppSignature signAppPay(String appId, String prepayId) {
        String timestamp = Long.toString(clock.instant().getEpochSecond());
        String nonce = nonces.get();
        String message = appId + "\n" + timestamp + "\n" + nonce + "\n" + prepayId + "\n";
        return new AppSignature(timestamp, nonce, sign(message));
    }

    record AppSignature(String timeStamp, String nonceStr, String sign) {
    }

    String sign(String message) {
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(privateKey);
            signer.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("could not sign the wechat pay request", exception);
        }
    }
}
