package com.lowaltitude.inspection.application.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.demand.application.DemandException;
import com.lowaltitude.inspection.demand.infrastructure.persistence.DemandBookingMapper;
import com.lowaltitude.inspection.demand.infrastructure.persistence.DemandMapper;
import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import com.lowaltitude.inspection.infrastructure.persistence.InspectionDemandServiceMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class InspectionDemandBookingServiceTest {
    private final DemandMapper demands = mock(DemandMapper.class);
    private final DemandBookingMapper links = mock(DemandBookingMapper.class);
    private final InspectionDemandServiceMapper providers = mock(InspectionDemandServiceMapper.class);
    private final ReservationApplicationService reservations = mock(ReservationApplicationService.class);
    private final InspectionDemandBookingService service =
            new InspectionDemandBookingService(demands, links, providers, reservations);
    private final UUID demandId = UUID.randomUUID();
    private final UUID key = UUID.randomUUID();
    private final UUID serviceId = UUID.randomUUID();
    private final CurrentSubject customer = new CurrentSubject(UUID.randomUUID(), Set.of("CUSTOMER"),
            UUID.randomUUID(), Set.of("inspection.reservation.create", "inspection.reservation.read.own"), 0);
    private final DemandBookingMapper.Quote quote =
            new DemandBookingMapper.Quote(UUID.randomUUID(), 3, UUID.randomUUID(), "获选机构", 13500, "作业方法");

    private void chosen() {
        DemandMapper.DemandRow row = mock(DemandMapper.DemandRow.class);
        when(row.id()).thenReturn(demandId);
        when(row.type()).thenReturn("INSPECTION");
        when(row.status()).thenReturn("CHOSEN");
        when(row.cityCode()).thenReturn("370500");
        when(row.chosenQuoteId()).thenReturn(quote.quoteId());
        when(row.chosenQuoteVersion()).thenReturn(quote.quoteVersion());
        when(demands.lockDemand(customer.subjectId(), demandId)).thenReturn(row);
        when(links.findChosen(demandId, quote.quoteId(), quote.quoteVersion())).thenReturn(quote);
    }

    private CreateReservationCommand command(String city, String address) {
        return new CreateReservationCommand(serviceId, city, address, 118.59, 37.46, 1000L,
                LocalDate.of(2026, 12, 2), "AM", List.of("VISUAL"), List.of(), null,
                "联系人", "13800000000", "v1", true);
    }

    @Test
    void directReservationKeysCannotAttachUnrelatedReservations() {
        chosen();
        var request = command("370500", "作业地址");
        UUID reservationId = UUID.randomUUID();
        var created = mock(ReservationView.class);
        when(created.id()).thenReturn(reservationId);
        when(providers.lockBookable(quote.providerId(), "370500", serviceId))
                .thenReturn(new InspectionDemandServiceMapper.BookableService(serviceId, "可约服务"));
        when(reservations.create(eq(customer.subjectId()), any(), eq(request))).thenReturn(created);

        assertThat(service.book(customer, demandId, key, request).created()).isTrue();
        var nativeKey = ArgumentCaptor.forClass(UUID.class);
        verify(reservations).create(eq(customer.subjectId()), nativeKey.capture(), eq(request));
        assertThat(nativeKey.getValue()).isNotEqualTo(key);
        verify(links).insert(demandId, reservationId, quote, customer.subjectId(), key,
                ReservationApplicationService.digest(request));
    }

    @Test
    void identicalReplayWithNewKeyReturnsOriginalEvenAfterServiceWithdrawn() {
        chosen();
        var request = command("370500", "作业地址");
        UUID original = UUID.randomUUID();
        when(links.findLink(demandId)).thenReturn(new DemandBookingMapper.Link(original,
                ReservationApplicationService.digest(request)));
        var result = service.book(customer, demandId, UUID.randomUUID(), request);
        assertThat(result.created()).isFalse();
        verify(reservations).read(customer, original);
        verifyNoInteractions(providers);
        assertThatThrownBy(() -> service.book(customer, demandId, key, command("370500", "另一处作业地址")))
                .isInstanceOfSatisfying(DemandException.class,
                        e -> assertThat(e.error()).isEqualTo(DemandException.Error.BOOKING_CONFLICT));
        verify(reservations, never()).create(any(), any(), any());
    }

    @Test
    void rejectsCrossDemandKeyWrongCityAndServiceOutsideChosenProvider() {
        chosen();
        when(links.findDemandByKey(customer.subjectId(), key)).thenReturn(UUID.randomUUID());
        assertThatThrownBy(() -> service.book(customer, demandId, key, command("370500", "作业地址")))
                .isInstanceOfSatisfying(DemandException.class,
                        e -> assertThat(e.error()).isEqualTo(DemandException.Error.IDEMPOTENCY_CONFLICT));
        assertThatThrownBy(() -> service.book(customer, demandId, UUID.randomUUID(), command("370100", "作业地址")))
                .isInstanceOfSatisfying(DemandException.class,
                        e -> assertThat(e.error()).isEqualTo(DemandException.Error.BOOKING_SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> service.book(customer, demandId, UUID.randomUUID(), command("370500", "作业地址")))
                .isInstanceOfSatisfying(DemandException.class,
                        e -> assertThat(e.error()).isEqualTo(DemandException.Error.BOOKING_SERVICE_UNAVAILABLE));
        verify(providers).lockBookable(quote.providerId(), "370500", serviceId);
        verifyNoInteractions(reservations);
    }
}
