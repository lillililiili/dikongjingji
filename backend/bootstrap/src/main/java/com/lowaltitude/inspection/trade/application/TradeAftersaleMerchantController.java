package com.lowaltitude.inspection.trade.application;

import java.util.UUID;

import com.lowaltitude.inspection.trade.application.TradeAftersaleService.ReturnAddress;
import com.lowaltitude.inspection.trade.application.TradeAftersaleViews.AftersalePageView;
import com.lowaltitude.inspection.trade.application.TradeAftersaleViews.AftersaleView;
import com.lowaltitude.inspection.trade.application.TradeAftersaleViews.ReturnAddressView;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家工作台的售后（S4-2，契约 contracts/openapi-trade-aftersale.yaml）：本机构卖出的商品的售后单、同意（给寄回地址）、
 * 拒绝（写理由）、确认收货并退款、验货不通过、默认寄回地址。要 PROVIDER_OPERATOR；机构只从令牌里取。
 * 平台不在这里裁决：这些都是商家（售后责任方）自己的处理。
 */
@RestController
public final class TradeAftersaleMerchantController {

    private final TradeAftersaleService aftersales;

    public TradeAftersaleMerchantController(TradeAftersaleService aftersales) {
        this.aftersales = aftersales;
    }

    @GetMapping("/api/v1/provider/trade/aftersales")
    public AftersalePageView list(
            Authentication authentication,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return aftersales.listForMerchant(
                TradeAftersaleSubjects.providerOperator(authentication).organizationId(), status, page, size);
    }

    @GetMapping("/api/v1/provider/trade/aftersales/{aftersaleId}")
    public ResponseEntity<AftersaleView> read(Authentication authentication, @PathVariable UUID aftersaleId) {
        AftersaleView view = aftersales.readForMerchant(
                TradeAftersaleSubjects.providerOperator(authentication).organizationId(), aftersaleId);
        return ResponseEntity.ok().eTag(Integer.toString(view.version())).body(view);
    }

    @PostMapping("/api/v1/provider/trade/aftersales/{aftersaleId}/approval")
    public ResponseEntity<AftersaleView> agree(
            Authentication authentication,
            @PathVariable UUID aftersaleId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestBody AgreeRequest request
    ) {
        TradeAftersaleSubjects.Subject operator = TradeAftersaleSubjects.providerOperator(authentication);
        AftersaleView view = aftersales.agree(operator.organizationId(), operator.subjectId(), aftersaleId,
                TradeOrderController.version(ifMatch),
                new ReturnAddress(request.contactName(), request.phone(), request.address(), request.note()),
                Boolean.TRUE.equals(request.saveAsDefault()));
        return ResponseEntity.ok().eTag(Integer.toString(view.version())).body(view);
    }

    @PostMapping("/api/v1/provider/trade/aftersales/{aftersaleId}/rejection")
    public ResponseEntity<AftersaleView> reject(
            Authentication authentication,
            @PathVariable UUID aftersaleId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestBody RejectRequest request
    ) {
        TradeAftersaleSubjects.Subject operator = TradeAftersaleSubjects.providerOperator(authentication);
        AftersaleView view = aftersales.reject(operator.organizationId(), operator.subjectId(), aftersaleId,
                TradeOrderController.version(ifMatch), request.reason(), request.note());
        return ResponseEntity.ok().eTag(Integer.toString(view.version())).body(view);
    }

    @PostMapping("/api/v1/provider/trade/aftersales/{aftersaleId}/receipt")
    public ResponseEntity<AftersaleView> confirmReceipt(
            Authentication authentication,
            @PathVariable UUID aftersaleId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestBody ReceiptRequest request
    ) {
        TradeAftersaleSubjects.Subject operator = TradeAftersaleSubjects.providerOperator(authentication);
        AftersaleView view = aftersales.confirmReceipt(operator.organizationId(), operator.subjectId(), aftersaleId,
                TradeOrderController.version(ifMatch), request.refundMinor(), request.note());
        return ResponseEntity.ok().eTag(Integer.toString(view.version())).body(view);
    }

    @PostMapping("/api/v1/provider/trade/aftersales/{aftersaleId}/receipt-rejection")
    public ResponseEntity<AftersaleView> rejectReceipt(
            Authentication authentication,
            @PathVariable UUID aftersaleId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestBody RejectRequest request
    ) {
        TradeAftersaleSubjects.Subject operator = TradeAftersaleSubjects.providerOperator(authentication);
        AftersaleView view = aftersales.rejectReceipt(operator.organizationId(), operator.subjectId(), aftersaleId,
                TradeOrderController.version(ifMatch), request.reason(), request.note());
        return ResponseEntity.ok().eTag(Integer.toString(view.version())).body(view);
    }

    @GetMapping("/api/v1/provider/trade/aftersales/{aftersaleId}/images/{imageId}")
    public ResponseEntity<byte[]> image(Authentication authentication, @PathVariable UUID aftersaleId,
                                        @PathVariable UUID imageId) {
        return TradeAftersaleController.imageResponse(aftersales.readMerchantImage(
                TradeAftersaleSubjects.providerOperator(authentication).organizationId(), aftersaleId, imageId));
    }

    @GetMapping("/api/v1/provider/trade/return-address")
    public ReturnAddressView returnAddress(Authentication authentication) {
        return aftersales.readReturnAddress(TradeAftersaleSubjects.providerOperator(authentication).organizationId());
    }

    @PutMapping("/api/v1/provider/trade/return-address")
    public ReturnAddressView saveReturnAddress(Authentication authentication, @RequestBody ReturnAddressRequest request) {
        TradeAftersaleSubjects.Subject operator = TradeAftersaleSubjects.providerOperator(authentication);
        return aftersales.saveReturnAddress(operator.organizationId(), operator.subjectId(),
                new ReturnAddress(request.contactName(), request.phone(), request.address(), request.note()));
    }

    record AgreeRequest(String contactName, String phone, String address, String note, Boolean saveAsDefault) {
    }

    record RejectRequest(String reason, String note) {
    }

    record ReceiptRequest(Long refundMinor, String note) {
    }

    record ReturnAddressRequest(String contactName, String phone, String address, String note) {
    }
}
