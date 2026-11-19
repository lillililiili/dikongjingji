package com.lowaltitude.inspection.mall.application;

import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.account.application.AccountAddressService;
import com.lowaltitude.inspection.account.application.AddressView;
import com.lowaltitude.inspection.account.application.CustomerAccount;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 购物车和确认订单的预览（契约 contracts/openapi-mall-cart.yaml）。都要登录，且要客户角色。
 * 账号只从令牌里取；不是自己的购物车行一律当作不存在。这一步没有“提交订单”。
 */
@RestController
public final class MallCartController {

    private final MallCartService carts;
    private final AccountAddressService addresses;

    public MallCartController(MallCartService carts, AccountAddressService addresses) {
        this.carts = carts;
        this.addresses = addresses;
    }

    @GetMapping("/api/v1/mall/cart")
    public CartView cart(Authentication authentication, @RequestParam(required = false) String cityCode) {
        return carts.read(CustomerAccount.of(authentication), trim(cityCode));
    }

    @GetMapping("/api/v1/mall/cart/count")
    public CartCountView count(Authentication authentication, @RequestParam(required = false) String cityCode) {
        return carts.count(CustomerAccount.of(authentication), trim(cityCode));
    }

    @PostMapping("/api/v1/mall/cart/items")
    public ResponseEntity<CartAddedView> add(Authentication authentication, @RequestBody AddRequest request) {
        CartAddedView added = carts.add(
                CustomerAccount.of(authentication),
                request.offerId(),
                request.quantity() == null ? 0 : request.quantity(),
                trim(request.cityCode()));
        return ResponseEntity.status(201).body(added);
    }

    @PutMapping("/api/v1/mall/cart/items/{cartItemId}")
    public CartQuantityView setQuantity(
            Authentication authentication,
            @PathVariable UUID cartItemId,
            @RequestBody QuantityRequest request
    ) {
        return carts.setQuantity(
                CustomerAccount.of(authentication),
                cartItemId,
                request.quantity() == null ? 0 : request.quantity());
    }

    @PostMapping("/api/v1/mall/cart/remove")
    public CartRemovedView remove(Authentication authentication, @RequestBody RemoveRequest request) {
        return carts.remove(CustomerAccount.of(authentication), request.cartItemIds());
    }

    @PostMapping("/api/v1/mall/cart/remove-invalid")
    public CartRemovedView removeInvalid(Authentication authentication, @RequestBody CityRequest request) {
        return carts.removeInvalid(CustomerAccount.of(authentication), trim(request.cityCode()));
    }

    /**
     * 确认订单页的内容。快递配送时带上收货地址：指定了 addressId 用那一条，否则用默认地址，都没有时为空，
     * 页面据此提示先添加地址。
     */
    @GetMapping("/api/v1/mall/checkout-preview")
    public CheckoutPreviewView preview(
            Authentication authentication,
            @RequestParam(required = false) String cityCode,
            @RequestParam(required = false) List<UUID> cartItemIds,
            @RequestParam(required = false) UUID offerId,
            @RequestParam(required = false) Integer quantity,
            @RequestParam(required = false) String deliveryMode,
            @RequestParam(required = false) UUID addressId,
            @RequestParam(required = false) UUID inquiryQuoteId
    ) {
        UUID accountId = CustomerAccount.of(authentication);
        CheckoutPreview preview = carts.preview(
                accountId, trim(cityCode), cartItemIds, offerId, quantity, deliveryMode, inquiryQuoteId);
        AddressView address = preview.deliveryMode() == DeliveryMode.EXPRESS
                ? addresses.findForUse(accountId, addressId).orElse(null)
                : null;
        return CheckoutPreviewView.of(preview, address);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    record AddRequest(UUID offerId, Integer quantity, String cityCode) {
    }

    record QuantityRequest(Integer quantity) {
    }

    record RemoveRequest(List<UUID> cartItemIds) {
    }

    record CityRequest(String cityCode) {
    }

    record CheckoutPreviewView(
            MallMerchantView merchant,
            List<CheckoutLineView> lines,
            int pieceCount,
            String fulfilledFrom,
            List<DeliveryOptionView> deliveryOptions,
            DeliveryMode deliveryMode,
            AddressView address,
            CheckoutAmountsView amounts,
            List<CheckoutBlocker> blockers
    ) {
        static CheckoutPreviewView of(CheckoutPreview preview, AddressView address) {
            List<CheckoutBlocker> blockers = new java.util.ArrayList<>();
            if (preview.deliveryMode() == null) {
                blockers.add(CheckoutBlocker.NO_DELIVERY_OPTION);
            } else if (preview.deliveryMode() == DeliveryMode.EXPRESS && address == null) {
                blockers.add(CheckoutBlocker.ADDRESS_REQUIRED);
            }
            return new CheckoutPreviewView(
                    preview.merchant(),
                    preview.lines(),
                    preview.pieceCount(),
                    preview.fulfilledFrom(),
                    preview.deliveryOptions(),
                    preview.deliveryMode(),
                    address,
                    preview.amounts(),
                    List.copyOf(blockers));
        }
    }

    /** 这一单还差什么才能提交。 */
    enum CheckoutBlocker {
        /** 快递配送但还没有收货地址 */
        ADDRESS_REQUIRED,
        /** 几样商品没有共同的配送方式，要分开结算 */
        NO_DELIVERY_OPTION
    }
}
