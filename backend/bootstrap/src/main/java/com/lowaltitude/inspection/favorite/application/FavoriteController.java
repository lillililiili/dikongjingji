package com.lowaltitude.inspection.favorite.application;

import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.favorite.application.FavoriteException.Error;
import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客户的收藏（契约 contracts/openapi-favorite.yaml 1.0.0）。都要登录和客户角色；账号只从令牌里取。
 * 对象类型在路径里按字符串收，不认识的由服务按 400 处理。
 */
@RestController
public final class FavoriteController {

    static final String CUSTOMER = "CUSTOMER";

    private final FavoriteService service;

    public FavoriteController(FavoriteService service) {
        this.service = service;
    }

    /** 一个分组的一页，新收藏的在前；counts 是四个分组各自的收藏数。 */
    @GetMapping("/api/v1/favorites")
    public FavoritePageView list(
            Authentication authentication,
            @RequestParam String group,
            @RequestParam String cityCode,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        return service.list(customer(authentication), group, cityCode, page, size);
    }

    @GetMapping("/api/v1/favorites/{targetType}/{targetId}")
    public FavoriteStateView state(Authentication authentication, @PathVariable String targetType,
                                   @PathVariable UUID targetId) {
        return service.state(customer(authentication), targetType, targetId);
    }

    /** 新收藏 201；已经收藏过或撤销了刚才的取消 200。 */
    @PutMapping("/api/v1/favorites/{targetType}/{targetId}")
    public ResponseEntity<FavoriteStateView> add(
            Authentication authentication,
            @PathVariable String targetType,
            @PathVariable UUID targetId,
            @RequestParam String cityCode
    ) {
        FavoritePut put = service.add(customer(authentication), targetType, targetId, cityCode);
        return ResponseEntity.status(put.created() ? 201 : 200).body(put.state());
    }

    @DeleteMapping("/api/v1/favorites/{targetType}/{targetId}")
    public FavoriteStateView remove(Authentication authentication, @PathVariable String targetType,
                                    @PathVariable UUID targetId) {
        return service.remove(customer(authentication), targetType, targetId);
    }

    /** 客户角色的账号 ID。取不到账号时按没有权限处理。 */
    static UUID customer(Authentication authentication) {
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (principal instanceof CurrentSubject current) {
            if (!current.roles().contains(CUSTOMER)) {
                throw new FavoriteException(Error.FORBIDDEN);
            }
            return current.subjectId();
        }
        if (principal instanceof Jwt jwt) {
            List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles == null || !roles.contains(CUSTOMER)) {
                throw new FavoriteException(Error.FORBIDDEN);
            }
            try {
                return UUID.fromString(jwt.getClaimAsString("subjectId"));
            } catch (RuntimeException invalidClaim) {
                throw new AccessDeniedException("authenticated subject is invalid", invalidClaim);
            }
        }
        throw new AccessDeniedException("authenticated subject is invalid");
    }
}
