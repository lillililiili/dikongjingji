package com.lowaltitude.inspection.trade.application;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.lowaltitude.inspection.ops.common.OpsException;
import com.lowaltitude.inspection.trade.application.TradeAftersaleRules.Kind;
import com.lowaltitude.inspection.trade.application.TradeAftersaleRules.OrderFacts;
import com.lowaltitude.inspection.trade.application.TradeAftersaleRules.Reason;
import com.lowaltitude.inspection.trade.application.TradeAftersaleViews.AftersaleActionsView;
import com.lowaltitude.inspection.trade.application.TradeAftersaleViews.AftersaleCountsView;
import com.lowaltitude.inspection.trade.application.TradeAftersaleViews.AftersaleEligibilityView;
import com.lowaltitude.inspection.trade.application.TradeAftersaleViews.AftersaleEventView;
import com.lowaltitude.inspection.trade.application.TradeAftersaleViews.AftersaleImageUploadView;
import com.lowaltitude.inspection.trade.application.TradeAftersaleViews.AftersaleImageView;
import com.lowaltitude.inspection.trade.application.TradeAftersaleViews.AftersaleMerchantView;
import com.lowaltitude.inspection.trade.application.TradeAftersaleViews.AftersaleOptionView;
import com.lowaltitude.inspection.trade.application.TradeAftersaleViews.AftersalePageView;
import com.lowaltitude.inspection.trade.application.TradeAftersaleViews.AftersaleReasonOptionView;
import com.lowaltitude.inspection.trade.application.TradeAftersaleViews.AftersaleRefundView;
import com.lowaltitude.inspection.trade.application.TradeAftersaleViews.AftersaleReturnAddressView;
import com.lowaltitude.inspection.trade.application.TradeAftersaleViews.AftersaleView;
import com.lowaltitude.inspection.trade.application.TradeAftersaleViews.AftersaleWaybillView;
import com.lowaltitude.inspection.trade.application.TradeAftersaleViews.ReturnAddressView;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeMediaView;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeOrderLineView;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeAftersaleMapper;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeAftersaleMapper.AftersaleRow;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeAftersaleMapper.DueRow;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeAftersaleMapper.ImageRow;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeAftersaleMapper.LineRow;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeAftersaleMapper.OrderFactsRow;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeAftersaleMapper.RefundStateRow;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeAftersaleMapper.ReturnAddressRow;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeAftersaleMapper.StatusCountRow;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeRefundMapper;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeRefundMapper.RefundOrderRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 商品售后（S4-2，A1 默认决定，规则见 {@link TradeAftersaleRules}）。只针对商城订单；平台只协助沟通、不裁决：
 * 同意、拒绝、验货都由卖出商品的商家操作，钱经本模块的退款原路退回。
 * <ul>
 *   <li>买家：看能申请什么、上传凭证、申请、撤销（商家寄回地址给出之后、填运单之前也能撤销）、填和改退货运单、看进度。</li>
 *   <li>商家：列表、同意（给寄回地址）或拒绝（写理由）、确认收货并退款（可以少于申请金额，要写说明）、验货不通过（写理由）、
 *       默认寄回地址。</li>
 *   <li>定时：48 小时不处理自动同意、7 天不寄回自动关闭、寄回 10 天商家不处理自动退款。</li>
 *   <li>退款到账或失败由退款服务回推（{@link #settled}）。</li>
 * </ul>
 * 锁的顺序和退款一样：先订单、后售后单。调渠道（提交退款）在事务提交之后。
 */
public class TradeAftersaleService implements TradeRefundService.RefundSettlement {

    static final int MAX_PAGE_SIZE = 50;
    static final int MAX_PAGE = 10_000;
    static final int MAX_DESCRIPTION = 200;
    static final int MAX_IMAGES = 9;
    static final int MAX_IMAGES_PER_SLOT = 3;
    static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    /** 一个账号一小时最多传这么多张凭证。 */
    static final int MAX_UPLOADS_PER_HOUR = 30;
    static final int MAX_REASON = 100;
    static final int MAX_NOTE = 200;
    static final int DUE_BATCH = 50;
    static final Set<String> SLOTS = Set.of("PRODUCT", "PACKAGE", "OTHER");
    static final Set<String> CLOSED = Set.of("REJECTED", "RECEIPT_REJECTED", "REVOKED", "CLOSED");

    private static final Logger log = LoggerFactory.getLogger(TradeAftersaleService.class);
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final TradeAftersaleMapper mapper;
    private final TradeRefundMapper refunds;
    private final TradeRefundService refundService;
    private final TradeAftersaleImageStore images;
    private final TransactionOperations tx;
    private final Clock clock;
    private final Supplier<String> numberSuffix;

    TradeAftersaleService(
            TradeAftersaleMapper mapper,
            TradeRefundMapper refunds,
            TradeRefundService refundService,
            TradeAftersaleImageStore images,
            TransactionOperations tx,
            Clock clock,
            Supplier<String> numberSuffix
    ) {
        this.mapper = mapper;
        this.refunds = refunds;
        this.refundService = refundService;
        this.images = images;
        this.tx = tx;
        this.clock = clock;
        this.numberSuffix = numberSuffix;
    }

    // ---- 买家：能申请什么 ----

    public AftersaleEligibilityView eligibility(UUID buyerId, UUID orderId) {
        requireId(buyerId, orderId);
        return tx.execute(status -> {
            RefundOrderRow order = refunds.lockBuyerOrder(buyerId, orderId);
            OrderFactsRow row = order == null ? null : mapper.findOrderFacts(orderId);
            if (row == null || !TradeSourceEvents.MALL.equals(row.source())) {
                throw new OpsException(TradeAftersaleError.NOT_FOUND);
            }
            OrderFacts facts = facts(order, row);
            Instant now = clock.instant();
            List<AftersaleOptionView> kinds = new ArrayList<>();
            for (Kind kind : Kind.values()) {
                String block = TradeAftersaleRules.kindBlock(facts, kind);
                kinds.add(new AftersaleOptionView(kind.name(), block == null, block));
            }
            List<AftersaleReasonOptionView> reasons = new ArrayList<>();
            for (Reason reason : Reason.values()) {
                String block = TradeAftersaleRules.reasonBlock(facts, reason, now);
                reasons.add(new AftersaleReasonOptionView(reason.name(), reason.kind.name(), block == null, block));
            }
            UUID open = facts.openAftersale()
                    ? mapper.findBuyerAftersales(buyerId, List.copyOf(TradeAftersaleRules.OPEN), 50, 0).stream()
                            .filter(item -> item.orderId().equals(orderId))
                            .map(AftersaleRow::id)
                            .findFirst()
                            .orElse(null)
                    : null;
            OrderHead head = orderHead(orderId);
            return new AftersaleEligibilityView(
                    orderId,
                    head.orderNo(),
                    order.status(),
                    lines(List.of(orderId)).getOrDefault(orderId, List.of()),
                    order.paidMinor(),
                    Math.max(0, facts.refundableMinor()),
                    head.merchant(),
                    kinds,
                    reasons,
                    open,
                    row.shippedAt(),
                    row.completedAt());
        });
    }

    // ---- 买家：凭证图片 ----

    public AftersaleImageUploadView uploadImage(UUID buyerId, String slot, byte[] content) {
        if (buyerId == null) {
            throw new IllegalArgumentException("buyer is required");
        }
        String normalizedSlot = slot == null ? "" : slot.trim();
        if (!SLOTS.contains(normalizedSlot)) {
            throw new IllegalArgumentException("image slot is invalid");
        }
        if (content == null || content.length == 0 || content.length > MAX_IMAGE_BYTES) {
            throw new OpsException(TradeAftersaleError.IMAGE_INVALID);
        }
        String contentType = sniff(content);
        if (contentType == null) {
            throw new OpsException(TradeAftersaleError.IMAGE_INVALID);
        }
        Instant now = clock.instant();
        if (mapper.countRecentUploads(buyerId, now.minus(Duration.ofHours(1))) >= MAX_UPLOADS_PER_HOUR) {
            throw new OpsException(TradeAftersaleError.UPLOAD_LIMITED);
        }
        String key = "aftersale/" + UUID.randomUUID() + "." + extension(contentType);
        try {
            images.put(key, content, contentType);
        } catch (TradeAftersaleImageStore.UnavailableException unavailable) {
            log.warn("aftersale image storage is unavailable: {}", unavailable.getMessage());
            throw new OpsException(TradeAftersaleError.STORAGE_UNAVAILABLE);
        }
        UUID id = tx.execute(status ->
                mapper.insertImage(buyerId, normalizedSlot, contentType, content.length, sha256(content), key, now));
        return new AftersaleImageUploadView(id, normalizedSlot, contentType, content.length);
    }

    /** 买家读自己上传的一张凭证（挂上售后单之前、之后都可以）。 */
    public StoredImage readOwnImage(UUID buyerId, UUID imageId) {
        requireId(buyerId, imageId);
        ImageRow image = mapper.findImage(imageId);
        if (image == null || !image.uploadedBy().equals(buyerId)) {
            throw new OpsException(TradeAftersaleError.NOT_FOUND);
        }
        return load(image);
    }

    /** 商家读本机构一张售后单上的凭证。 */
    public StoredImage readMerchantImage(UUID merchantId, UUID aftersaleId, UUID imageId) {
        requireId(merchantId, aftersaleId);
        if (imageId == null || mapper.findMerchantAftersale(merchantId, aftersaleId) == null) {
            throw new OpsException(TradeAftersaleError.NOT_FOUND);
        }
        ImageRow image = mapper.findImage(imageId);
        if (image == null || !aftersaleId.equals(image.aftersaleId())) {
            throw new OpsException(TradeAftersaleError.NOT_FOUND);
        }
        return load(image);
    }

    public record StoredImage(byte[] content, String contentType) {
    }

    private StoredImage load(ImageRow image) {
        return images.read(image.storageKey())
                .map(bytes -> new StoredImage(bytes, image.contentType()))
                .orElseThrow(() -> new OpsException(TradeAftersaleError.NOT_FOUND));
    }

    // ---- 买家：申请 ----

    public record ApplyCommand(String kind, String reason, String description, Long requestedMinor, List<UUID> imageIds) {
    }

    public record ApplyResult(AftersaleView aftersale, boolean created) {
    }

    public ApplyResult apply(UUID buyerId, UUID orderId, UUID idempotencyKey, ApplyCommand command) {
        requireId(buyerId, orderId);
        if (idempotencyKey == null || command == null) {
            throw new IllegalArgumentException("aftersale application is invalid");
        }
        Kind kind = parse(command.kind(), Kind::valueOf, "kind");
        Reason reason = parse(command.reason(), Reason::valueOf, "reason");
        if (reason.kind != kind) {
            throw new IllegalArgumentException("reason does not belong to the kind");
        }
        String description = text(command.description(), MAX_DESCRIPTION, true);
        if (command.requestedMinor() != null && command.requestedMinor() < 1) {
            throw new IllegalArgumentException("requested amount must be positive");
        }
        List<UUID> imageIds = command.imageIds() == null ? List.of() : command.imageIds();
        if (imageIds.size() > MAX_IMAGES || imageIds.stream().anyMatch(Objects::isNull)
                || new LinkedHashSet<>(imageIds).size() != imageIds.size()) {
            throw new OpsException(TradeAftersaleError.IMAGE_INVALID);
        }
        if (kind == Kind.REFUND_ONLY && !refundService.channelReady()) {
            throw new OpsException(TradeAftersaleError.CHANNEL_UNAVAILABLE);
        }
        String hash = sha256((kind + "|" + reason + "|" + Objects.toString(description, "") + "|"
                + Objects.toString(command.requestedMinor(), "") + "|"
                + imageIds.stream().map(UUID::toString).collect(Collectors.joining(","))).getBytes(StandardCharsets.UTF_8));

        Applied applied = tx.execute(status -> {
            RefundOrderRow order = refunds.lockBuyerOrder(buyerId, orderId);
            if (order == null) {
                throw new OpsException(TradeAftersaleError.NOT_FOUND);
            }
            AftersaleRow replayed = mapper.findByKey(buyerId, idempotencyKey);
            if (replayed != null) {
                if (!replayed.orderId().equals(orderId) || !hash.equals(replayed.requestHash())) {
                    throw new OpsException(TradeAftersaleError.IDEMPOTENCY_CONFLICT);
                }
                return new Applied(replayed.id(), null, false);
            }
            OrderFactsRow row = mapper.findOrderFacts(orderId);
            if (row == null || !TradeSourceEvents.MALL.equals(row.source())) {
                throw new OpsException(TradeAftersaleError.NOT_FOUND);
            }
            OrderFacts facts = facts(order, row);
            Instant now = clock.instant();
            String block = TradeAftersaleRules.reasonBlock(facts, reason, now);
            if ("OPEN_AFTERSALE".equals(block)) {
                throw new OpsException(TradeAftersaleError.ALREADY_OPEN);
            }
            if (block != null) {
                throw new OpsException(TradeAftersaleError.NOT_ELIGIBLE, Map.of("reason", block));
            }
            long refundable = facts.refundableMinor();
            long requested = command.requestedMinor() == null ? refundable : command.requestedMinor();
            if (requested > refundable) {
                throw new OpsException(TradeAftersaleError.AMOUNT_EXCEEDS);
            }
            if (kind == Kind.REFUND_ONLY && requested != refundable) {
                // 仅退款是未发货取消即退：只能把还没退的全部退掉。
                throw new IllegalArgumentException("refund only takes the whole refundable amount");
            }
            List<ImageRow> uploaded = imageIds.isEmpty() ? List.of() : mapper.lockImages(imageIds);
            Map<UUID, ImageRow> byId = uploaded.stream().collect(Collectors.toMap(ImageRow::id, image -> image));
            Map<String, Integer> perSlot = new HashMap<>();
            for (UUID imageId : imageIds) {
                ImageRow image = byId.get(imageId);
                if (image == null || !image.uploadedBy().equals(buyerId) || image.aftersaleId() != null
                        || perSlot.merge(image.slot(), 1, Integer::sum) > MAX_IMAGES_PER_SLOT) {
                    throw new OpsException(TradeAftersaleError.IMAGE_INVALID);
                }
            }
            String number = newNumber(now);
            UUID id = mapper.insertAftersale(number, orderId, buyerId, order.merchantId(), kind.name(), reason.name(),
                    description, requested, "PENDING_REVIEW", now.plus(TradeAftersaleRules.REVIEW_WINDOW),
                    idempotencyKey, hash, now);
            int sort = 0;
            for (UUID imageId : imageIds) {
                mapper.attachImage(imageId, id, sort);
                sort += 10;
            }
            UUID refundId = null;
            String target = "PENDING_REVIEW";
            if (kind == Kind.REFUND_ONLY) {
                try {
                    refundId = refundService.createAftersaleRefund(orderId, requested, "买家申请仅退款 " + number,
                            "BUYER_CANCEL", buyerId, refundKey(id));
                } catch (TradeException exception) {
                    throw translate(exception);
                }
                mapper.saveRefund(id, refundId, requested, null, false, now);
                mapper.changeStatus(id, "PENDING_REVIEW", "REFUNDING", null, now);
                target = "REFUNDING";
            }
            mapper.insertEvent(id, "SUBMIT", null, target, buyerId, now);
            return new Applied(id, refundId, true);
        });
        submitQuietly(applied.refundId());
        return new ApplyResult(read(buyerId, applied.aftersaleId()), applied.created());
    }

    private record Applied(UUID aftersaleId, UUID refundId, boolean created) {
    }

    // ---- 买家：读、撤销、寄回 ----

    public AftersaleView read(UUID buyerId, UUID aftersaleId) {
        requireId(buyerId, aftersaleId);
        AftersaleRow row = mapper.findBuyerAftersale(buyerId, aftersaleId);
        if (row == null) {
            throw new OpsException(TradeAftersaleError.NOT_FOUND);
        }
        return detail(row, Perspective.BUYER);
    }

    public AftersalePageView list(UUID buyerId, String tab, int page, int size) {
        if (buyerId == null) {
            throw new IllegalArgumentException("buyer is required");
        }
        requirePage(page, size);
        List<String> statuses = TradeAftersaleRules.ofTab(tab);
        long total = mapper.countBuyerAftersales(buyerId, statuses);
        List<AftersaleRow> rows = mapper.findBuyerAftersales(buyerId, statuses, size, (page - 1) * size);
        return new AftersalePageView(page, size, total, (long) page * size < total,
                summaries(rows, Perspective.BUYER), counts(mapper.countBuyerByStatus(buyerId)));
    }

    public AftersaleView revoke(UUID buyerId, UUID aftersaleId, int version) {
        requireId(buyerId, aftersaleId);
        tx.executeWithoutResult(status -> {
            AftersaleRow row = lockForBuyer(buyerId, aftersaleId, version);
            if (!"PENDING_REVIEW".equals(row.status()) && !"AWAITING_RETURN".equals(row.status())) {
                throw new OpsException(TradeAftersaleError.STATE_INVALID);
            }
            Instant now = clock.instant();
            mapper.changeStatus(row.id(), row.status(), "REVOKED", null, now);
            mapper.markClosed(row.id(), now);
            mapper.insertEvent(row.id(), "REVOKE", row.status(), "REVOKED", buyerId, now);
        });
        return read(buyerId, aftersaleId);
    }

    /** 填退货运单（待寄回 → 待商家收货），或在商家确认收货前改运单（状态不变）。 */
    public AftersaleView submitReturn(UUID buyerId, UUID aftersaleId, int version, String carrierName,
                                      String trackingNo) {
        requireId(buyerId, aftersaleId);
        String carrier = text(carrierName, TradeOrderService.MAX_CARRIER_LENGTH, false);
        String tracking = trackingNo == null ? "" : trackingNo.trim();
        if (!tracking.matches("^[0-9A-Za-z-]{4," + TradeOrderService.MAX_TRACKING_LENGTH + "}$")) {
            throw new IllegalArgumentException("tracking number is invalid");
        }
        tx.executeWithoutResult(status -> {
            AftersaleRow row = lockForBuyer(buyerId, aftersaleId, version);
            Instant now = clock.instant();
            if ("AWAITING_RETURN".equals(row.status())) {
                mapper.changeStatus(row.id(), "AWAITING_RETURN", "RETURN_SHIPPED",
                        now.plus(TradeAftersaleRules.RECEIVE_WINDOW), now);
                mapper.saveWaybill(row.id(), carrier, tracking, now);
                mapper.insertEvent(row.id(), "SHIP_RETURN", "AWAITING_RETURN", "RETURN_SHIPPED", buyerId, now);
            } else if ("RETURN_SHIPPED".equals(row.status())) {
                // 改运单不改状态和时限，只让版本加一。
                mapper.changeStatus(row.id(), "RETURN_SHIPPED", "RETURN_SHIPPED", row.dueAt(), now);
                mapper.saveWaybill(row.id(), carrier, tracking, now);
                mapper.insertEvent(row.id(), "EDIT_RETURN", "RETURN_SHIPPED", "RETURN_SHIPPED", buyerId, now);
            } else {
                throw new OpsException(TradeAftersaleError.STATE_INVALID);
            }
        });
        return read(buyerId, aftersaleId);
    }

    // ---- 商家 ----

    public AftersalePageView listForMerchant(UUID merchantId, String status, int page, int size) {
        if (merchantId == null) {
            throw new IllegalArgumentException("merchant is required");
        }
        requirePage(page, size);
        List<String> statuses = TradeAftersaleRules.ofStatus(status);
        long total = mapper.countMerchantAftersales(merchantId, statuses);
        List<AftersaleRow> rows = mapper.findMerchantAftersales(merchantId, statuses, size, (page - 1) * size);
        return new AftersalePageView(page, size, total, (long) page * size < total,
                summaries(rows, Perspective.MERCHANT), counts(mapper.countMerchantByStatus(merchantId)));
    }

    public AftersaleView readForMerchant(UUID merchantId, UUID aftersaleId) {
        requireId(merchantId, aftersaleId);
        AftersaleRow row = mapper.findMerchantAftersale(merchantId, aftersaleId);
        if (row == null) {
            throw new OpsException(TradeAftersaleError.NOT_FOUND);
        }
        return detail(row, Perspective.MERCHANT);
    }

    public record ReturnAddress(String contactName, String phone, String address, String note) {
    }

    /**
     * 同意退货：给寄回地址（不填就用默认寄回地址；saveAsDefault 时把这次填的存成默认）。
     */
    public AftersaleView agree(UUID merchantId, UUID operatorId, UUID aftersaleId, int version, ReturnAddress address,
                               boolean saveAsDefault) {
        requireId(merchantId, aftersaleId);
        requireOperator(operatorId);
        ReturnAddress given = address == null || isBlank(address.address()) ? null : validAddress(address);
        tx.executeWithoutResult(status -> {
            AftersaleRow row = lockForMerchant(merchantId, aftersaleId, version);
            if (!"PENDING_REVIEW".equals(row.status()) || !Kind.RETURN_REFUND.name().equals(row.kind())) {
                throw new OpsException(TradeAftersaleError.STATE_INVALID);
            }
            Instant now = clock.instant();
            ReturnAddress use = given;
            if (use == null) {
                ReturnAddressRow saved = mapper.findReturnAddress(merchantId);
                if (saved == null) {
                    throw new OpsException(TradeAftersaleError.RETURN_ADDRESS_REQUIRED);
                }
                use = new ReturnAddress(saved.contactName(), saved.phone(), saved.address(), saved.note());
            } else if (saveAsDefault) {
                mapper.saveReturnAddress(merchantId, use.contactName(), use.phone(), use.address(), use.note(),
                        operatorId, now);
            }
            mapper.changeStatus(row.id(), "PENDING_REVIEW", "AWAITING_RETURN",
                    now.plus(TradeAftersaleRules.RETURN_WINDOW), now);
            mapper.saveAgreement(row.id(), use.contactName(), use.phone(), use.address(), use.note(), now);
            mapper.insertEvent(row.id(), "AGREE", "PENDING_REVIEW", "AWAITING_RETURN", operatorId, now);
        });
        return readForMerchant(merchantId, aftersaleId);
    }

    /** 不同意（审核时）：写理由；不会发起退款。 */
    public AftersaleView reject(UUID merchantId, UUID operatorId, UUID aftersaleId, int version, String reason,
                                String note) {
        requireId(merchantId, aftersaleId);
        requireOperator(operatorId);
        String why = text(reason, MAX_REASON, false);
        String more = text(note, MAX_NOTE, true);
        tx.executeWithoutResult(status -> {
            AftersaleRow row = lockForMerchant(merchantId, aftersaleId, version);
            if (!"PENDING_REVIEW".equals(row.status())) {
                throw new OpsException(TradeAftersaleError.STATE_INVALID);
            }
            Instant now = clock.instant();
            // 先写理由再改状态：表上的约束要求“未同意”的单一定有理由。
            mapper.saveRejection(row.id(), why, more, false, now);
            mapper.changeStatus(row.id(), "PENDING_REVIEW", "REJECTED", null, now);
            mapper.insertEvent(row.id(), "REJECT", "PENDING_REVIEW", "REJECTED", operatorId, now);
        });
        return readForMerchant(merchantId, aftersaleId);
    }

    /**
     * 确认收货并退款：refundMinor 不传是申请金额；少于申请金额时要写说明（比如少了配件）。
     * 买家还没填运单但商家已经收到货（待寄回）时也可以确认。
     */
    public AftersaleView confirmReceipt(UUID merchantId, UUID operatorId, UUID aftersaleId, int version,
                                        Long refundMinor, String note) {
        requireId(merchantId, aftersaleId);
        requireOperator(operatorId);
        String more = text(note, MAX_NOTE, true);
        if (refundMinor != null && refundMinor < 1) {
            throw new IllegalArgumentException("refund amount must be positive");
        }
        if (!refundService.channelReady()) {
            throw new OpsException(TradeAftersaleError.CHANNEL_UNAVAILABLE);
        }
        UUID refundId = tx.execute(status -> {
            AftersaleRow row = lockForMerchant(merchantId, aftersaleId, version);
            if (!"RETURN_SHIPPED".equals(row.status()) && !"AWAITING_RETURN".equals(row.status())) {
                throw new OpsException(TradeAftersaleError.STATE_INVALID);
            }
            long amount = refundMinor == null ? row.requestedMinor() : refundMinor;
            if (amount > row.requestedMinor()) {
                throw new OpsException(TradeAftersaleError.AMOUNT_EXCEEDS);
            }
            if (amount < row.requestedMinor() && more == null) {
                throw new IllegalArgumentException("a note is required when refunding less than requested");
            }
            Instant now = clock.instant();
            UUID created;
            try {
                created = refundService.createAftersaleRefund(row.orderId(), amount,
                        "售后退货退款 " + row.aftersaleNo(), "MERCHANT", operatorId, refundKey(row.id()));
            } catch (TradeException exception) {
                throw translate(exception);
            }
            mapper.saveRefund(row.id(), created, amount, more, true, now);
            mapper.changeStatus(row.id(), row.status(), "REFUNDING", null, now);
            mapper.insertEvent(row.id(), "CONFIRM_RECEIPT", row.status(), "REFUNDING", operatorId, now);
            return created;
        });
        submitQuietly(refundId);
        return readForMerchant(merchantId, aftersaleId);
    }

    /** 验货不通过：写理由和怎么处理寄回的商品（比如按原收货地址退回）；不会发起退款。 */
    public AftersaleView rejectReceipt(UUID merchantId, UUID operatorId, UUID aftersaleId, int version, String reason,
                                       String note) {
        requireId(merchantId, aftersaleId);
        requireOperator(operatorId);
        String why = text(reason, MAX_REASON, false);
        String more = text(note, MAX_NOTE, false);
        tx.executeWithoutResult(status -> {
            AftersaleRow row = lockForMerchant(merchantId, aftersaleId, version);
            if (!"RETURN_SHIPPED".equals(row.status())) {
                throw new OpsException(TradeAftersaleError.STATE_INVALID);
            }
            Instant now = clock.instant();
            mapper.saveRejection(row.id(), why, more, true, now);
            mapper.changeStatus(row.id(), "RETURN_SHIPPED", "RECEIPT_REJECTED", null, now);
            mapper.insertEvent(row.id(), "REJECT_RECEIPT", "RETURN_SHIPPED", "RECEIPT_REJECTED", operatorId, now);
        });
        return readForMerchant(merchantId, aftersaleId);
    }

    public ReturnAddressView readReturnAddress(UUID merchantId) {
        if (merchantId == null) {
            throw new IllegalArgumentException("merchant is required");
        }
        ReturnAddressRow row = mapper.findReturnAddress(merchantId);
        if (row == null) {
            throw new OpsException(TradeAftersaleError.NOT_FOUND);
        }
        return returnAddressView(row);
    }

    public ReturnAddressView saveReturnAddress(UUID merchantId, UUID operatorId, ReturnAddress address) {
        if (merchantId == null) {
            throw new IllegalArgumentException("merchant is required");
        }
        requireOperator(operatorId);
        ReturnAddress valid = validAddress(address);
        return returnAddressView(tx.execute(status -> mapper.saveReturnAddress(merchantId, valid.contactName(),
                valid.phone(), valid.address(), valid.note(), operatorId, clock.instant())));
    }

    // ---- 退款结果（退款服务回推，在它的事务里） ----

    @Override
    public void settled(UUID refundId, boolean succeeded, Instant at) {
        AftersaleRow row = mapper.lockByRefund(refundId);
        if (row == null || !"REFUNDING".equals(row.status())) {
            return;
        }
        String target = succeeded ? "REFUNDED" : "REFUND_FAILED";
        mapper.changeStatus(row.id(), "REFUNDING", target, null, at);
        if (succeeded) {
            mapper.markRefunded(row.id(), at);
        }
        mapper.insertEvent(row.id(), succeeded ? "REFUND_SUCCEEDED" : "REFUND_FAILED", "REFUNDING", target, null, at);
    }

    // ---- 定时：到了处理时限的 ----

    /** 返回这一轮推进了几张。 */
    public int processDeadlines() {
        Instant now = clock.instant();
        int changed = 0;
        for (DueRow due : mapper.findDue(now, DUE_BATCH)) {
            try {
                UUID refundId = tx.execute(status -> advance(due, clock.instant()));
                changed++;
                submitQuietly(refundId);
            } catch (RuntimeException exception) {
                log.error("advancing aftersale {} past its deadline failed", due.id(), exception);
            }
        }
        return changed;
    }

    /** 在事务里推进一张；返回新建的退款（自动退款时），提交后再交给渠道。 */
    private UUID advance(DueRow due, Instant now) {
        RefundOrderRow order = refunds.lockOrder(due.orderId());
        AftersaleRow row = mapper.lockAftersale(due.id());
        if (order == null || row == null || row.dueAt() == null || row.dueAt().isAfter(now)) {
            return null;
        }
        switch (row.status()) {
            case "PENDING_REVIEW" -> {
                ReturnAddressRow saved = mapper.findReturnAddress(row.merchantId());
                mapper.changeStatus(row.id(), "PENDING_REVIEW", "AWAITING_RETURN",
                        now.plus(TradeAftersaleRules.RETURN_WINDOW), now);
                mapper.saveAgreement(row.id(),
                        saved == null ? null : saved.contactName(),
                        saved == null ? null : saved.phone(),
                        saved == null ? null : saved.address(),
                        saved == null ? null : saved.note(), now);
                mapper.insertEvent(row.id(), "AUTO_AGREE", "PENDING_REVIEW", "AWAITING_RETURN", null, now);
                return null;
            }
            case "AWAITING_RETURN" -> {
                mapper.changeStatus(row.id(), "AWAITING_RETURN", "CLOSED", null, now);
                mapper.markClosed(row.id(), now);
                mapper.insertEvent(row.id(), "CLOSE_OVERDUE", "AWAITING_RETURN", "CLOSED", null, now);
                return null;
            }
            case "RETURN_SHIPPED" -> {
                if (!refundService.channelReady()) {
                    return null;
                }
                long refundable = refundService.refundableNow(row.orderId());
                long amount = Math.min(row.requestedMinor(), refundable);
                if (amount < 1) {
                    // 商家已经另外把钱退完了：没有可退的，留给人工，一天后再看。
                    log.error("aftersale {} is due for an automatic refund but nothing is left to refund",
                            row.aftersaleNo());
                    mapper.changeStatus(row.id(), "RETURN_SHIPPED", "RETURN_SHIPPED", now.plus(Duration.ofDays(1)), now);
                    return null;
                }
                UUID refundId = refundService.createAftersaleRefund(row.orderId(), amount,
                        "售后超时自动退款 " + row.aftersaleNo(), "SOURCE", null, refundKey(row.id()));
                mapper.saveRefund(row.id(), refundId, amount, null, false, now);
                mapper.changeStatus(row.id(), "RETURN_SHIPPED", "REFUNDING", null, now);
                mapper.insertEvent(row.id(), "AUTO_REFUND", "RETURN_SHIPPED", "REFUNDING", null, now);
                return refundId;
            }
            default -> {
                return null;
            }
        }
    }

    // ---- 订单页的售后入口（给 TradeOrderService 用） ----

    static boolean applicable(String source, String status, Instant completedAt, boolean noReasonReturn,
                              boolean open, long count, long refundableMinor, Instant now) {
        OrderFacts facts = new OrderFacts(TradeSourceEvents.MALL.equals(source), TradeOrderStatus.valueOf(status),
                completedAt, noReasonReturn, open, count, refundableMinor);
        return TradeAftersaleRules.applicable(facts, now);
    }

    // ---- 组装 ----

    private enum Perspective { BUYER, MERCHANT }

    private AftersaleView detail(AftersaleRow row, Perspective perspective) {
        List<AftersaleEventView> timeline = mapper.findEvents(row.id()).stream()
                .map(event -> new AftersaleEventView(event.action(), event.toStatus(), event.occurredAt()))
                .toList();
        boolean reapply = false;
        if (perspective == Perspective.BUYER && CLOSED.contains(row.status())) {
            reapply = tx.execute(status -> {
                RefundOrderRow order = refunds.lockBuyerOrder(row.buyerId(), row.orderId());
                OrderFactsRow facts = order == null ? null : mapper.findOrderFacts(row.orderId());
                return facts != null && TradeAftersaleRules.applicable(facts(order, facts), clock.instant());
            });
        }
        return assemble(List.of(row), perspective, Map.of(row.id(), timeline), Map.of(row.id(), reapply)).getFirst();
    }

    private List<AftersaleView> summaries(List<AftersaleRow> rows, Perspective perspective) {
        Map<UUID, Boolean> reapply = new HashMap<>();
        for (AftersaleRow row : rows) {
            reapply.put(row.id(), perspective == Perspective.BUYER && CLOSED.contains(row.status()));
        }
        return assemble(rows, perspective, Map.of(), reapply);
    }

    private List<AftersaleView> assemble(List<AftersaleRow> rows, Perspective perspective,
                                         Map<UUID, List<AftersaleEventView>> timelines, Map<UUID, Boolean> reapply) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<TradeOrderLineView>> lines = lines(rows.stream().map(AftersaleRow::orderId).distinct().toList());
        Map<UUID, List<AftersaleImageView>> pictures = mapper.findImages(rows.stream().map(AftersaleRow::id).toList())
                .stream()
                .collect(Collectors.groupingBy(TradeAftersaleMapper.ImageRefRow::aftersaleId,
                        Collectors.mapping(image -> new AftersaleImageView(image.id(), image.slot()),
                                Collectors.toList())));
        List<AftersaleView> views = new ArrayList<>();
        for (AftersaleRow row : rows) {
            AftersaleRefundView refund = null;
            if (row.refundId() != null) {
                RefundStateRow state = mapper.findRefundState(row.refundId());
                if (state != null) {
                    refund = new AftersaleRefundView(state.status(), state.amountMinor(), state.succeededAt(),
                            mapper.findTransactionId(row.orderId()));
                }
            }
            boolean buyer = perspective == Perspective.BUYER;
            String status = row.status();
            AftersaleActionsView actions = new AftersaleActionsView(
                    buyer && ("PENDING_REVIEW".equals(status) || "AWAITING_RETURN".equals(status)),
                    buyer && "AWAITING_RETURN".equals(status),
                    buyer && "RETURN_SHIPPED".equals(status),
                    buyer && Boolean.TRUE.equals(reapply.get(row.id())),
                    !buyer && "PENDING_REVIEW".equals(status),
                    !buyer && ("RETURN_SHIPPED".equals(status) || "AWAITING_RETURN".equals(status)),
                    !buyer && "RETURN_SHIPPED".equals(status));
            AftersaleReturnAddressView address = row.reviewedAt() != null && row.returnAddress() != null
                    ? new AftersaleReturnAddressView(row.returnContact(), row.returnPhone(), row.returnAddress(),
                            row.returnNote())
                    : null;
            AftersaleWaybillView waybill = row.returnTrackingNo() == null ? null
                    : new AftersaleWaybillView(row.returnCarrier(), row.returnTrackingNo(), row.returnShippedAt());
            views.add(new AftersaleView(
                    row.id(),
                    row.aftersaleNo(),
                    row.orderId(),
                    row.orderNo(),
                    row.kind(),
                    row.reason(),
                    row.description(),
                    status,
                    new AftersaleMerchantView(row.merchantId(), row.merchantName(), blankToNull(row.aftersaleParty())),
                    lines.getOrDefault(row.orderId(), List.of()),
                    row.paidMinor(),
                    row.requestedMinor(),
                    row.refundMinor(),
                    address,
                    waybill,
                    row.rejectReason(),
                    row.merchantNote(),
                    refund,
                    pictures.getOrDefault(row.id(), List.of()),
                    row.dueAt(),
                    row.submittedAt(),
                    row.reviewedAt(),
                    row.returnShippedAt(),
                    row.receivedAt(),
                    row.refundedAt(),
                    row.closedAt(),
                    timelines.getOrDefault(row.id(), List.of()),
                    actions,
                    row.version()));
        }
        return views;
    }

    private Map<UUID, List<TradeOrderLineView>> lines(Collection<UUID> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        return mapper.findLines(List.copyOf(orderIds)).stream()
                .collect(Collectors.groupingBy(LineRow::orderId, Collectors.mapping(line -> new TradeOrderLineView(
                        line.id(),
                        line.productId(),
                        line.offerId(),
                        line.productName(),
                        line.skuName(),
                        secureMedia(line.coverUrl(), line.productName()),
                        line.unitAmountMinor(),
                        line.quantity(),
                        line.subtotalMinor()), Collectors.toList())));
    }

    private record OrderHead(String orderNo, AftersaleMerchantView merchant) {
    }

    private OrderHead orderHead(UUID orderId) {
        TradeAftersaleMapper.OrderHeadRow head = mapper.findOrderHead(orderId);
        return new OrderHead(head.orderNo(),
                new AftersaleMerchantView(head.merchantId(), head.merchantName(), blankToNull(head.aftersaleParty())));
    }

    private static AftersaleCountsView counts(List<StatusCountRow> rows) {
        long doing = 0;
        long done = 0;
        long closed = 0;
        List<String> doingStatuses = TradeAftersaleRules.ofTab("DOING");
        for (StatusCountRow row : rows) {
            if (doingStatuses.contains(row.status())) {
                doing += row.total();
            } else if ("REFUNDED".equals(row.status())) {
                done += row.total();
            } else {
                closed += row.total();
            }
        }
        return new AftersaleCountsView(doing, done, closed);
    }

    private OrderFacts facts(RefundOrderRow order, OrderFactsRow row) {
        return new OrderFacts(
                TradeSourceEvents.MALL.equals(row.source()),
                TradeOrderStatus.valueOf(order.status()),
                row.completedAt(),
                row.noReasonReturn(),
                mapper.countOpenForOrder(order.id()) > 0,
                mapper.countForOrder(order.id()),
                order.paidMinor() - order.refundedMinor() - refunds.sumReservedRefunds(order.id()));
    }

    private AftersaleRow lockForBuyer(UUID buyerId, UUID aftersaleId, int version) {
        AftersaleRow found = mapper.findBuyerAftersale(buyerId, aftersaleId);
        if (found == null) {
            throw new OpsException(TradeAftersaleError.NOT_FOUND);
        }
        refunds.lockOrder(found.orderId());
        AftersaleRow row = mapper.lockAftersale(aftersaleId);
        if (row.version() != version) {
            throw new OpsException(TradeAftersaleError.VERSION_CONFLICT);
        }
        return row;
    }

    private AftersaleRow lockForMerchant(UUID merchantId, UUID aftersaleId, int version) {
        AftersaleRow found = mapper.findMerchantAftersale(merchantId, aftersaleId);
        if (found == null) {
            throw new OpsException(TradeAftersaleError.NOT_FOUND);
        }
        refunds.lockOrder(found.orderId());
        AftersaleRow row = mapper.lockAftersale(aftersaleId);
        if (row.version() != version) {
            throw new OpsException(TradeAftersaleError.VERSION_CONFLICT);
        }
        return row;
    }

    private void submitQuietly(UUID refundId) {
        if (refundId == null) {
            return;
        }
        try {
            refundService.submit(refundId);
        } catch (RuntimeException exception) {
            // 退款单已经建好；定时任务会用同一个退款单号重新提交。
            log.error("submitting aftersale refund {} failed; the scheduled job will retry", refundId, exception);
        }
    }

    private static OpsException translate(TradeException exception) {
        return switch (exception.error()) {
            case REFUND_EXCEEDS_PAID -> new OpsException(TradeAftersaleError.AMOUNT_EXCEEDS);
            case REFUND_LIMIT_REACHED -> new OpsException(TradeAftersaleError.NOT_ELIGIBLE,
                    Map.of("reason", "LIMIT_REACHED"));
            case NOT_FOUND -> new OpsException(TradeAftersaleError.NOT_FOUND);
            default -> new OpsException(TradeAftersaleError.STATE_INVALID);
        };
    }

    /** 同一张售后单只会发起一笔退款：退款的幂等键由售后单 ID 定。 */
    static UUID refundKey(UUID aftersaleId) {
        return UUID.nameUUIDFromBytes(("aftersale-refund:" + aftersaleId).getBytes(StandardCharsets.UTF_8));
    }

    private String newNumber(Instant now) {
        String day = DAY.format(now.atZone(BEIJING));
        for (int attempt = 0; attempt < 5; attempt++) {
            String candidate = "AS" + day + numberSuffix.get();
            if (mapper.countAftersaleNo(candidate) == 0) {
                return candidate;
            }
        }
        throw new IllegalStateException("could not pick an unused aftersale number");
    }

    private static ReturnAddressView returnAddressView(ReturnAddressRow row) {
        return new ReturnAddressView(row.contactName(), row.phone(), row.address(), row.note(), row.version(),
                row.updatedAt());
    }

    private static ReturnAddress validAddress(ReturnAddress address) {
        if (address == null) {
            throw new IllegalArgumentException("return address is required");
        }
        String contact = text(address.contactName(), 40, false);
        String phone = address.phone() == null ? "" : address.phone().trim();
        if (!phone.matches("^[0-9+\\-]{5,20}$")) {
            throw new IllegalArgumentException("return phone is invalid");
        }
        return new ReturnAddress(contact, phone, text(address.address(), 200, false), text(address.note(), MAX_NOTE, true));
    }

    /** 去掉首尾空白；空的按 optional 给 null 或拒绝；超长、含控制字符（换行除外）拒绝。 */
    private static String text(String value, int max, boolean optional) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            if (optional) {
                return null;
            }
            throw new IllegalArgumentException("text is required");
        }
        if (trimmed.codePointCount(0, trimmed.length()) > max
                || trimmed.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n')) {
            throw new IllegalArgumentException("text is invalid");
        }
        return trimmed;
    }

    private static <E extends Enum<E>> E parse(String code, java.util.function.Function<String, E> parser, String what) {
        try {
            return parser.apply(code == null ? "" : code.trim());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(what + " is invalid");
        }
    }

    private static void requireId(UUID owner, UUID id) {
        if (owner == null || id == null) {
            throw new IllegalArgumentException("owner and id are required");
        }
    }

    private static void requireOperator(UUID operatorId) {
        if (operatorId == null) {
            throw new IllegalArgumentException("operator is required");
        }
    }

    private static void requirePage(int page, int size) {
        if (page < 1 || page > MAX_PAGE || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page is invalid");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 按文件头判断图片类型：JPEG、PNG、WebP；别的一律不收。 */
    static String sniff(byte[] content) {
        if (content.length >= 3 && (content[0] & 0xFF) == 0xFF && (content[1] & 0xFF) == 0xD8
                && (content[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (content.length >= 8 && (content[0] & 0xFF) == 0x89 && content[1] == 'P' && content[2] == 'N'
                && content[3] == 'G') {
            return "image/png";
        }
        if (content.length >= 12 && content[0] == 'R' && content[1] == 'I' && content[2] == 'F' && content[3] == 'F'
                && content[8] == 'W' && content[9] == 'E' && content[10] == 'B' && content[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private static String extension(String contentType) {
        return switch (contentType) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> "jpg";
        };
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static TradeMediaView secureMedia(String url, String alt) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    ? new TradeMediaView(uri.toString(), alt)
                    : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
