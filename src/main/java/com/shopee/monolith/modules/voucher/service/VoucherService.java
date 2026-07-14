package com.shopee.monolith.modules.voucher.service;

import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.modules.voucher.dto.request.CreateVoucherRequest;
import com.shopee.monolith.modules.voucher.dto.request.UpdateVoucherRequest;
import com.shopee.monolith.modules.voucher.dto.response.ValidateVoucherResponse;
import com.shopee.monolith.modules.voucher.dto.response.VoucherResponse;

import java.math.BigDecimal;
import java.util.UUID;

public interface VoucherService {

    VoucherResponse createVoucher(CreateVoucherRequest request);

    VoucherResponse updateVoucher(UUID id, UpdateVoucherRequest request);

    void activateVoucher(UUID id);

    void deactivateVoucher(UUID id);

    void deleteVoucher(UUID id);

    VoucherResponse getVoucher(UUID id);

    PagedResponse<VoucherResponse> listVouchers(int page, int size);

    ValidateVoucherResponse validateVoucher(String code, BigDecimal orderSubtotal);

    /**
     * Locks the voucher row, re-validates against the final order subtotal, records a RESERVED
     * usage for this checkout session, and increments the voucher's usage count. Called inside
     * the checkout transaction — never call outside of one.
     */
    BigDecimal reserveVoucher(String code, UUID checkoutSessionId, UUID buyerId, BigDecimal orderSubtotal);

    /**
     * Transitions a RESERVED usage to CONFIRMED on payment success. No-op if no reservation exists
     * for this checkout session (i.e. no voucher was applied).
     */
    void confirmVoucherUsage(UUID checkoutSessionId);

    /**
     * Transitions a RESERVED usage to RELEASED and decrements the voucher's usage count, on
     * checkout cancel/expiry/payment-failure. No-op if no reservation exists or it was already settled.
     */
    void releaseVoucherUsage(UUID checkoutSessionId);
}
