package com.lowaltitude.inspection.application.city;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.lowaltitude.inspection.ops.OpsTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class OpsMapPlaceControllerTest {
    private final MapPlaceOperations places = mock(MapPlaceOperations.class);
    private final MockMvc api = OpsTestSupport.mockMvc(new OpsMapPlaceController(OpsTestSupport.actors(), places));
    private final String base = "/api/v1/ops/map-places";
    private final UUID id = UUID.randomUUID();

    @Test void onlyPlatformOperatorsCanReadWriteOrSeeHistory() throws Exception {
        for (var who : java.util.List.of(OpsTestSupport.support(), OpsTestSupport.signedIn(UUID.randomUUID(), "CUSTOMER"))) {
            api.perform(get(base).principal(who)).andExpect(status().isForbidden());
            api.perform(put(base + "/" + id).principal(who).contentType(MediaType.APPLICATION_JSON)
                    .content("{}")).andExpect(status().isForbidden());
            api.perform(get(base + "/" + id + "/changes").principal(who)).andExpect(status().isForbidden());
        }
        verifyNoInteractions(places);
    }
    @Test void staleWriteReturnsConflictWithoutOverwritingNewerData() throws Exception {
        when(places.save(any(), eq(id), any())).thenThrow(new CityOperationException(CityOperationException.Error.VERSION_CONFLICT));
        api.perform(put(base + "/" + id).principal(OpsTestSupport.operator()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":1}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPS_VERSION_CONFLICT"));
    }
}
