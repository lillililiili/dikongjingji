package com.lowaltitude.inspection.application.reservation;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.demand.application.DemandException;
import com.lowaltitude.inspection.demand.application.DemandException.Error;
import com.lowaltitude.inspection.demand.application.DemandType;
import com.lowaltitude.inspection.demand.infrastructure.persistence.DemandBookingMapper;
import com.lowaltitude.inspection.demand.infrastructure.persistence.DemandBookingMapper.Quote;
import com.lowaltitude.inspection.demand.infrastructure.persistence.DemandMapper;
import com.lowaltitude.inspection.demand.infrastructure.persistence.DemandMapper.DemandRow;
import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import com.lowaltitude.inspection.infrastructure.persistence.InspectionDemandServiceMapper;
import com.lowaltitude.inspection.infrastructure.persistence.InspectionDemandServiceMapper.BookableService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 客户补齐作业位置、时段和联系人后，为已选巡检报价创建一笔标准 M04 预约。 */
@Service
public class InspectionDemandBookingService {
    private final DemandMapper demands;
    private final DemandBookingMapper links;
    private final InspectionDemandServiceMapper providers;
    private final ReservationApplicationService reservations;

    public InspectionDemandBookingService(DemandMapper demands, DemandBookingMapper links,
                                         InspectionDemandServiceMapper providers,
                                         ReservationApplicationService reservations) {
        this.demands = demands;
        this.links = links;
        this.providers = providers;
        this.reservations = reservations;
    }

    @Transactional(readOnly = true)
    public Context preview(UUID customerId, UUID demandId) {
        DemandRow demand = demands.findDemand(customerId, demandId);
        Quote q = requireChosen(demand);
        return new Context(demand.id(), DemandType.INSPECTION.label() + "需求", demand.cityCode(), demand.address(),
                demand.scopeText(), demand.note(), demand.dueDate(), q.quoteId(), q.quoteVersion(), q.providerId(),
                q.providerName(), q.amountMinor(), q.summary(), providers.findBookable(q.providerId(), demand.cityCode()),
                demand.inspectionReservationId());
    }

    @Transactional
    public Result book(CurrentSubject customer, UUID demandId, UUID key, CreateReservationCommand command) {
        UUID accountId = customer.subjectId();
        demands.lockAccount(accountId);
        DemandRow demand = demands.lockDemand(accountId, demandId);
        Quote q = requireChosen(demand);
        UUID previousDemand = links.findDemandByKey(accountId, key);
        if (previousDemand != null && !previousDemand.equals(demandId)) {
            throw new DemandException(Error.IDEMPOTENCY_CONFLICT);
        }
        String hash = ReservationApplicationService.digest(command);
        var existing = links.findLink(demandId);
        if (existing != null) {
            if (!hash.equals(existing.requestHash())) {
                throw new DemandException(Error.BOOKING_CONFLICT);
            }
            return new Result(reservations.read(customer, existing.reservationId()), false);
        }
        if (!demand.cityCode().equals(command.cityCode()) || command.serviceId() == null
                || providers.lockBookable(q.providerId(), demand.cityCode(), command.serviceId()) == null) {
            throw new DemandException(Error.BOOKING_SERVICE_UNAVAILABLE);
        }
        // 转预约键只作用于这个接口；标准预约另用内部键，防止误绑定同账号使用同一客户端键创建的普通预约。
        UUID id = reservations.create(accountId, UUID.randomUUID(), command).id();
        links.insert(demandId, id, q, accountId, key, hash);
        return new Result(reservations.read(customer, id), true);
    }

    private Quote requireChosen(DemandRow demand) {
        if (demand == null) {
            throw new DemandException(Error.NOT_FOUND);
        }
        if (!"INSPECTION".equals(demand.type()) || !"CHOSEN".equals(demand.status())
                || demand.chosenQuoteId() == null || demand.chosenQuoteVersion() == null) {
            throw new DemandException(Error.STATE_INVALID);
        }
        Quote quote = links.findChosen(demand.id(), demand.chosenQuoteId(), demand.chosenQuoteVersion());
        if (quote == null) {
            throw new DemandException(Error.QUOTE_CHANGED);
        }
        return quote;
    }

    public record Context(UUID demandId, String title, String cityCode, String address, String scope, String note,
                          LocalDate dueDate, UUID quoteId, int quoteVersion, UUID providerId, String providerName,
                          long amountMinor, String summary, List<BookableService> services, UUID reservationId) { }

    public record Result(ReservationDetails reservation, boolean created) { }
}
