package com.lowaltitude.inspection.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.lowaltitude.inspection.content.infrastructure.persistence.HomeCarouselMapper;
import com.lowaltitude.inspection.content.infrastructure.persistence.HomeCarouselMapper.SlideRow;
import com.lowaltitude.inspection.ops.media.OpsMediaStore;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class HomeCarouselControllerTest {
    @Test
    void preservesPublishedCityOrderAndUsesConfiguredMediaStore() {
        var mapper = mock(HomeCarouselMapper.class);
        var media = mock(OpsMediaStore.class);
        String key = "ops-media/12345678-abcd-4321-beef-123456789abc.jpg";
        String fixture = "/api/v1/dev-media/images/home/hero-world.jpg";
        when(mapper.published("370500")).thenReturn(List.of(row("local", fixture), row("published", key)));
        when(media.publicUrl(key)).thenReturn("https://assets.example.com/ops/" + key);
        var dev = new HomeCarouselController(mapper, media, new MockEnvironment().withProperty("spring.profiles.active", "dev"));
        var prod = new HomeCarouselController(mapper, media, new MockEnvironment());
        assertThat(dev.carousel("370500").items()).extracting(HomeCarouselController.Slide::id)
                .containsExactly("local", "published");
        assertThat(dev.carousel("370500").items().getFirst().imageUrl()).isEqualTo(fixture);
        var visible = prod.carousel("370500").items();
        assertThat(visible).hasSize(1);
        assertThat(visible.getFirst().imageUrl()).isEqualTo("https://assets.example.com/ops/" + key);
        assertThat(prod.carousel("110100").items()).isEmpty();
        assertThatThrownBy(() -> dev.carousel("bad city")).isInstanceOf(IllegalArgumentException.class);
    }

    private static SlideRow row(String id, String image) {
        return new SlideRow(id, "tag", "first", "second", "a", "b", "go", image, "alt", "SKY", "ACTIVITY");
    }
}
