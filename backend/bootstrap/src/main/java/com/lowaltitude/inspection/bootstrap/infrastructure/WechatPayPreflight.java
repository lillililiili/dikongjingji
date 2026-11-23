package com.lowaltitude.inspection.bootstrap.infrastructure;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.lowaltitude.inspection.trade.application.PaymentChannelDiagnostics;
import com.lowaltitude.inspection.trade.application.TradeChannelCheckService;
import com.lowaltitude.inspection.trade.application.TradeViews.ChannelCheckItemView;
import com.lowaltitude.inspection.trade.infrastructure.wechat.WechatPayCredentials;

/**
 * 微信支付配置的逐项自检（S2-1）。启动时（只在真实微信模式）和支付通道自检接口都用它。
 *
 * <p>一次列出所有问题，而不是碰到第一个就停：联调时省得改一项、重启一次。每一项的消息只说哪里不对、该怎么改，
 * 不带密钥、文件内容和服务器路径（路径本身也透露服务器布局）。
 *
 * <p>“模拟模式”：接口域名是本机回环上的 http（infra/dev/fake-wechat 写出的配置就是这样）。模拟模式下回调地址可以是
 * 本机 http，商户号、APPID 按模拟服务的固定值，只做格式检查。
 */
public final class WechatPayPreflight {

    static final String CALLBACK_PATH = "/api/v1/trade/channels/wechat/notifications";
    private static final Set<String> OFFICIAL_HOSTS = Set.of("api.mch.weixin.qq.com", "api2.mch.weixin.qq.com");
    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "localhost", "[::1]", "::1");
    private static final int MIN_RSA_BITS = 2048;

    /** 每一项对应的配置属性，启动失败时写在项目代号后面，照着就知道改哪个环境变量。 */
    static final Map<String, String> PROPERTIES = Map.ofEntries(
            Map.entry("ENABLED", "trade.wechat.enabled"),
            Map.entry("API_BASE", "trade.wechat.api-base"),
            Map.entry("SP_MCHID", "trade.wechat.sp-mchid"),
            Map.entry("SP_APPID", "trade.wechat.sp-appid"),
            Map.entry("MERCHANT_SERIAL_NO", "trade.wechat.merchant-serial-no"),
            Map.entry("MERCHANT_PRIVATE_KEY", "trade.wechat.merchant-private-key-path"),
            Map.entry("MERCHANT_PRIVATE_KEY_FILE", "trade.wechat.merchant-private-key-path"),
            Map.entry("API_V3_KEY", "trade.wechat.api-v3-key"),
            Map.entry("PLATFORM_PUBLIC_KEY_ID", "trade.wechat.platform-public-key-id"),
            Map.entry("PLATFORM_PUBLIC_KEY", "trade.wechat.platform-public-key-path"),
            Map.entry("KEYS_DISTINCT", "trade.wechat.platform-public-key-path"),
            Map.entry("PAYMENT_NOTIFY_URL", "trade.wechat.payment-notify-url"),
            Map.entry("REFUND_NOTIFY_URL", "trade.wechat.refund-notify-url"));

    private WechatPayPreflight() {
    }

    /** 配置开着、接口域名是本机回环上的 http：指向本机模拟微信。 */
    public static boolean simulated(TradeWechatPayProperties properties) {
        URI base = properties.apiBase();
        return base != null && "http".equals(base.getScheme()) && base.getHost() != null
                && LOOPBACK.contains(base.getHost().toLowerCase(Locale.ROOT));
    }

    public static String mode(TradeWechatPayProperties properties) {
        if (!properties.enabled()) {
            return PaymentChannelDiagnostics.MODE_DISABLED;
        }
        return simulated(properties) ? PaymentChannelDiagnostics.MODE_SIMULATED : PaymentChannelDiagnostics.MODE_WECHAT;
    }

    public static List<ChannelCheckItemView> inspect(TradeWechatPayProperties properties) {
        List<ChannelCheckItemView> items = new ArrayList<>();
        if (!properties.enabled()) {
            items.add(fail("ENABLED", "微信支付没有开启（TRADE_WECHAT_ENABLED 不是 true）"));
            return items;
        }
        boolean simulated = simulated(properties);
        items.add(simulated
                ? warn("ENABLED", "已开启，指向本机模拟微信：只能用于开发机，不能用于生产")
                : pass("ENABLED", "已开启，指向真实微信支付"));
        items.add(apiBase(properties.apiBase(), simulated));
        items.add(spMchid(properties.spMchid(), simulated));
        items.add(spAppid(properties.spAppid()));
        items.add(serialNo(properties.merchantSerialNo()));

        Path privatePath = properties.merchantPrivateKeyPath();
        PrivateKey privateKey = null;
        ChannelCheckItemView privateItem;
        if (privatePath == null) {
            privateItem = fail("MERCHANT_PRIVATE_KEY", "没有配置 API 证书私钥文件（TRADE_WECHAT_MERCHANT_PRIVATE_KEY_PATH）");
        } else {
            try {
                privateKey = WechatPayCredentials.parsePrivateKey(read(privatePath));
                privateItem = privateKey instanceof RSAPrivateCrtKey key && key.getModulus().bitLength() >= MIN_RSA_BITS
                        ? pass("MERCHANT_PRIVATE_KEY", "API 证书私钥能解析（RSA "
                                + ((RSAPrivateCrtKey) privateKey).getModulus().bitLength() + " 位，PKCS#8）")
                        : fail("MERCHANT_PRIVATE_KEY", "API 证书私钥不是 2048 位以上的 RSA 私钥");
            } catch (IOException exception) {
                privateItem = fail("MERCHANT_PRIVATE_KEY", "API 证书私钥文件读不到（不存在或运行后端的用户没有读权限）");
            } catch (IllegalStateException exception) {
                privateItem = fail("MERCHANT_PRIVATE_KEY", "API 证书私钥解析失败：要用商户平台下载的 apiclient_key.pem"
                        + "（-----BEGIN PRIVATE KEY-----，PKCS#8），不是证书文件 apiclient_cert.pem，也不是 PKCS#1");
            }
        }
        items.add(privateItem);
        if (privatePath != null && !simulated) {
            items.add(fileMode("MERCHANT_PRIVATE_KEY_FILE", privatePath, "API 证书私钥文件"));
        }

        items.add(apiV3Key(properties.apiV3Key()));
        items.add(publicKeyId(properties.platformPublicKeyId()));

        Path publicPath = properties.platformPublicKeyPath();
        PublicKey publicKey = null;
        if (publicPath == null) {
            items.add(fail("PLATFORM_PUBLIC_KEY", "没有配置微信支付公钥文件（TRADE_WECHAT_PLATFORM_PUBLIC_KEY_PATH）"));
        } else {
            try {
                publicKey = WechatPayCredentials.parsePublicKey(read(publicPath));
                items.add(publicKey instanceof RSAPublicKey key && key.getModulus().bitLength() >= MIN_RSA_BITS
                        ? pass("PLATFORM_PUBLIC_KEY", "微信支付公钥能解析")
                        : fail("PLATFORM_PUBLIC_KEY", "微信支付公钥不是 2048 位以上的 RSA 公钥"));
            } catch (IOException exception) {
                items.add(fail("PLATFORM_PUBLIC_KEY", "微信支付公钥文件读不到（不存在或运行后端的用户没有读权限）"));
            } catch (IllegalStateException exception) {
                items.add(fail("PLATFORM_PUBLIC_KEY", "微信支付公钥解析失败：要用商户平台“API 安全”里下载的 pub_key.pem"
                        + "（-----BEGIN PUBLIC KEY-----）"));
            }
        }
        if (privateKey instanceof RSAPrivateCrtKey privateRsa && publicKey instanceof RSAPublicKey publicRsa) {
            BigInteger modulus = privateRsa.getModulus();
            items.add(modulus.equals(publicRsa.getModulus())
                    ? fail("KEYS_DISTINCT", "微信支付公钥和 API 证书私钥是一对：公钥文件放错了，应是微信支付公钥 pub_key.pem")
                    : pass("KEYS_DISTINCT", "微信支付公钥和 API 证书私钥不是同一对密钥"));
        }

        items.add(notifyUrl("PAYMENT_NOTIFY_URL", "支付回调地址", properties.paymentNotifyUrl(), simulated));
        items.add(notifyUrl("REFUND_NOTIFY_URL", "退款回调地址", properties.refundNotifyUrl(), simulated));
        return items;
    }

    /** 启动时用：只在真实微信模式下拦，有 FAIL 就不让后端起来。返回 WARN 项给调用方记日志。 */
    static List<ChannelCheckItemView> requireStartable(TradeWechatPayProperties properties) {
        if (!properties.enabled() || simulated(properties)) {
            return List.of();
        }
        List<ChannelCheckItemView> items = inspect(properties);
        List<ChannelCheckItemView> failures = items.stream()
                .filter(found -> TradeChannelCheckService.FAIL.equals(found.status()))
                .toList();
        if (!failures.isEmpty()) {
            StringBuilder message = new StringBuilder("微信支付配置自检不通过（共 " + failures.size() + " 项）：");
            for (ChannelCheckItemView failure : failures) {
                message.append("\n  - ").append(failure.code());
                String property = PROPERTIES.get(failure.code());
                if (property != null) {
                    message.append("（").append(property).append("）");
                }
                message.append("：").append(failure.message());
            }
            throw new IllegalStateException(message.toString());
        }
        return items.stream().filter(found -> TradeChannelCheckService.WARN.equals(found.status())).toList();
    }

    // ---- 各项 ----

    private static ChannelCheckItemView apiBase(URI base, boolean simulated) {
        if (base == null) {
            return pass("API_BASE", "接口域名用默认的 api.mch.weixin.qq.com");
        }
        if (simulated) {
            return warn("API_BASE", "接口域名是本机模拟微信");
        }
        if (!"https".equals(base.getScheme()) || base.getHost() == null) {
            return fail("API_BASE", "接口域名必须是 https");
        }
        String path = base.getPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            return fail("API_BASE", "接口域名只写到域名，不带路径");
        }
        return OFFICIAL_HOSTS.contains(base.getHost().toLowerCase(Locale.ROOT))
                ? pass("API_BASE", "接口域名是微信支付官方域名")
                : warn("API_BASE", "接口域名不是 api.mch.weixin.qq.com 或 api2.mch.weixin.qq.com，确认不是写错了");
    }

    private static ChannelCheckItemView spMchid(String value, boolean simulated) {
        String text = trim(value);
        if (text == null) {
            return fail("SP_MCHID", "没有配置服务商户号（TRADE_WECHAT_SP_MCHID）");
        }
        if (!text.matches("^[0-9]{8,32}$")) {
            return fail("SP_MCHID", "服务商户号只能是数字");
        }
        if (!simulated && !text.matches("^[0-9]{10}$")) {
            return warn("SP_MCHID", "服务商户号通常是 10 位数字，确认是服务商号而不是子商户号或 APPID");
        }
        return pass("SP_MCHID", "服务商户号格式对");
    }

    private static ChannelCheckItemView spAppid(String value) {
        String text = trim(value);
        if (text == null) {
            return fail("SP_APPID", "没有配置服务商的移动应用 APPID（TRADE_WECHAT_SP_APPID）");
        }
        if (!text.matches("^[0-9A-Za-z_]{6,32}$")) {
            return fail("SP_APPID", "APPID 格式不对");
        }
        return text.startsWith("wx")
                ? pass("SP_APPID", "APPID 格式对")
                : warn("SP_APPID", "APPID 通常以 wx 开头，确认是开放平台移动应用的 APPID");
    }

    private static ChannelCheckItemView serialNo(String value) {
        String text = trim(value);
        if (text == null) {
            return fail("MERCHANT_SERIAL_NO", "没有配置 API 证书序列号（TRADE_WECHAT_MERCHANT_SERIAL_NO）");
        }
        if (!text.matches("^[0-9A-Fa-f]{8,64}$")) {
            return fail("MERCHANT_SERIAL_NO", "API 证书序列号只能是十六进制字符");
        }
        return text.length() == 40
                ? pass("MERCHANT_SERIAL_NO", "API 证书序列号格式对")
                : warn("MERCHANT_SERIAL_NO", "API 证书序列号通常是 40 个十六进制字符，确认没有少抄");
    }

    private static ChannelCheckItemView apiV3Key(String value) {
        if (value == null || value.isBlank()) {
            return fail("API_V3_KEY", "没有配置 APIv3 密钥（TRADE_WECHAT_API_V3_KEY）");
        }
        int length = value.trim().getBytes(StandardCharsets.UTF_8).length;
        return length == 32
                ? pass("API_V3_KEY", "APIv3 密钥长度对（32 个字符）")
                : fail("API_V3_KEY", "APIv3 密钥必须是 32 个字符（商户平台“API 安全”里设置的那串），现在的长度不对");
    }

    private static ChannelCheckItemView publicKeyId(String value) {
        String text = trim(value);
        if (text == null) {
            return fail("PLATFORM_PUBLIC_KEY_ID", "没有配置微信支付公钥 ID（TRADE_WECHAT_PLATFORM_PUBLIC_KEY_ID）");
        }
        return text.matches("^PUB_KEY_ID_[0-9A-Za-z]{1,64}$")
                ? pass("PLATFORM_PUBLIC_KEY_ID", "微信支付公钥 ID 格式对")
                : fail("PLATFORM_PUBLIC_KEY_ID", "微信支付公钥 ID 应以 PUB_KEY_ID_ 开头");
    }

    private static ChannelCheckItemView notifyUrl(String code, String name, URI url, boolean simulated) {
        if (url == null) {
            return fail(code, "没有配置" + name);
        }
        String host = url.getHost() == null ? null : url.getHost().toLowerCase(Locale.ROOT);
        if (host == null) {
            return fail(code, name + "不是完整的地址");
        }
        if (url.getRawQuery() != null || url.getRawFragment() != null || url.toString().length() > 255) {
            return fail(code, name + "不能带查询参数，最长 255 个字符");
        }
        if (simulated) {
            return "https".equals(url.getScheme()) || LOOPBACK.contains(host)
                    ? warn(code, name + "指向本机（模拟模式）")
                    : fail(code, name + "必须是 https，或本机地址");
        }
        if (!"https".equals(url.getScheme())) {
            return fail(code, name + "必须是 https");
        }
        if (LOOPBACK.contains(host) || privateAddress(host)) {
            return fail(code, name + "是本机或内网地址，微信访问不到；要用外网能访问的域名");
        }
        if (url.getPort() != -1 && url.getPort() != 443) {
            return warn(code, name + "用了非 443 端口，确认外网和微信都能访问");
        }
        return CALLBACK_PATH.equals(url.getPath())
                ? pass(code, name + "是外网 https，路径对")
                : warn(code, name + "的路径不是 " + CALLBACK_PATH + "，确认 Nginx 把它转到了后端的回调接口");
    }

    /** IP 字面量是私网、链路本地或回环。域名不解析（启动时不做 DNS，免得依赖网络）。 */
    private static boolean privateAddress(String host) {
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (!bare.matches("^[0-9.]+$") && !bare.contains(":")) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(bare);
            return address.isSiteLocalAddress() || address.isLinkLocalAddress() || address.isLoopbackAddress()
                    || address.isAnyLocalAddress();
        } catch (IOException exception) {
            return false;
        }
    }

    private static ChannelCheckItemView fileMode(String code, Path path, String what) {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
            boolean open = permissions.contains(PosixFilePermission.GROUP_READ)
                    || permissions.contains(PosixFilePermission.GROUP_WRITE)
                    || permissions.contains(PosixFilePermission.OTHERS_READ)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE);
            return open
                    ? warn(code, what + "对其他用户可读写，改成只让运行后端的用户读（chmod 400 或 600）")
                    : pass(code, what + "只有属主能读");
        } catch (UnsupportedOperationException | IOException exception) {
            return new ChannelCheckItemView(code, TradeChannelCheckService.SKIPPED,
                    "这台机器的文件系统不支持查看" + what + "的权限");
        }
    }

    /** 按单字节读：内容不是 ASCII 时交给解析报“格式不对”，而不是报“读不到”。 */
    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.ISO_8859_1);
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ChannelCheckItemView pass(String code, String message) {
        return new ChannelCheckItemView(code, TradeChannelCheckService.PASS, message);
    }

    private static ChannelCheckItemView warn(String code, String message) {
        return new ChannelCheckItemView(code, TradeChannelCheckService.WARN, message);
    }

    private static ChannelCheckItemView fail(String code, String message) {
        return new ChannelCheckItemView(code, TradeChannelCheckService.FAIL, message);
    }
}
