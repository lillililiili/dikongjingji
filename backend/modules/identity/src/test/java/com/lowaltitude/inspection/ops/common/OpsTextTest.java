package com.lowaltitude.inspection.ops.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class OpsTextTest {

    @Test
    void textIsTrimmedAndCountedByCodePoint() {
        assertThat(OpsText.required("  黄河口  ", 3, "title")).isEqualTo("黄河口");
        assertThat(OpsText.optional("   ", 3, "title")).isNull();
        assertThat(OpsText.required("𠀀𠀀", 2, "title")).isEqualTo("𠀀𠀀");
        assertThatThrownBy(() -> OpsText.required("黄河口低", 3, "title")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpsText.required(" ", 3, "title")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpsText.optional("a\nb", 3, "title")).isInstanceOf(IllegalArgumentException.class);
        assertThat(OpsText.multiline("第一条\r\n第二条", 20, "notice")).isEqualTo("第一条\n第二条");
        assertThatThrownBy(() -> OpsText.multiline("a\tb", 20, "notice")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyHttpsAddressesWithAHostAreAccepted() {
        assertThat(OpsText.httpsUrl(" https://cdn.example.com/a.jpg ", "coverUrl"))
                .isEqualTo("https://cdn.example.com/a.jpg");
        assertThat(OpsText.httpsUrl("", "coverUrl")).isNull();
        for (String bad : List.of("http://cdn.example.com/a.jpg", "https:///a.jpg", "javascript:alert(1)",
                "https://user:pw@cdn.example.com/a.jpg", "https://cdn.example.com/a b.jpg", "/static/a.jpg",
                "https://cdn.example.com/" + "a".repeat(500))) {
            assertThatThrownBy(() -> OpsText.httpsUrl(bad, "coverUrl")).as(bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void keywordsLoseWildcardsAndPagesAreBounded() {
        assertThat(OpsText.keyword(" 100%_无人机 ", 20)).isEqualTo("100无人机");
        assertThat(OpsText.keyword("%%", 20)).isNull();
        assertThatThrownBy(() -> OpsText.page(0, 20, 50)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpsText.page(1, 51, 50)).isInstanceOf(IllegalArgumentException.class);
        assertThat(OpsText.operatorName(" " + "名".repeat(90))).hasSize(80);
    }

    @Test
    void incompleteCarriesTheMissingKeys() {
        OpsError error = new OpsError() {
            @Override
            public int httpStatus() {
                return 422;
            }

            @Override
            public String code() {
                return OpsError.PUBLISH_INCOMPLETE;
            }

            @Override
            public String message() {
                return "上线前还有必填项没填";
            }
        };
        OpsException exception = OpsException.incomplete(error, List.of("coverUrl", "sessions"));
        assertThat(exception.getMessage()).isEqualTo("OPS_PUBLISH_INCOMPLETE");
        assertThat(exception.details()).isEqualTo(Map.of("missing", List.of("coverUrl", "sessions")));
        assertThat(new OpsException(error).details()).isNull();
    }
}
