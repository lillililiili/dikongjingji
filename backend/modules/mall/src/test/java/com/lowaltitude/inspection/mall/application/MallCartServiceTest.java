package com.lowaltitude.inspection.mall.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import com.lowaltitude.inspection.mall.application.MallCartException.Error;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallCartMapper;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallCartMapper.AddedRow;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallCartMapper.CartRow;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallCartMapper.OfferRow;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/**
 * 购物车和结算预览的规则测试。mapper 换成内存实现（按 SQL 的条件和 V002 的约束写：一个账号一个供给一行、数量 1–99），
 * 时钟可以往前拨，用来排出“最近加入或改数量”的先后。SQL 文本本身由 MallCartMapperContractTest 检查。
 */
class MallCartServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-17T04:00:00Z");
    private static final String DONGYING = "370500";
    private static final String JINAN = "370100";

    private static final UUID ACCOUNT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_ACCOUNT = UUID.fromString("11111111-1111-1111-1111-222222222222");
    private static final UUID FACTORY = UUID.fromString("c1000000-0000-4000-8000-000000000001");
    private static final UUID STORE = UUID.fromString("c1000000-0000-4000-8000-000000000003");

    private final InMemoryMapper mapper = new InMemoryMapper();
    private final MovableClock clock = new MovableClock();
    private final MallCartService service = new MallCartService(mapper, clock);

    // ---- 加入购物车 ----

    @Test
    void addingAnOfferCreatesALineAndAnswersTheNewCartCount() {
        Offer robot = mapper.offer(buy(FACTORY, 129900));

        CartAddedView added = service.add(ACCOUNT, robot.id, 2, DONGYING);

        assertThat(mapper.lines).singleElement().satisfies(line -> {
            assertThat(line.id).isEqualTo(added.cartItemId());
            assertThat(line.accountId).isEqualTo(ACCOUNT);
            assertThat(line.offerId).isEqualTo(robot.id);
            assertThat(line.quantity).isEqualTo(2);
            assertThat(line.updatedAt).isEqualTo(NOW);
        });
        assertThat(added.quantity()).isEqualTo(2);
        assertThat(added.cartCount()).isEqualTo(1);
    }

    // 同一账号的加购排队，两个同时到的请求不能都通过“没到 50 行”的检查。
    @Test
    void addingTakesTheAccountLockBeforeCountingLines() {
        Offer robot = mapper.offer(buy(FACTORY, 129900));

        service.add(ACCOUNT, robot.id, 1, DONGYING);

        assertThat(mapper.locks).containsExactly("mall-cart:" + ACCOUNT);
    }

    @Test
    void addingTheSameOfferAgainAddsToTheQuantityAndStopsAtNinetyNine() {
        Offer robot = mapper.offer(buy(FACTORY, 129900));
        CartAddedView first = service.add(ACCOUNT, robot.id, 50, DONGYING);

        CartAddedView second = service.add(ACCOUNT, robot.id, 30, DONGYING);
        CartAddedView third = service.add(ACCOUNT, robot.id, 60, DONGYING);

        assertThat(second.cartItemId()).isEqualTo(first.cartItemId());
        assertThat(second.quantity()).isEqualTo(80);
        assertThat(third.quantity()).isEqualTo(99);
        // 数量合并不是新的一行，角标还是 1。
        assertThat(third.cartCount()).isEqualTo(1);
        assertThat(mapper.lines).hasSize(1);
    }

    @Test
    void cartReportsCurrentSellableQuantitySoTheBuyerCanReduceAnExistingLine() {
        Offer robot = mapper.offer(buy(FACTORY, 129900).stock(3));
        CartAddedView added = service.add(ACCOUNT, robot.id, 3, DONGYING);
        robot.stock(1);
        CartItemView item = service.read(ACCOUNT, DONGYING).groups().getFirst().items().getFirst();
        assertThat(item.quantity()).isEqualTo(3);
        assertThat(item.stockQuantity()).isEqualTo(1);
        service.setQuantity(ACCOUNT, added.cartItemId(), 1);
        assertThat(service.preview(ACCOUNT, DONGYING, List.of(added.cartItemId()), null, null, "EXPRESS")
                .amounts().goodsMinor()).isEqualTo(129900);
        robot.stock(null);
        assertThat(service.read(ACCOUNT, DONGYING).groups().getFirst().items().getFirst().stockQuantity()).isNull();
    }

    // cart 1.2.0：库存为 0 不能加；加上购物车里已有的数量不能超过现货；不跟踪库存的不受限。
    @Test
    void stockLimitsWhatCanBeAddedAndASoldOutLineIsInvalid() {
        Offer soldOut = mapper.offer(buy(FACTORY, 129900).stock(0));
        Offer three = mapper.offer(buy(STORE, 738000).stock(3));
        Offer untracked = mapper.offer(buy(STORE, 1000).stock(null));

        assertRejected(() -> service.add(ACCOUNT, soldOut.id, 1, DONGYING), Error.OUT_OF_STOCK);
        service.add(ACCOUNT, three.id, 2, DONGYING);
        assertRejected(() -> service.add(ACCOUNT, three.id, 2, DONGYING), Error.OUT_OF_STOCK);
        assertThat(service.add(ACCOUNT, three.id, 1, DONGYING).quantity()).isEqualTo(3);
        assertThat(service.add(ACCOUNT, untracked.id, 99, DONGYING).quantity()).isEqualTo(99);

        assertRejected(() -> service.preview(ACCOUNT, DONGYING, null, three.id, 4, "EXPRESS"),
                Error.ITEM_UNAVAILABLE);
        assertThat(service.preview(ACCOUNT, DONGYING, null, three.id, 3, "EXPRESS").amounts().goodsMinor())
                .isEqualTo(3 * 738000L);

        three.stockQuantity = 0;
        assertThat(service.read(ACCOUNT, DONGYING).invalidItems())
                .extracting(CartItemView::offerId, CartItemView::invalidReason)
                .containsExactly(tuple(three.id, CartInvalidReason.OUT_OF_STOCK));
    }

    @Test
    void rentalInquiryAndUnpricedOffersAreNotPurchasable() {
        Offer rental = mapper.offer(buy(FACTORY, 38000).saleMode("RENT").unit("DAY"));
        Offer halfDay = mapper.offer(buy(FACTORY, 20000).saleMode("RENT").unit("HALF_DAY"));
        Offer inquiry = mapper.offer(buy(FACTORY, null).saleMode("INQUIRY"));
        Offer unpriced = mapper.offer(buy(FACTORY, null));
        Offer perDay = mapper.offer(buy(FACTORY, 9900).unit("DAY"));

        for (Offer offer : List.of(rental, halfDay, inquiry, unpriced, perDay)) {
            assertRejected(() -> service.add(ACCOUNT, offer.id, 1, DONGYING), Error.NOT_PURCHASABLE);
        }
        assertThat(mapper.lines).isEmpty();
    }

    @Test
    void anOfferOfAnotherCityIsNotSoldHereButAnOfferWithoutACityIsSoldEverywhere() {
        Offer jinanOnly = mapper.offer(buy(FACTORY, 129900).city(JINAN));
        Offer everywhere = mapper.offer(buy(FACTORY, 129900).city(null));
        Offer blankCity = mapper.offer(buy(STORE, 129900).city(" "));

        assertRejected(() -> service.add(ACCOUNT, jinanOnly.id, 1, DONGYING), Error.NOT_SOLD_IN_CITY);
        assertThat(service.add(ACCOUNT, jinanOnly.id, 1, JINAN).quantity()).isEqualTo(1);
        assertThat(service.add(ACCOUNT, everywhere.id, 1, DONGYING).quantity()).isEqualTo(1);
        assertThat(service.add(ACCOUNT, blankCity.id, 1, DONGYING).quantity()).isEqualTo(1);
    }

    @Test
    void anOfflineDisabledOrUnknownOfferIsNotFound() {
        Offer draft = mapper.offer(buy(FACTORY, 129900).productStatus("DRAFT"));
        Offer offline = mapper.offer(buy(FACTORY, 129900).productStatus("OFFLINE"));
        Offer skuOff = mapper.offer(buy(FACTORY, 129900).skuStatus("INACTIVE"));
        Offer disabled = mapper.offer(buy(FACTORY, 129900).offerStatus("INACTIVE"));
        // 下架比“不是购买商品”先判断：下架的租赁商品也是不存在。
        Offer offlineRental = mapper.offer(buy(FACTORY, 38000).saleMode("RENT").unit("DAY").productStatus("OFFLINE"));

        for (Offer offer : List.of(draft, offline, skuOff, disabled, offlineRental)) {
            assertRejected(() -> service.add(ACCOUNT, offer.id, 1, DONGYING), Error.NOT_FOUND);
        }
        assertRejected(() -> service.add(ACCOUNT, UUID.randomUUID(), 1, DONGYING), Error.NOT_FOUND);
        assertThat(mapper.lines).isEmpty();
    }

    @Test
    void addingNeedsAnAccountAValidCityAnOfferAndAQuantityFromOneToNinetyNine() {
        Offer robot = mapper.offer(buy(FACTORY, 129900));

        assertInvalid(() -> service.add(null, robot.id, 1, DONGYING));
        assertInvalid(() -> service.add(ACCOUNT, null, 1, DONGYING));
        assertInvalid(() -> service.add(ACCOUNT, robot.id, 0, DONGYING));
        assertInvalid(() -> service.add(ACCOUNT, robot.id, 100, DONGYING));
        assertInvalid(() -> service.add(ACCOUNT, robot.id, -1, DONGYING));
        for (String city : new String[] {null, "", "370", "dongying", "370 500", "37050012345678901"}) {
            assertInvalid(() -> service.add(ACCOUNT, robot.id, 1, city));
        }
        assertThat(service.add(ACCOUNT, robot.id, 99, DONGYING).quantity()).isEqualTo(99);
    }

    @Test
    void aFullCartRefusesANewLineButStillTakesMoreOfALineItHas() {
        List<Offer> offers = IntStream.range(0, MallCartService.MAX_LINES)
                .mapToObj(i -> mapper.offer(buy(FACTORY, 100 + i)))
                .toList();
        offers.forEach(offer -> service.add(ACCOUNT, offer.id, 1, DONGYING));
        Offer oneMore = mapper.offer(buy(FACTORY, 999));

        assertRejected(() -> service.add(ACCOUNT, oneMore.id, 1, DONGYING), Error.CART_FULL);
        assertThat(service.add(ACCOUNT, offers.get(0).id, 3, DONGYING).quantity()).isEqualTo(4);
        assertThat(mapper.lines).hasSize(50);
        // 另一个账号的购物车不受影响。
        assertThat(service.add(OTHER_ACCOUNT, oneMore.id, 1, DONGYING).cartCount()).isEqualTo(1);
    }

    @Test
    void theCartCountAfterAddingOnlyCountsLinesThatCanBeBoughtInTheCity() {
        Offer robot = mapper.offer(buy(FACTORY, 129900));
        Offer kit = mapper.offer(buy(STORE, 32900));
        service.add(ACCOUNT, robot.id, 1, DONGYING);
        robot.offerStatus = "INACTIVE";

        assertThat(service.add(ACCOUNT, kit.id, 5, DONGYING).cartCount()).isEqualTo(1);
    }

    // ---- 改数量、删除 ----

    @Test
    void setQuantityChangesOnlyTheAccountsOwnLine() {
        Offer robot = mapper.offer(buy(FACTORY, 129900));
        UUID mine = service.add(ACCOUNT, robot.id, 1, DONGYING).cartItemId();
        UUID theirs = service.add(OTHER_ACCOUNT, robot.id, 1, DONGYING).cartItemId();
        clock.advance();

        assertThat(service.setQuantity(ACCOUNT, mine, 7)).isEqualTo(new CartQuantityView(mine, 7));
        assertThat(mapper.line(mine).quantity).isEqualTo(7);
        assertThat(mapper.line(mine).updatedAt).isEqualTo(clock.instant());

        assertRejected(() -> service.setQuantity(ACCOUNT, theirs, 5), Error.NOT_FOUND);
        assertRejected(() -> service.setQuantity(ACCOUNT, UUID.randomUUID(), 5), Error.NOT_FOUND);
        assertThat(mapper.line(theirs).quantity).isEqualTo(1);
    }

    @Test
    void setQuantityNeedsALineAndAQuantityFromOneToNinetyNine() {
        UUID line = service.add(ACCOUNT, mapper.offer(buy(FACTORY, 100)).id, 1, DONGYING).cartItemId();

        assertInvalid(() -> service.setQuantity(null, line, 1));
        assertInvalid(() -> service.setQuantity(ACCOUNT, null, 1));
        assertInvalid(() -> service.setQuantity(ACCOUNT, line, 0));
        assertInvalid(() -> service.setQuantity(ACCOUNT, line, 100));
        assertThat(service.setQuantity(ACCOUNT, line, 99).quantity()).isEqualTo(99);
    }

    @Test
    void removeDeletesTheNamedLinesOfTheAccountAndLeavesOtherAccountsAlone() {
        UUID first = service.add(ACCOUNT, mapper.offer(buy(FACTORY, 100)).id, 1, DONGYING).cartItemId();
        UUID second = service.add(ACCOUNT, mapper.offer(buy(FACTORY, 200)).id, 1, DONGYING).cartItemId();
        UUID kept = service.add(ACCOUNT, mapper.offer(buy(FACTORY, 300)).id, 1, DONGYING).cartItemId();
        UUID theirs = service.add(OTHER_ACCOUNT, mapper.offer(buy(FACTORY, 400)).id, 1, DONGYING).cartItemId();

        CartRemovedView removed = service.remove(ACCOUNT, List.of(first, second, theirs, UUID.randomUUID()));

        assertThat(removed.removed()).isEqualTo(2);
        assertThat(mapper.lines).extracting(line -> line.id).containsExactlyInAnyOrder(kept, theirs);
    }

    @Test
    void removeNeedsOneToFiftyIds() {
        List<UUID> tooMany = IntStream.range(0, 51).mapToObj(i -> UUID.randomUUID()).toList();
        List<UUID> withNull = new ArrayList<>();
        withNull.add(UUID.randomUUID());
        withNull.add(null);

        assertInvalid(() -> service.remove(ACCOUNT, null));
        assertInvalid(() -> service.remove(ACCOUNT, List.of()));
        assertInvalid(() -> service.remove(ACCOUNT, tooMany));
        assertInvalid(() -> service.remove(ACCOUNT, withNull));
        assertInvalid(() -> service.remove(null, List.of(UUID.randomUUID())));
        assertThat(service.remove(ACCOUNT, tooMany.subList(0, 50)).removed()).isZero();
    }

    @Test
    void removeInvalidDeletesOnlyTheLinesThatCannotBeBoughtInTheCity() {
        Offer good = mapper.offer(buy(FACTORY, 100));
        Offer jinanOnly = mapper.offer(buy(FACTORY, 200).city(JINAN));
        Offer laterOffline = mapper.offer(buy(STORE, 300));
        service.add(ACCOUNT, good.id, 1, DONGYING);
        service.add(ACCOUNT, jinanOnly.id, 1, JINAN);
        service.add(ACCOUNT, laterOffline.id, 1, DONGYING);
        UUID theirs = service.add(OTHER_ACCOUNT, jinanOnly.id, 1, JINAN).cartItemId();
        laterOffline.productStatus = "OFFLINE";

        assertThat(service.removeInvalid(ACCOUNT, DONGYING).removed()).isEqualTo(2);
        assertThat(mapper.lines).extracting(line -> line.offerId, line -> line.accountId)
                .containsExactlyInAnyOrder(tuple(good.id, ACCOUNT), tuple(jinanOnly.id, OTHER_ACCOUNT));
        assertThat(mapper.line(theirs)).isNotNull();

        // 没有失效的行时不发删除语句。
        int removeCalls = mapper.removeCalls;
        assertThat(service.removeInvalid(ACCOUNT, DONGYING).removed()).isZero();
        assertThat(mapper.removeCalls).isEqualTo(removeCalls);
        assertInvalid(() -> service.removeInvalid(ACCOUNT, "bad"));
    }

    // ---- 读取购物车 ----

    @Test
    void theCartGroupsLinesByMerchantInTheOrderTheyWereLastTouched() {
        Offer robot = mapper.offer(buy(FACTORY, 129900).product("编程机器人", "标准版"));
        Offer kit = mapper.offer(buy(STORE, 32900).product("科创套装", "入门版").kind("LOCAL_STORE"));
        Offer parts = mapper.offer(buy(FACTORY, 5900).product("备用电池", "2000mAh"));
        UUID robotLine = service.add(ACCOUNT, robot.id, 1, DONGYING).cartItemId();
        clock.advance();
        UUID kitLine = service.add(ACCOUNT, kit.id, 2, DONGYING).cartItemId();
        clock.advance();
        UUID partsLine = service.add(ACCOUNT, parts.id, 3, DONGYING).cartItemId();
        clock.advance();
        // 改数量也算最近动过：机器人那一行排到最前，它的商家也排到最前。
        service.setQuantity(ACCOUNT, robotLine, 4);

        CartView cart = service.read(ACCOUNT, DONGYING);

        assertThat(cart.groups())
                .extracting(group -> group.merchant().organizationId())
                .containsExactly(FACTORY, STORE);
        assertThat(cart.groups().get(0).items())
                .extracting(CartItemView::cartItemId, CartItemView::quantity)
                .containsExactly(tuple(robotLine, 4), tuple(partsLine, 3));
        assertThat(cart.groups().get(1).items()).extracting(CartItemView::cartItemId).containsExactly(kitLine);
        assertThat(cart.groups().get(1).merchant())
                .isEqualTo(new MallMerchantView(STORE, "商家 " + STORE.toString().substring(34), MallMerchantKind.LOCAL_STORE));
        assertThat(cart.invalidItems()).isEmpty();
        assertThat(cart.validCount()).isEqualTo(3);
    }

    @Test
    void aValidLineShowsTheCurrentPriceAndEverythingThePageNeeds() {
        Offer robot = mapper.offer(buy(FACTORY, 129900)
                .product("编程机器人", "标准版")
                .cover("https://media.example.com/robot.jpg")
                .shipping(" 东营可买 · 包邮 "));
        UUID line = service.add(ACCOUNT, robot.id, 2, DONGYING).cartItemId();
        robot.unitAmountMinor = 119900L;

        CartItemView item = service.read(ACCOUNT, DONGYING).groups().get(0).items().get(0);

        assertThat(item.cartItemId()).isEqualTo(line);
        assertThat(item.offerId()).isEqualTo(robot.id);
        assertThat(item.productId()).isEqualTo(robot.productId);
        assertThat(item.productName()).isEqualTo("编程机器人");
        assertThat(item.skuName()).isEqualTo("标准版");
        assertThat(item.coverImage()).isEqualTo(new MallMediaView("https://media.example.com/robot.jpg", "编程机器人"));
        assertThat(item.merchant().kind()).isEqualTo(MallMerchantKind.FACTORY_DIRECT);
        // 价格不存在购物车里：读到的是现在的价格。
        assertThat(item.unitAmountMinor()).isEqualTo(119900L);
        assertThat(item.quantity()).isEqualTo(2);
        assertThat(item.shippingNote()).isEqualTo("东营可买 · 包邮");
        assertThat(item.deliveryModes()).containsExactly(DeliveryMode.EXPRESS);
        assertThat(item.invalidReason()).isNull();
    }

    @Test
    void anInsecureOrBlankCoverAndABlankShippingNoteAreLeftOut() {
        mapper.offer(buy(FACTORY, 100).cover("http://media.example.com/a.jpg").shipping("  "));
        mapper.offer(buy(FACTORY, 200).cover(" "));
        mapper.offers.values().forEach(offer -> service.add(ACCOUNT, offer.id, 1, DONGYING));

        assertThat(service.read(ACCOUNT, DONGYING).groups().get(0).items()).allSatisfy(item -> {
            assertThat(item.coverImage()).isNull();
            assertThat(item.shippingNote()).isNull();
        });
    }

    @Test
    void eachLineListsTheDeliveryModesItAllows() {
        Offer expressOnly = mapper.offer(buy(FACTORY, 100));
        Offer pickupOnly = mapper.offer(buy(FACTORY, 200).express(false).pickup("东营门店（示例路 18 号）"));
        Offer both = mapper.offer(buy(FACTORY, 300).pickup("东营自提点"));
        Offer blankPickup = mapper.offer(buy(FACTORY, 400).pickup("  "));
        Map<UUID, Offer> byLine = new HashMap<>();
        for (Offer offer : List.of(expressOnly, pickupOnly, both, blankPickup)) {
            byLine.put(service.add(ACCOUNT, offer.id, 1, DONGYING).cartItemId(), offer);
            clock.advance();
        }

        Map<UUID, List<DeliveryMode>> modes = new HashMap<>();
        service.read(ACCOUNT, DONGYING).groups().get(0).items()
                .forEach(item -> modes.put(byLine.get(item.cartItemId()).id, item.deliveryModes()));

        assertThat(modes.get(expressOnly.id)).containsExactly(DeliveryMode.EXPRESS);
        assertThat(modes.get(pickupOnly.id)).containsExactly(DeliveryMode.PICKUP);
        assertThat(modes.get(both.id)).containsExactly(DeliveryMode.EXPRESS, DeliveryMode.PICKUP);
        assertThat(modes.get(blankPickup.id)).containsExactly(DeliveryMode.EXPRESS);
    }

    @Test
    void linesThatCanNoLongerBeBoughtMoveToInvalidItemsWithTheirReasonAndNoPrice() {
        Offer good = mapper.offer(buy(FACTORY, 100));
        Offer productOff = mapper.offer(buy(FACTORY, 200));
        Offer skuOff = mapper.offer(buy(FACTORY, 300));
        Offer offerOff = mapper.offer(buy(STORE, 400));
        Offer becameRental = mapper.offer(buy(STORE, 500));
        Offer lostPrice = mapper.offer(buy(STORE, 600));
        Offer jinanOnly = mapper.offer(buy(STORE, 700).city(JINAN));
        // 同时下架又不在本城：先报下架。
        Offer offlineElsewhere = mapper.offer(buy(STORE, 800).city(JINAN));
        service.add(ACCOUNT, good.id, 1, DONGYING);
        for (Offer offer : List.of(productOff, skuOff, offerOff, becameRental, lostPrice)) {
            clock.advance();
            service.add(ACCOUNT, offer.id, 1, DONGYING);
        }
        clock.advance();
        service.add(ACCOUNT, jinanOnly.id, 1, JINAN);
        clock.advance();
        service.add(ACCOUNT, offlineElsewhere.id, 1, JINAN);
        productOff.productStatus = "OFFLINE";
        skuOff.skuStatus = "INACTIVE";
        offerOff.offerStatus = "INACTIVE";
        becameRental.saleMode = "RENT";
        becameRental.priceUnit = "DAY";
        lostPrice.unitAmountMinor = null;
        offlineElsewhere.productStatus = "OFFLINE";

        CartView cart = service.read(ACCOUNT, DONGYING);

        assertThat(cart.validCount()).isEqualTo(1);
        assertThat(cart.groups()).singleElement()
                .satisfies(group -> assertThat(group.items()).extracting(CartItemView::offerId).containsExactly(good.id));
        assertThat(cart.invalidItems())
                .extracting(CartItemView::offerId, CartItemView::invalidReason, CartItemView::unitAmountMinor)
                .containsExactly(
                        tuple(offlineElsewhere.id, CartInvalidReason.PRODUCT_OFFLINE, null),
                        tuple(jinanOnly.id, CartInvalidReason.NOT_SOLD_IN_CITY, null),
                        tuple(lostPrice.id, CartInvalidReason.NOT_PURCHASABLE, null),
                        tuple(becameRental.id, CartInvalidReason.NOT_PURCHASABLE, null),
                        tuple(offerOff.id, CartInvalidReason.OFFER_UNAVAILABLE, null),
                        tuple(skuOff.id, CartInvalidReason.PRODUCT_OFFLINE, null),
                        tuple(productOff.id, CartInvalidReason.PRODUCT_OFFLINE, null));
        // 在济南读，济南的那一行又能买了。
        assertThat(service.read(ACCOUNT, JINAN).groups().get(0).items())
                .extracting(CartItemView::offerId)
                .containsExactly(jinanOnly.id);
        // 东营那一行在济南反而不能买了。
        assertThat(service.count(ACCOUNT, JINAN).count()).isEqualTo(1);
    }

    @Test
    void theCountIsTheNumberOfLinesNotPiecesAndEachAccountSeesOnlyItsOwnCart() {
        service.add(ACCOUNT, mapper.offer(buy(FACTORY, 100)).id, 5, DONGYING);
        service.add(ACCOUNT, mapper.offer(buy(STORE, 200)).id, 7, DONGYING);

        assertThat(service.count(ACCOUNT, DONGYING)).isEqualTo(new CartCountView(2));
        assertThat(service.count(OTHER_ACCOUNT, DONGYING)).isEqualTo(new CartCountView(0));
        assertThat(service.read(OTHER_ACCOUNT, DONGYING)).isEqualTo(new CartView(List.of(), List.of(), 0));
        assertInvalid(() -> service.read(null, DONGYING));
        assertInvalid(() -> service.read(ACCOUNT, null));
        assertInvalid(() -> service.count(ACCOUNT, "37-0500"));
    }

    // ---- 结算预览 ----

    @Test
    void aPreviewFromTheCartAddsUpTheLinesAndChargesTheHighestShippingFeeForExpress() {
        Offer robot = mapper.offer(buy(FACTORY, 129900).product("编程机器人", "标准版")
                .shipping("东营可买 · 包邮").fulfilledFrom("示例机器人商家 东营仓").fee(0));
        Offer battery = mapper.offer(buy(FACTORY, 5900).product("备用电池", "2000mAh")
                .shipping("48 小时内发货").fulfilledFrom("示例机器人商家 东营仓").fee(1200));
        Offer cable = mapper.offer(buy(FACTORY, 1900).product("数据线", "1 米")
                .shipping("东营可买 · 包邮").fulfilledFrom("示例机器人商家 济南仓").fee(800));
        UUID robotLine = service.add(ACCOUNT, robot.id, 1, DONGYING).cartItemId();
        UUID batteryLine = service.add(ACCOUNT, battery.id, 3, DONGYING).cartItemId();
        UUID cableLine = service.add(ACCOUNT, cable.id, 2, DONGYING).cartItemId();

        CheckoutPreview preview = service.preview(
                ACCOUNT, DONGYING, List.of(batteryLine, robotLine, cableLine, robotLine), null, null, null);

        assertThat(preview.merchant()).isEqualTo(robotMerchant());
        // 按请求里的顺序，重复的行只算一次。
        assertThat(preview.lines())
                .extracting(
                        CheckoutLineView::cartItemId,
                        CheckoutLineView::offerId,
                        CheckoutLineView::productName,
                        CheckoutLineView::unitAmountMinor,
                        CheckoutLineView::quantity,
                        CheckoutLineView::subtotalMinor)
                .containsExactly(
                        tuple(batteryLine, battery.id, "备用电池", 5900L, 3, 17700L),
                        tuple(robotLine, robot.id, "编程机器人", 129900L, 1, 129900L),
                        tuple(cableLine, cable.id, "数据线", 1900L, 2, 3800L));
        assertThat(preview.pieceCount()).isEqualTo(6);
        assertThat(preview.fulfilledFrom()).isEqualTo("示例机器人商家 东营仓；示例机器人商家 济南仓");
        assertThat(preview.deliveryOptions())
                .containsExactly(new DeliveryOptionView(DeliveryMode.EXPRESS, "48 小时内发货；东营可买 · 包邮"));
        assertThat(preview.deliveryMode()).isEqualTo(DeliveryMode.EXPRESS);
        assertThat(preview.amounts()).isEqualTo(new CheckoutAmountsView(151400L, 1200L, 152600L, "CNY"));
    }

    @Test
    void aDirectPurchaseIsOneLineWithoutACartLine() {
        Offer robot = mapper.offer(buy(FACTORY, 129900).product("编程机器人", "标准版")
                .cover("https://media.example.com/robot.jpg").fee(1500).fulfilledFrom(" "));

        CheckoutPreview preview = service.preview(ACCOUNT, DONGYING, null, robot.id, 2, "EXPRESS");

        assertThat(preview.lines()).singleElement().satisfies(line -> {
            assertThat(line.cartItemId()).isNull();
            assertThat(line.offerId()).isEqualTo(robot.id);
            assertThat(line.productId()).isEqualTo(robot.productId);
            assertThat(line.skuName()).isEqualTo("标准版");
            assertThat(line.coverImage()).isEqualTo(new MallMediaView("https://media.example.com/robot.jpg", "编程机器人"));
            assertThat(line.quantity()).isEqualTo(2);
            assertThat(line.subtotalMinor()).isEqualTo(259800L);
        });
        assertThat(preview.pieceCount()).isEqualTo(2);
        assertThat(preview.fulfilledFrom()).isNull();
        // 没有发货说明时快递选项的说明为空。
        assertThat(preview.deliveryOptions()).containsExactly(new DeliveryOptionView(DeliveryMode.EXPRESS, null));
        assertThat(preview.amounts()).isEqualTo(new CheckoutAmountsView(259800L, 1500L, 261300L, "CNY"));
        // 立即购买不碰购物车。
        assertThat(mapper.lines).isEmpty();
    }

    @Test
    void aDirectPurchaseIsRefusedForRentalsUnknownOffersAndOffersThatCannotBeBoughtHere() {
        Offer rental = mapper.offer(buy(FACTORY, 38000).saleMode("RENT").unit("DAY"));
        Offer inquiry = mapper.offer(buy(FACTORY, null).saleMode("INQUIRY"));
        Offer offline = mapper.offer(buy(FACTORY, 100).productStatus("OFFLINE"));
        Offer disabled = mapper.offer(buy(FACTORY, 100).offerStatus("INACTIVE"));
        Offer jinanOnly = mapper.offer(buy(FACTORY, 100).city(JINAN));
        Offer robot = mapper.offer(buy(FACTORY, 100));

        assertRejected(() -> service.preview(ACCOUNT, DONGYING, null, rental.id, 1, null), Error.NOT_PURCHASABLE);
        assertRejected(() -> service.preview(ACCOUNT, DONGYING, null, inquiry.id, 1, null), Error.NOT_PURCHASABLE);
        assertRejected(() -> service.preview(ACCOUNT, DONGYING, null, offline.id, 1, null), Error.ITEM_UNAVAILABLE);
        assertRejected(() -> service.preview(ACCOUNT, DONGYING, null, disabled.id, 1, null), Error.ITEM_UNAVAILABLE);
        assertRejected(() -> service.preview(ACCOUNT, DONGYING, null, jinanOnly.id, 1, null), Error.ITEM_UNAVAILABLE);
        assertRejected(() -> service.preview(ACCOUNT, DONGYING, null, UUID.randomUUID(), 1, null), Error.NOT_FOUND);
        assertInvalid(() -> service.preview(ACCOUNT, DONGYING, null, robot.id, null, null));
        assertInvalid(() -> service.preview(ACCOUNT, DONGYING, null, robot.id, 0, null));
        assertInvalid(() -> service.preview(ACCOUNT, DONGYING, null, robot.id, 100, null));
        assertThat(service.preview(ACCOUNT, DONGYING, null, robot.id, 99, null).pieceCount()).isEqualTo(99);
    }

    @Test
    void thePreviewTakesEitherCartLinesOrOneOfferAndNeverBoth() {
        Offer robot = mapper.offer(buy(FACTORY, 100));
        UUID line = service.add(ACCOUNT, robot.id, 1, DONGYING).cartItemId();

        assertInvalid(() -> service.preview(ACCOUNT, DONGYING, List.of(line), robot.id, 1, null));
        assertInvalid(() -> service.preview(ACCOUNT, DONGYING, null, null, null, null));
        assertInvalid(() -> service.preview(ACCOUNT, DONGYING, List.of(), null, 1, null));
        // 空列表等于没给，配上 offerId 就是立即购买。
        assertThat(service.preview(ACCOUNT, DONGYING, List.of(), robot.id, 1, null).lines().get(0).cartItemId())
                .isNull();
        assertInvalid(() -> service.preview(null, DONGYING, List.of(line), null, null, null));
        assertInvalid(() -> service.preview(ACCOUNT, "x", List.of(line), null, null, null));
        assertInvalid(() -> service.preview(ACCOUNT, DONGYING, List.of(line), null, null, "SHIP"));
    }

    @Test
    void aPreviewFromTheCartTakesAtMostTwentyLinesAndNoMissingId() {
        List<UUID> lines = IntStream.range(0, 21)
                .mapToObj(i -> service.add(ACCOUNT, mapper.offer(buy(FACTORY, 100 + i)).id, 1, DONGYING).cartItemId())
                .toList();
        List<UUID> withNull = new ArrayList<>(lines.subList(0, 2));
        withNull.add(null);

        assertInvalid(() -> service.preview(ACCOUNT, DONGYING, lines, null, null, null));
        assertInvalid(() -> service.preview(ACCOUNT, DONGYING, withNull, null, null, null));
        assertThat(service.preview(ACCOUNT, DONGYING, lines.subList(0, 20), null, null, null).lines()).hasSize(20);
    }

    @Test
    void aLineThatIsNotInTheAccountsCartIsNotFound() {
        Offer robot = mapper.offer(buy(FACTORY, 100));
        UUID mine = service.add(ACCOUNT, robot.id, 1, DONGYING).cartItemId();
        UUID theirs = service.add(OTHER_ACCOUNT, robot.id, 1, DONGYING).cartItemId();

        assertRejected(() -> service.preview(ACCOUNT, DONGYING, List.of(mine, theirs), null, null, null),
                Error.NOT_FOUND);
        assertRejected(() -> service.preview(ACCOUNT, DONGYING, List.of(UUID.randomUUID()), null, null, null),
                Error.NOT_FOUND);
    }

    @Test
    void aLineThatCannotBeBoughtAnyMoreStopsThePreview() {
        Offer robot = mapper.offer(buy(FACTORY, 100));
        Offer battery = mapper.offer(buy(FACTORY, 200));
        UUID robotLine = service.add(ACCOUNT, robot.id, 1, DONGYING).cartItemId();
        UUID batteryLine = service.add(ACCOUNT, battery.id, 1, DONGYING).cartItemId();

        battery.saleMode = "INQUIRY";
        assertRejected(() -> service.preview(ACCOUNT, DONGYING, List.of(robotLine, batteryLine), null, null, null),
                Error.ITEM_UNAVAILABLE);
        battery.saleMode = "BUY";
        battery.city = JINAN;
        assertRejected(() -> service.preview(ACCOUNT, DONGYING, List.of(robotLine, batteryLine), null, null, null),
                Error.ITEM_UNAVAILABLE);
        battery.city = DONGYING;
        assertThat(service.preview(ACCOUNT, DONGYING, List.of(robotLine, batteryLine), null, null, null).lines())
                .hasSize(2);
    }

    @Test
    void linesOfTwoMerchantsCannotBeCheckedOutTogether() {
        UUID robotLine = service.add(ACCOUNT, mapper.offer(buy(FACTORY, 100)).id, 1, DONGYING).cartItemId();
        UUID kitLine = service.add(ACCOUNT, mapper.offer(buy(STORE, 200)).id, 1, DONGYING).cartItemId();

        assertRejected(() -> service.preview(ACCOUNT, DONGYING, List.of(robotLine, kitLine), null, null, null),
                Error.MULTIPLE_MERCHANTS);
        assertThat(service.preview(ACCOUNT, DONGYING, List.of(kitLine), null, null, null).merchant().organizationId())
                .isEqualTo(STORE);
    }

    @Test
    void pickupIsOfferedWhenEveryLineSharesOnePickupPointAndCostsNoShipping() {
        String point = "东营低空与机器人体验馆 自提点（示例路 66 号）";
        UUID robotLine = service.add(ACCOUNT,
                mapper.offer(buy(FACTORY, 129900).pickup(point).fee(1200)).id, 1, DONGYING).cartItemId();
        UUID batteryLine = service.add(ACCOUNT,
                mapper.offer(buy(FACTORY, 5900).pickup(point).fee(800).shipping("包邮")).id, 2, DONGYING).cartItemId();
        List<UUID> lines = List.of(robotLine, batteryLine);

        CheckoutPreview express = service.preview(ACCOUNT, DONGYING, lines, null, null, null);
        CheckoutPreview pickup = service.preview(ACCOUNT, DONGYING, lines, null, null, " PICKUP ");

        assertThat(express.deliveryOptions()).containsExactly(
                new DeliveryOptionView(DeliveryMode.EXPRESS, "包邮"),
                new DeliveryOptionView(DeliveryMode.PICKUP, point));
        assertThat(express.deliveryMode()).isEqualTo(DeliveryMode.EXPRESS);
        assertThat(express.amounts()).isEqualTo(new CheckoutAmountsView(141700L, 1200L, 142900L, "CNY"));
        assertThat(pickup.deliveryOptions()).isEqualTo(express.deliveryOptions());
        assertThat(pickup.deliveryMode()).isEqualTo(DeliveryMode.PICKUP);
        assertThat(pickup.amounts()).isEqualTo(new CheckoutAmountsView(141700L, 0L, 141700L, "CNY"));
    }

    @Test
    void aWantedModeThatTheOrderDoesNotOfferFallsBackToTheFirstOption() {
        String point = "示例科创器材店 东营门店（示例路 18 号）";
        Offer storeOnly = mapper.offer(buy(STORE, 32900).express(false).pickup(point).fee(1000));
        Offer expressOnly = mapper.offer(buy(FACTORY, 5900).fee(600));

        CheckoutPreview pickupInstead = service.preview(ACCOUNT, DONGYING, null, storeOnly.id, 1, "EXPRESS");
        CheckoutPreview expressInstead = service.preview(ACCOUNT, DONGYING, null, expressOnly.id, 1, "PICKUP");

        assertThat(pickupInstead.deliveryOptions()).containsExactly(new DeliveryOptionView(DeliveryMode.PICKUP, point));
        assertThat(pickupInstead.deliveryMode()).isEqualTo(DeliveryMode.PICKUP);
        assertThat(pickupInstead.amounts().shippingMinor()).isZero();
        assertThat(expressInstead.deliveryOptions()).containsExactly(new DeliveryOptionView(DeliveryMode.EXPRESS, null));
        assertThat(expressInstead.deliveryMode()).isEqualTo(DeliveryMode.EXPRESS);
        assertThat(expressInstead.amounts().shippingMinor()).isEqualTo(600L);
    }

    @Test
    void linesWithoutACommonDeliveryModeHaveNoOptionAndNoShipping() {
        // 一行只能自提，另一行的自提点不同：既不能都走快递，也不能在同一处自提。
        UUID storeLine = service.add(ACCOUNT,
                mapper.offer(buy(STORE, 32900).express(false).pickup("东营门店")).id, 1, DONGYING).cartItemId();
        UUID otherPointLine = service.add(ACCOUNT,
                mapper.offer(buy(STORE, 1900).pickup("济南门店").fee(800)).id, 1, DONGYING).cartItemId();
        // 一行只能自提，另一行没有自提点。
        UUID noPickupLine = service.add(ACCOUNT,
                mapper.offer(buy(STORE, 900).fee(500)).id, 1, DONGYING).cartItemId();

        for (List<UUID> lines : List.of(List.of(storeLine, otherPointLine), List.of(storeLine, noPickupLine))) {
            CheckoutPreview preview = service.preview(ACCOUNT, DONGYING, lines, null, null, "PICKUP");

            assertThat(preview.deliveryOptions()).isEmpty();
            assertThat(preview.deliveryMode()).isNull();
            assertThat(preview.amounts().shippingMinor()).isZero();
            assertThat(preview.amounts().payableMinor()).isEqualTo(preview.amounts().goodsMinor());
        }
        // 两行都能走快递、自提点不同：只有快递。
        CheckoutPreview expressOnly = service.preview(
                ACCOUNT, DONGYING, List.of(otherPointLine, noPickupLine), null, null, "PICKUP");
        assertThat(expressOnly.deliveryOptions()).extracting(DeliveryOptionView::mode)
                .containsExactly(DeliveryMode.EXPRESS);
        assertThat(expressOnly.deliveryMode()).isEqualTo(DeliveryMode.EXPRESS);
        assertThat(expressOnly.amounts().shippingMinor()).isEqualTo(800L);
    }

    // ---- 按询价报价结算（F4） ----

    @Test
    void aChosenInquiryQuoteIsOneLineAtTheQuotedPriceAndTheInquiryQuantity() {
        Offer drone = mapper.offer(buy(FACTORY, null).saleMode("INQUIRY").product("行业测绘无人机", "按配置报价")
                .fee(1500).pickup("东营门店"));
        UUID quoteId = UUID.randomUUID();
        List<UUID> askedBy = new ArrayList<>();
        MallCartService quoted = new MallCartService(mapper, clock, (accountId, id) -> {
            askedBy.add(accountId);
            return new MallInquiryOrderQuote(id, drone.id, 2, 2880000, "测绘版 · RTK", null);
        });

        // 数量取询价单的，传进来的数量不用。
        CheckoutPreview preview = quoted.preview(ACCOUNT, DONGYING, null, null, 5, "EXPRESS", quoteId);

        assertThat(askedBy).containsExactly(ACCOUNT);
        assertThat(preview.lines()).singleElement().satisfies(line -> {
            assertThat(line.cartItemId()).isNull();
            assertThat(line.offerId()).isEqualTo(drone.id);
            assertThat(line.skuName()).isEqualTo("测绘版 · RTK");
            assertThat(line.unitAmountMinor()).isEqualTo(2880000L);
            assertThat(line.quantity()).isEqualTo(2);
            assertThat(line.subtotalMinor()).isEqualTo(5760000L);
        });
        // 配送方式和运费按这家供给。
        assertThat(preview.deliveryOptions()).extracting(DeliveryOptionView::mode)
                .containsExactly(DeliveryMode.EXPRESS, DeliveryMode.PICKUP);
        assertThat(preview.amounts()).isEqualTo(new CheckoutAmountsView(5760000L, 1500L, 5761500L, "CNY"));
        assertThat(quoted.preview(ACCOUNT, DONGYING, null, null, null, "PICKUP", quoteId).amounts().payableMinor())
                .isEqualTo(5760000L);
        assertThat(mapper.lines).isEmpty();
    }

    @Test
    void anInquiryQuoteIsRefusedWhenItsOfferIsNoLongerSoldHereOrTheQuoteCannotBeOrdered() {
        Offer drone = mapper.offer(buy(FACTORY, null).saleMode("INQUIRY"));
        Map<UUID, UUID> offerOfQuote = new HashMap<>();
        MallCartService quoted = new MallCartService(mapper, clock, (accountId, id) -> {
            if (!offerOfQuote.containsKey(id)) {
                throw new MallInquiryException(MallInquiryException.Error.QUOTE_NOT_CHOSEN);
            }
            return new MallInquiryOrderQuote(id, offerOfQuote.get(id), 1, 100, "型号", null);
        });
        UUID quoteId = UUID.randomUUID();
        offerOfQuote.put(quoteId, drone.id);
        UUID gone = UUID.randomUUID();
        offerOfQuote.put(gone, UUID.randomUUID());

        assertThat(quoted.preview(ACCOUNT, DONGYING, null, null, null, null, quoteId).lines()).hasSize(1);
        assertThatThrownBy(() -> quoted.preview(ACCOUNT, DONGYING, null, null, null, null, UUID.randomUUID()))
                .isInstanceOfSatisfying(MallInquiryException.class, exception ->
                        assertThat(exception.error()).isEqualTo(MallInquiryException.Error.QUOTE_NOT_CHOSEN));
        assertRejected(() -> quoted.preview(ACCOUNT, JINAN, null, null, null, null, quoteId), Error.ITEM_UNAVAILABLE);
        assertRejected(() -> quoted.preview(ACCOUNT, DONGYING, null, null, null, null, gone), Error.ITEM_UNAVAILABLE);
        drone.offerStatus = "INACTIVE";
        assertRejected(() -> quoted.preview(ACCOUNT, DONGYING, null, null, null, null, quoteId), Error.ITEM_UNAVAILABLE);
        drone.offerStatus = "ACTIVE";
        drone.productStatus = "OFFLINE";
        assertRejected(() -> quoted.preview(ACCOUNT, DONGYING, null, null, null, null, quoteId), Error.ITEM_UNAVAILABLE);
        // 三种来源只能给一种。
        assertInvalid(() -> quoted.preview(ACCOUNT, DONGYING, null, drone.id, 1, null, quoteId));
        assertInvalid(() -> quoted.preview(ACCOUNT, DONGYING, List.of(UUID.randomUUID()), null, null, null, quoteId));
        // 没接询价模块的服务（旧构造）不认识报价。
        assertThatThrownBy(() -> service.preview(ACCOUNT, DONGYING, null, null, null, null, quoteId))
                .isInstanceOfSatisfying(MallInquiryException.class, exception ->
                        assertThat(exception.error()).isEqualTo(MallInquiryException.Error.NOT_FOUND));
    }

    // ---- 辅助 ----

    private static MallMerchantView robotMerchant() {
        return new MallMerchantView(FACTORY, "商家 " + FACTORY.toString().substring(34), MallMerchantKind.FACTORY_DIRECT);
    }

    private static void assertRejected(ThrowingCallable call, Error error) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(MallCartException.class, exception ->
                        assertThat(exception.error()).isEqualTo(error));
    }

    private static void assertInvalid(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(IllegalArgumentException.class);
    }

    /** 东营在售、按件标价、可走快递的“购买”商品。 */
    private static Offer buy(UUID merchantId, Integer amountMinor) {
        Offer offer = new Offer();
        offer.merchantId = merchantId;
        offer.unitAmountMinor = amountMinor == null ? null : amountMinor.longValue();
        return offer;
    }

    /** 一个供给和它的规格、商品、商家的当前状态；测试里直接改字段来模拟下架、改价、改城市。 */
    private static final class Offer {
        UUID id = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID merchantId;
        String merchantKind = "FACTORY_DIRECT";
        String offerStatus = "ACTIVE";
        String skuStatus = "ACTIVE";
        String productStatus = "PUBLISHED";
        String saleMode = "BUY";
        String priceUnit = "ITEM";
        String city = DONGYING;
        Long unitAmountMinor;
        String productName = "示例商品";
        String skuName = "标准版";
        String coverUrl;
        String shippingNote;
        String fulfilledFrom;
        boolean expressAvailable = true;
        String pickupPoint;
        long shippingFeeMinor;
        Integer stockQuantity;

        Offer stock(Integer value) {
            stockQuantity = value;
            return this;
        }

        Offer saleMode(String value) {
            saleMode = value;
            return this;
        }

        Offer unit(String value) {
            priceUnit = value;
            return this;
        }

        Offer city(String value) {
            city = value;
            return this;
        }

        Offer productStatus(String value) {
            productStatus = value;
            return this;
        }

        Offer skuStatus(String value) {
            skuStatus = value;
            return this;
        }

        Offer offerStatus(String value) {
            offerStatus = value;
            return this;
        }

        Offer kind(String value) {
            merchantKind = value;
            return this;
        }

        Offer product(String name, String sku) {
            productName = name;
            skuName = sku;
            return this;
        }

        Offer cover(String value) {
            coverUrl = value;
            return this;
        }

        Offer shipping(String value) {
            shippingNote = value;
            return this;
        }

        Offer fulfilledFrom(String value) {
            fulfilledFrom = value;
            return this;
        }

        Offer express(boolean value) {
            expressAvailable = value;
            return this;
        }

        Offer pickup(String value) {
            pickupPoint = value;
            return this;
        }

        Offer fee(long value) {
            shippingFeeMinor = value;
            return this;
        }

        OfferRow toRow() {
            return new OfferRow(
                    id, offerStatus, city, unitAmountMinor, "CNY", priceUnit, shippingNote, fulfilledFrom,
                    expressAvailable, pickupPoint, shippingFeeMinor, merchantKind, merchantId,
                    "商家 " + merchantId.toString().substring(34), skuId, skuName, skuStatus,
                    productId, productName, coverUrl, saleMode, productStatus, stockQuantity);
        }
    }

    private static final class Line {
        UUID id;
        UUID accountId;
        UUID offerId;
        int quantity;
        int version;
        Instant updatedAt;
    }

    /** 按 MallCartMapper 的 SQL 写的内存实现：账号条件、ON CONFLICT 合并数量到 99、按 updated_at DESC, id ASC 排序。 */
    private static final class InMemoryMapper implements MallCartMapper {
        final List<String> locks = new java.util.ArrayList<>();

        final Map<UUID, Offer> offers = new java.util.LinkedHashMap<>();
        final List<Line> lines = new ArrayList<>();
        int removeCalls;

        Offer offer(Offer offer) {
            offers.put(offer.id, offer);
            return offer;
        }

        Line line(UUID id) {
            return lines.stream().filter(line -> line.id.equals(id)).findFirst().orElse(null);
        }

        @Override
        public OfferRow findOffer(UUID offerId) {
            Offer offer = offers.get(offerId);
            return offer == null ? null : offer.toRow();
        }

        @Override
        public List<CartRow> findCart(UUID accountId) {
            return lines.stream()
                    .filter(line -> line.accountId.equals(accountId))
                    .sorted(Comparator.comparing((Line line) -> line.updatedAt).reversed()
                            .thenComparing(line -> line.id))
                    .map(line -> {
                        OfferRow o = offers.get(line.offerId).toRow();
                        return new CartRow(
                                line.id, line.quantity, line.updatedAt,
                                o.offerId(), o.offerStatus(), o.offerCityCode(), o.unitAmountMinor(), o.currency(),
                                o.priceUnit(), o.shippingNote(), o.fulfilledFrom(), o.expressAvailable(),
                                o.pickupPoint(), o.shippingFeeMinor(), o.merchantKind(), o.merchantId(),
                                o.merchantName(), o.skuId(), o.skuName(), o.skuStatus(), o.productId(),
                                o.productName(), o.coverUrl(), o.saleMode(), o.productStatus(), o.stockQuantity());
                    })
                    .toList();
        }

        @Override
        public Integer lockOwner(String lockName, UUID accountId) {
            locks.add(lockName + accountId);
            return 1;
        }

        @Override
        public int countLines(UUID accountId) {
            return (int) lines.stream().filter(line -> line.accountId.equals(accountId)).count();
        }

        @Override
        public AddedRow add(UUID accountId, UUID offerId, int quantity, Instant at) {
            checkQuantity(quantity);
            Line existing = lines.stream()
                    .filter(line -> line.accountId.equals(accountId) && line.offerId.equals(offerId))
                    .findFirst()
                    .orElse(null);
            if (existing != null) {
                existing.quantity = Math.min(99, existing.quantity + quantity);
                existing.version++;
                existing.updatedAt = at;
                return new AddedRow(existing.id, existing.quantity);
            }
            Line line = new Line();
            line.id = UUID.randomUUID();
            line.accountId = accountId;
            line.offerId = offerId;
            line.quantity = quantity;
            line.updatedAt = at;
            lines.add(line);
            return new AddedRow(line.id, line.quantity);
        }

        @Override
        public int countOffer(UUID accountId, UUID offerId) {
            return (int) lines.stream()
                    .filter(line -> line.accountId.equals(accountId) && line.offerId.equals(offerId))
                    .count();
        }

        @Override
        public int setQuantity(UUID accountId, UUID cartItemId, int quantity, Instant at) {
            checkQuantity(quantity);
            int changed = 0;
            for (Line line : lines) {
                if (line.id.equals(cartItemId) && line.accountId.equals(accountId)) {
                    line.quantity = quantity;
                    line.version++;
                    line.updatedAt = at;
                    changed++;
                }
            }
            return changed;
        }

        @Override
        public int remove(UUID accountId, List<UUID> cartItemIds) {
            removeCalls++;
            int before = lines.size();
            lines.removeIf(line -> line.accountId.equals(accountId) && cartItemIds.contains(line.id));
            return before - lines.size();
        }

        /** mall_cart_item 的 CHECK (quantity BETWEEN 1 AND 99)。 */
        private static void checkQuantity(int quantity) {
            if (quantity < 1 || quantity > 99) {
                throw new IllegalStateException("quantity check violated");
            }
        }
    }

    private static final class MovableClock extends Clock {

        private Instant now = NOW;

        void advance() {
            now = now.plus(Duration.ofMinutes(1));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
