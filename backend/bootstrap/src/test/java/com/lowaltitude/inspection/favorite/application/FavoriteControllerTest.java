package com.lowaltitude.inspection.favorite.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.favorite.application.FavoriteException.Error;
import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;

/** 收藏接口（计划 08 S5-6）：账号只从令牌里取，只有客户角色能用；PUT 新收藏 201、已有 200。 */
class FavoriteControllerTest {

    private static final UUID CUSTOMER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID KIT = UUID.fromString("a1000000-0000-4000-8000-000000000001");
    private final FavoriteService service = mock(FavoriteService.class);
    private final FavoriteController controller = new FavoriteController(service);

    @Test
    void puttingAnswersCreatedOnlyForANewFavorite() {
        FavoriteStateView state = new FavoriteStateView(FavoriteTargetType.PRODUCT, KIT, true, OffsetDateTime.now());
        when(service.add(CUSTOMER, "PRODUCT", KIT, "DONGYING")).thenReturn(new FavoritePut(state, true),
                new FavoritePut(state, false));

        ResponseEntity<FavoriteStateView> created = controller.add(signedIn("CUSTOMER"), "PRODUCT", KIT, "DONGYING");
        ResponseEntity<FavoriteStateView> again = controller.add(signedIn("CUSTOMER"), "PRODUCT", KIT, "DONGYING");

        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(again.getStatusCode().value()).isEqualTo(200);
        assertThat(again.getBody()).isEqualTo(state);
    }

    @Test
    void onlyCustomersMayUseFavorites() {
        assertThatThrownBy(() -> controller.list(signedIn("PROVIDER_OPERATOR"), "GOODS", "DONGYING", 1, 10))
                .isInstanceOfSatisfying(FavoriteException.class,
                        failure -> assertThat(failure.error()).isEqualTo(Error.FORBIDDEN));
        assertThatThrownBy(() -> controller.remove(null, "PRODUCT", KIT)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(service);
    }

    private static TestingAuthenticationToken signedIn(String role) {
        return new TestingAuthenticationToken(new CurrentSubject(CUSTOMER, Set.of(role), null, Set.of(), 1), null);
    }
}
