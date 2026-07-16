package com.shopee.monolith.modules.voucher.service;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.modules.voucher.dto.request.CreateVoucherRequest;
import com.shopee.monolith.modules.voucher.dto.request.UpdateVoucherRequest;
import com.shopee.monolith.modules.voucher.dto.response.ValidateVoucherResponse;
import com.shopee.monolith.modules.voucher.dto.response.VoucherResponse;
import com.shopee.monolith.modules.voucher.entity.Voucher;
import com.shopee.monolith.modules.voucher.entity.VoucherUsage;
import com.shopee.monolith.modules.voucher.mapper.VoucherMapper;
import com.shopee.monolith.modules.voucher.model.VoucherStatus;
import com.shopee.monolith.modules.voucher.model.VoucherUsageStatus;
import com.shopee.monolith.modules.voucher.repository.VoucherRepository;
import com.shopee.monolith.modules.voucher.repository.VoucherUsageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VoucherServiceImpl implements VoucherService {

    private final VoucherRepository voucherRepository;
    private final VoucherUsageRepository voucherUsageRepository;
    private final VoucherMapper voucherMapper;
    private final Clock clock;

    @Override
    @Transactional
    public VoucherResponse createVoucher(CreateVoucherRequest request) {
        if (!request.expiresAt().isAfter(request.startsAt())) {
            throw new AppException(ErrorCode.VOUCHER_INVALID_DATE_RANGE);
        }

        String normalizedCode = normalizeCode(request.code());

        Voucher voucher = Voucher.builder()
                .code(normalizedCode)
                .discountType(request.discountType())
                .discountValue(request.discountValue())
                .maxDiscountAmount(request.maxDiscountAmount())
                .minOrderAmount(request.minOrderAmount())
                .usageLimit(request.usageLimit())
                .startsAt(request.startsAt())
                .expiresAt(request.expiresAt())
                .build();

        try {
            Voucher saved = voucherRepository.saveAndFlush(voucher);
            return voucherMapper.toResponse(saved);
        } catch (DataIntegrityViolationException e) {
            throw new AppException(ErrorCode.VOUCHER_CODE_ALREADY_EXISTS);
        }
    }

    @Override
    @Transactional
    public VoucherResponse updateVoucher(UUID id, UpdateVoucherRequest request) {
        if (!request.expiresAt().isAfter(request.startsAt())) {
            throw new AppException(ErrorCode.VOUCHER_INVALID_DATE_RANGE);
        }

        Voucher voucher = voucherRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.VOUCHER_NOT_FOUND));

        voucher.update(
                request.discountType(),
                request.discountValue(),
                request.maxDiscountAmount(),
                request.minOrderAmount(),
                request.usageLimit(),
                request.startsAt(),
                request.expiresAt()
        );

        return voucherMapper.toResponse(voucherRepository.save(voucher));
    }

    @Override
    @Transactional
    public void activateVoucher(UUID id) {
        Voucher voucher = voucherRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.VOUCHER_NOT_FOUND));
        voucher.activate();
        voucherRepository.save(voucher);
    }

    @Override
    @Transactional
    public void deactivateVoucher(UUID id) {
        Voucher voucher = voucherRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.VOUCHER_NOT_FOUND));
        voucher.deactivate();
        voucherRepository.save(voucher);
    }

    @Override
    @Transactional
    public void deleteVoucher(UUID id) {
        Voucher voucher = voucherRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.VOUCHER_NOT_FOUND));
        voucher.softDelete();
        voucherRepository.save(voucher);
    }

    @Override
    @Transactional(readOnly = true)
    public VoucherResponse getVoucher(UUID id) {
        Voucher voucher = voucherRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.VOUCHER_NOT_FOUND));
        return voucherMapper.toResponse(voucher);
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<VoucherResponse> listVouchers(int page, int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        var voucherPage = voucherRepository.findByStatusNot(VoucherStatus.DELETED, pageable)
                .map(voucherMapper::toResponse);
        return PagedResponse.from(voucherPage);
    }

    @Override
    @Transactional(readOnly = true, noRollbackFor = AppException.class)
    public ValidateVoucherResponse validateVoucher(String code, BigDecimal orderSubtotal) {
        Voucher voucher = voucherRepository.findByCode(normalizeCode(code))
                .orElseThrow(() -> new AppException(ErrorCode.VOUCHER_NOT_FOUND));

        BigDecimal discountAmount = validateAndComputeDiscount(voucher, orderSubtotal, Instant.now(clock));

        return ValidateVoucherResponse.builder()
                .code(voucher.getCode())
                .discountAmount(discountAmount)
                .build();
    }

    @Override
    @Transactional
    public BigDecimal reserveVoucher(String code, UUID checkoutSessionId, UUID buyerId, BigDecimal orderSubtotal) {
        Voucher voucher = voucherRepository.findByCodeForUpdate(normalizeCode(code))
                .orElseThrow(() -> new AppException(ErrorCode.VOUCHER_NOT_FOUND));

        BigDecimal discountAmount = validateAndComputeDiscount(voucher, orderSubtotal, Instant.now(clock));

        voucher.incrementUsage();
        voucherRepository.save(voucher);

        voucherUsageRepository.save(VoucherUsage.builder()
                .voucherId(voucher.getId())
                .checkoutSessionId(checkoutSessionId)
                .buyerId(buyerId)
                .discountAmount(discountAmount)
                .build());

        return discountAmount;
    }

    @Override
    @Transactional
    public void confirmVoucherUsage(UUID checkoutSessionId) {
        voucherUsageRepository.findByCheckoutSessionIdForUpdate(checkoutSessionId).ifPresent(usage -> {
            if (usage.getStatus() == VoucherUsageStatus.RESERVED) {
                usage.confirm();
                voucherUsageRepository.save(usage);
            }
        });
    }

    @Override
    @Transactional
    public void releaseVoucherUsage(UUID checkoutSessionId) {
        voucherUsageRepository.findByCheckoutSessionIdForUpdate(checkoutSessionId).ifPresent(usage -> {
            if (usage.getStatus() != VoucherUsageStatus.RESERVED) {
                return;
            }
            usage.release();
            voucherUsageRepository.save(usage);
            voucherRepository.findByIdForUpdate(usage.getVoucherId()).ifPresent(voucher -> {
                voucher.decrementUsage();
                voucherRepository.save(voucher);
            });
        });
    }

    /**
     * Shared validation used both by the standalone /validate preview endpoint and by
     * checkout reservation — keeps the eligibility rules in exactly one place.
     */
    BigDecimal validateAndComputeDiscount(Voucher voucher, BigDecimal orderSubtotal, Instant now) {
        if (!voucher.isActive()) {
            throw new AppException(ErrorCode.VOUCHER_NOT_ACTIVE);
        }
        if (!voucher.isWithinValidityWindow(now)) {
            throw new AppException(ErrorCode.VOUCHER_EXPIRED);
        }
        if (!voucher.hasUsageRemaining()) {
            throw new AppException(ErrorCode.VOUCHER_USAGE_LIMIT_REACHED);
        }
        if (!voucher.isOrderTotalEligible(orderSubtotal)) {
            throw new AppException(ErrorCode.VOUCHER_MIN_ORDER_NOT_MET);
        }
        return voucher.computeDiscount(orderSubtotal);
    }

    private String normalizeCode(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }
}
