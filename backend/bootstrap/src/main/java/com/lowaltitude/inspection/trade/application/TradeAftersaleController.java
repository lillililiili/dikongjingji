package com.lowaltitude.inspection.trade.application;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.trade.application.TradeAftersaleService.ApplyCommand;
import com.lowaltitude.inspection.trade.application.TradeAftersaleService.ApplyResult;
import com.lowaltitude.inspection.trade.application.TradeAftersaleService.StoredImage;
import com.lowaltitude.inspection.trade.application.TradeAftersaleViews.AftersaleEligibilityView;
import com.lowaltitude.inspection.trade.application.TradeAftersaleViews.AftersaleImageUploadView;
import com.lowaltitude.inspection.trade.application.TradeAftersaleViews.AftersalePageView;
import com.lowaltitude.inspection.trade.application.TradeAftersaleViews.AftersaleView;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 买家的商品售后（S4-2，契约 contracts/openapi-trade-aftersale.yaml）：看能申请什么、上传凭证、申请、列表、详情、撤销、
 * 填和改退货运单。要 CUSTOMER 角色；不是自己的订单和售后单一律当作不存在。
 */
@RestController
public final class TradeAftersaleController {

    private final TradeAftersaleService aftersales;

    public TradeAftersaleController(TradeAftersaleService aftersales) {
        this.aftersales = aftersales;
    }

    @GetMapping("/api/v1/trade/orders/{orderId}/aftersale-eligibility")
    public AftersaleEligibilityView eligibility(Authentication authentication, @PathVariable UUID orderId) {
        return aftersales.eligibility(TradeAftersaleSubjects.customer(authentication), orderId);
    }

    @PostMapping("/api/v1/trade/orders/{orderId}/aftersales")
    public ResponseEntity<AftersaleView> apply(
            Authentication authentication,
            @PathVariable UUID orderId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody ApplyAftersaleRequest request
    ) {
        ApplyResult result = aftersales.apply(TradeAftersaleSubjects.customer(authentication), orderId, idempotencyKey,
                new ApplyCommand(request.kind(), request.reason(), request.description(), request.requestedMinor(),
                        request.imageIds()));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .eTag(Integer.toString(result.aftersale().version()))
                .body(result.aftersale());
    }

    @GetMapping("/api/v1/trade/aftersales")
    public AftersalePageView list(
            Authentication authentication,
            @RequestParam(required = false) String tab,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return aftersales.list(TradeAftersaleSubjects.customer(authentication), tab, page, size);
    }

    @GetMapping("/api/v1/trade/aftersales/{aftersaleId}")
    public ResponseEntity<AftersaleView> read(Authentication authentication, @PathVariable UUID aftersaleId) {
        AftersaleView view = aftersales.read(TradeAftersaleSubjects.customer(authentication), aftersaleId);
        return ResponseEntity.ok().eTag(Integer.toString(view.version())).body(view);
    }

    @PostMapping("/api/v1/trade/aftersales/{aftersaleId}/revoke")
    public ResponseEntity<AftersaleView> revoke(
            Authentication authentication,
            @PathVariable UUID aftersaleId,
            @RequestHeader("If-Match") String ifMatch
    ) {
        AftersaleView view = aftersales.revoke(TradeAftersaleSubjects.customer(authentication), aftersaleId,
                TradeOrderController.version(ifMatch));
        return ResponseEntity.ok().eTag(Integer.toString(view.version())).body(view);
    }

    @PostMapping("/api/v1/trade/aftersales/{aftersaleId}/return-shipment")
    public ResponseEntity<AftersaleView> submitReturn(
            Authentication authentication,
            @PathVariable UUID aftersaleId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestBody ReturnShipmentRequest request
    ) {
        AftersaleView view = aftersales.submitReturn(TradeAftersaleSubjects.customer(authentication), aftersaleId,
                TradeOrderController.version(ifMatch), request.carrierName(), request.trackingNo());
        return ResponseEntity.ok().eTag(Integer.toString(view.version())).body(view);
    }

    @PostMapping(path = "/api/v1/trade/aftersale-images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AftersaleImageUploadView> upload(
            Authentication authentication,
            @RequestParam("slot") String slot,
            @RequestPart("file") MultipartFile file
    ) throws IOException {
        AftersaleImageUploadView view = aftersales.uploadImage(TradeAftersaleSubjects.customer(authentication), slot,
                file.getBytes());
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @GetMapping("/api/v1/trade/aftersale-images/{imageId}")
    public ResponseEntity<byte[]> image(Authentication authentication, @PathVariable UUID imageId) {
        return imageResponse(aftersales.readOwnImage(TradeAftersaleSubjects.customer(authentication), imageId));
    }

    static ResponseEntity<byte[]> imageResponse(StoredImage image) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .body(image.content());
    }

    record ApplyAftersaleRequest(String kind, String reason, String description, Long requestedMinor,
                                 List<UUID> imageIds) {
    }

    record ReturnShipmentRequest(String carrierName, String trackingNo) {
    }
}
