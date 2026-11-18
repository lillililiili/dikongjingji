package com.lowaltitude.inspection.account.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import com.lowaltitude.inspection.account.application.RegionDirectory.Region;
import com.lowaltitude.inspection.account.infrastructure.persistence.AccountAddressMapper;
import com.lowaltitude.inspection.account.infrastructure.persistence.AccountAddressMapper.AddressRow;
import org.springframework.transaction.annotation.Transactional;

/**
 * 收货地址簿（契约 contracts/openapi-account.yaml）。只有地址的主人能读写；
 * 地址簿里只要有地址就有且只有一条默认：第一条自动成为默认，删掉默认后最早的一条接上。
 */
public class AccountAddressService {

    static final int MAX_ADDRESSES = 20;
    private static final String LOCK_NAME = "account-address:";
    static final int MAX_NAME_LENGTH = 20;
    static final int MAX_DETAIL_LENGTH = 80;
    private static final Pattern PHONE = Pattern.compile("^1[3-9][0-9]{9}$");

    private final AccountAddressMapper mapper;
    private final RegionDirectory regions;
    private final Clock clock;

    AccountAddressService(AccountAddressMapper mapper, RegionDirectory regions, Clock clock) {
        this.mapper = mapper;
        this.regions = regions;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AddressListView list(UUID ownerId) {
        requireOwner(ownerId);
        return new AddressListView(mapper.findAll(ownerId).stream().map(AccountAddressService::toView).toList());
    }

    /** 指定的那条；没指定时取默认地址；都没有时为空。确认订单用。 */
    @Transactional(readOnly = true)
    public Optional<AddressView> findForUse(UUID ownerId, UUID addressId) {
        requireOwner(ownerId);
        if (addressId != null) {
            AddressRow row = mapper.findOne(ownerId, addressId);
            if (row == null) {
                throw new AddressNotFoundException();
            }
            return Optional.of(toView(row));
        }
        return Optional.ofNullable(mapper.findDefault(ownerId)).map(AccountAddressService::toView);
    }

    @Transactional
    public AddressView create(UUID ownerId, AddressForm form) {
        requireOwner(ownerId);
        mapper.lockOwner(LOCK_NAME, ownerId);
        int existing = mapper.count(ownerId);
        if (existing >= MAX_ADDRESSES) {
            throw new AddressLimitException();
        }
        // 第一条地址总是默认。
        AddressValues values = validate(form, existing == 0 || Boolean.TRUE.equals(form.isDefault()));
        Instant now = clock.instant();
        if (values.isDefault()) {
            mapper.clearDefault(ownerId, now);
        }
        UUID id = mapper.insert(
                ownerId,
                values.recipientName(),
                values.phone(),
                values.provinceCode(),
                values.provinceName(),
                values.cityCode(),
                values.cityName(),
                values.detail(),
                values.isDefault(),
                now);
        return toView(mapper.findOne(ownerId, id));
    }

    @Transactional
    public AddressView update(UUID ownerId, UUID addressId, AddressForm form) {
        requireOwner(ownerId);
        mapper.lockOwner(LOCK_NAME, ownerId);
        AddressRow current = requireAddress(ownerId, addressId);
        // 默认地址不能直接取消默认，只能把别的地址设为默认。
        AddressValues values = validate(form, current.isDefault() || Boolean.TRUE.equals(form.isDefault()));
        Instant now = clock.instant();
        if (values.isDefault() && !current.isDefault()) {
            mapper.clearDefault(ownerId, now);
        }
        mapper.update(
                ownerId,
                addressId,
                values.recipientName(),
                values.phone(),
                values.provinceCode(),
                values.provinceName(),
                values.cityCode(),
                values.cityName(),
                values.detail(),
                values.isDefault(),
                now);
        return toView(mapper.findOne(ownerId, addressId));
    }

    @Transactional
    public void delete(UUID ownerId, UUID addressId) {
        requireOwner(ownerId);
        mapper.lockOwner(LOCK_NAME, ownerId);
        requireAddress(ownerId, addressId);
        Instant now = clock.instant();
        mapper.delete(ownerId, addressId, now);
        mapper.promoteOldestToDefault(ownerId, now);
    }

    private AddressValues validate(AddressForm form, boolean isDefault) {
        if (form == null) {
            throw new IllegalArgumentException("address is required");
        }
        String name = text(form.recipientName(), MAX_NAME_LENGTH, "recipient name");
        String phone = form.phone() == null ? "" : form.phone().trim();
        if (!PHONE.matcher(phone).matches()) {
            throw new IllegalArgumentException("phone is invalid");
        }
        String detail = text(form.detail(), MAX_DETAIL_LENGTH, "detail");
        String cityCode = form.cityCode() == null ? "" : form.cityCode().trim();
        Region region = regions.findCity(cityCode)
                .orElseThrow(() -> new IllegalArgumentException("city is unknown"));
        return new AddressValues(
                name,
                phone,
                region.provinceCode(),
                region.provinceName(),
                region.cityCode(),
                region.cityName(),
                detail,
                isDefault);
    }

    private AddressRow requireAddress(UUID ownerId, UUID addressId) {
        if (addressId == null) {
            throw new IllegalArgumentException("address id is required");
        }
        AddressRow row = mapper.findOne(ownerId, addressId);
        if (row == null) {
            throw new AddressNotFoundException();
        }
        return row;
    }

    private static String text(String value, int maxLength, String field) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty() || text.length() > maxLength || text.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return text;
    }

    private static void requireOwner(UUID ownerId) {
        if (ownerId == null) {
            throw new IllegalArgumentException("owner is required");
        }
    }

    private static AddressView toView(AddressRow row) {
        return new AddressView(
                row.id(),
                row.recipientName(),
                row.phone(),
                new AddressRegionView(row.provinceCode(), row.provinceName(), row.cityCode(), row.cityName()),
                row.detail(),
                row.isDefault());
    }
}

/** 校验并补全省份后要写进表的值。 */
record AddressValues(
        String recipientName,
        String phone,
        String provinceCode,
        String provinceName,
        String cityCode,
        String cityName,
        String detail,
        boolean isDefault
) {
}
