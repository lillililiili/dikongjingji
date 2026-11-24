package com.lowaltitude.inspection.identity.application.account;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import com.lowaltitude.inspection.identity.domain.OpsStatsExclusion;
import com.lowaltitude.inspection.identity.infrastructure.persistence.IdentityOpsStatsMapper;
import org.springframework.transaction.annotation.Transactional;

/**
 * 把“经营概览”的排除名单（登录名、机构编码）解析成用户 ID 和机构 ID。名单是逗号分隔的，大小写敏感；
 * 末尾一个 * 表示前缀匹配（dev-* 匹配 dev-customer）。写法不对时启动失败，免得名单悄悄不生效或者把所有账号都排除了。
 * 每次统计现查，不缓存：新建的测试账号立刻不计入。
 */
public class OpsStatsExclusionService {

    private static final Pattern ENTRY = Pattern.compile("[^\\s,*]+\\*?");

    private final IdentityOpsStatsMapper mapper;
    private final NamePatterns loginNames;
    private final NamePatterns organizationCodes;

    public OpsStatsExclusionService(IdentityOpsStatsMapper mapper, String excludedLoginNames,
                                    String excludedOrganizationCodes) {
        this.mapper = mapper;
        this.loginNames = NamePatterns.parse("platform.ops-stats.excluded-login-names", excludedLoginNames);
        this.organizationCodes = NamePatterns.parse("platform.ops-stats.excluded-organization-codes",
                excludedOrganizationCodes);
    }

    @Transactional(readOnly = true)
    public OpsStatsExclusion resolve() {
        if (loginNames.isEmpty() && organizationCodes.isEmpty()) {
            return OpsStatsExclusion.NONE;
        }
        return new OpsStatsExclusion(
                loginNames.isEmpty() ? List.of()
                        : mapper.findUserIds(loginNames.exactList(), loginNames.prefixList()),
                organizationCodes.isEmpty() ? List.of()
                        : mapper.findOrganizationIds(organizationCodes.exactList(),
                                organizationCodes.prefixList()));
    }

    /** 名单分成完整匹配和前缀匹配两组；每项都不含逗号，拼成逗号分隔的一个参数交给 SQL 拆。 */
    record NamePatterns(List<String> exact, List<String> prefixes) {

        static NamePatterns parse(String key, String configured) {
            List<String> exact = new ArrayList<>();
            List<String> prefixes = new ArrayList<>();
            Arrays.stream((configured == null ? "" : configured).split(","))
                    .map(String::trim)
                    .filter(entry -> !entry.isEmpty())
                    .forEach(entry -> {
                        if (!ENTRY.matcher(entry).matches()) {
                            throw new IllegalStateException(key + " 里的 “" + entry
                                    + "” 写法不对：只能在末尾用一个 *，* 前面要有内容，不能有空格");
                        }
                        if (entry.endsWith("*")) {
                            prefixes.add(entry.substring(0, entry.length() - 1));
                        } else {
                            exact.add(entry);
                        }
                    });
            return new NamePatterns(List.copyOf(exact), List.copyOf(prefixes));
        }

        boolean isEmpty() {
            return exact.isEmpty() && prefixes.isEmpty();
        }

        String exactList() {
            return String.join(",", exact);
        }

        String prefixList() {
            return String.join(",", prefixes);
        }
    }
}
