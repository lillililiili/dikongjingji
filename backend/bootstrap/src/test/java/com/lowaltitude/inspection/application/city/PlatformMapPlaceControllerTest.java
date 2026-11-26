package com.lowaltitude.inspection.application.city;

import com.lowaltitude.inspection.infrastructure.persistence.PlatformMapPlaceMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PlatformMapPlaceControllerTest {
    private final PlatformMapPlaceMapper mapper = mock(PlatformMapPlaceMapper.class);
    private final PlatformMapPlaceController controller = new PlatformMapPlaceController(mapper);

    @Test void passesCoordinatesAndKeywordToStoreAndKeepsStoredCoordinates() {
        var row = new PlatformMapPlaceMapper.PlaceRow("370500:gate", "入口", "南门", 37.42, 118.64, false);
        when(mapper.find("370500", "入口", 37.0, 118.0)).thenReturn(List.of(row));
        assertThat(controller.places("370500", " 入口 ", 37.0, 118.0)).containsExactly(row);
    }

    @Test void rejectsInvalidCoordinatesAndMalformedCityBeforeReading() {
        assertThatThrownBy(() -> controller.places("370500", "", 37.0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.places("370500", "", Double.NaN, 100.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.places("370500", "", 38.0, 181.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.places("unknown", "", null, null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mapper);
    }
}
