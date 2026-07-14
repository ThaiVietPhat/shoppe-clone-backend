package com.shopee.monolith.modules.voucher.service;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.modules.voucher.dto.request.CreateVoucherRequest;
import com.shopee.monolith.modules.voucher.dto.request.UpdateVoucherRequest;
import com.shopee.monolith.modules.voucher.dto.response.ValidateVoucherResponse;
import com.shopee.monolith.modules.voucher.dto.response.VoucherResponse;
import com.shopee.monolith.modules.voucher.entity.Voucher;
import com.shopee.monolith.modules.voucher.mapper.VoucherMapper;
import com.shopee.monolith.modules.voucher.model.DiscountType;
import com.shopee.monolith.modules.voucher.model.VoucherStatus;
import com.shopee.monolith.modules.voucher.repository.VoucherRepository;
import com.shopee.monolith.modules.voucher.repository.VoucherUsageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoucherServiceImplTest {

    @Mock
    private VoucherRepository voucherRepository;

    @Mock
    private VoucherUsageRepository voucherUsageRepository;

    @Mock
    private VoucherMapper voucherMapper;

    private Clock clock;

    private VoucherServiceImpl voucherService;

    private final Instant now = Instant.parse("2026-06-03T12:00:00Z");

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(now, ZoneOffset.UTC);
        voucherService = new VoucherServiceImpl(voucherRepository, voucherUsageRepository, voucherMapper, clock);
        lenient().when(voucherMapper.toResponse(any(Voucher.class)))
                .thenAnswer(invocation -> {
                    Voucher v = invocation.getArgument(0);
                    return VoucherResponse.builder().id(v.getId()).code(v.getCode()).build();
                });
    }

    private Voucher.VoucherBuilder<?, ?> activeVoucher() {
        return Voucher.builder()
                .code("WELCOME10")
                .discountType(DiscountType.PERCENTAGE)
                .discountValue(BigDecimal.TEN)
                .minOrderAmount(BigDecimal.ZERO)
                .startsAt(now.minus(Duration.ofDays(1)))
                .expiresAt(now.plus(Duration.ofDays(1)))
                .status(VoucherStatus.ACTIVE);
    }

    @Test
    void createVoucherWhenValidShouldNormalizeCodeAndPersist() {
        CreateVoucherRequest request = CreateVoucherRequest.builder()
                .code(" welcome10 ")
                .discountType(DiscountType.PERCENTAGE)
                .discountValue(BigDecimal.TEN)
                .minOrderAmount(BigDecimal.ZERO)
                .startsAt(now)
                .expiresAt(now.plus(Duration.ofDays(30)))
                .build();

        when(voucherRepository.saveAndFlush(any(Voucher.class))).thenAnswer(inv -> inv.getArgument(0));

        voucherService.createVoucher(request);

        ArgumentCaptor<Voucher> captor = ArgumentCaptor.forClass(Voucher.class);
        verify(voucherRepository).saveAndFlush(captor.capture());
        assertEquals("WELCOME10", captor.getValue().getCode());
    }

    @Test
    void createVoucherWhenExpiryBeforeStartShouldThrowInvalidDateRange() {
        CreateVoucherRequest request = CreateVoucherRequest.builder()
                .code("BAD")
                .discountType(DiscountType.FIXED_AMOUNT)
                .discountValue(BigDecimal.TEN)
                .minOrderAmount(BigDecimal.ZERO)
                .startsAt(now)
                .expiresAt(now.minus(Duration.ofDays(1)))
                .build();

        AppException exception = assertThrows(AppException.class, () -> voucherService.createVoucher(request));
        assertEquals(ErrorCode.VOUCHER_INVALID_DATE_RANGE, exception.getErrorCode());
    }

    @Test
    void createVoucherWhenCodeAlreadyExistsShouldThrowCodeAlreadyExists() {
        CreateVoucherRequest request = CreateVoucherRequest.builder()
                .code("DUP")
                .discountType(DiscountType.FIXED_AMOUNT)
                .discountValue(BigDecimal.TEN)
                .minOrderAmount(BigDecimal.ZERO)
                .startsAt(now)
                .expiresAt(now.plus(Duration.ofDays(1)))
                .build();

        when(voucherRepository.saveAndFlush(any(Voucher.class))).thenThrow(new DataIntegrityViolationException("dup"));

        AppException exception = assertThrows(AppException.class, () -> voucherService.createVoucher(request));
        assertEquals(ErrorCode.VOUCHER_CODE_ALREADY_EXISTS, exception.getErrorCode());
    }

    @Test
    void updateVoucherWhenMissingShouldThrowNotFound() {
        UUID id = UUID.randomUUID();
        UpdateVoucherRequest request = UpdateVoucherRequest.builder()
                .discountType(DiscountType.FIXED_AMOUNT)
                .discountValue(BigDecimal.TEN)
                .minOrderAmount(BigDecimal.ZERO)
                .startsAt(now)
                .expiresAt(now.plus(Duration.ofDays(1)))
                .build();
        when(voucherRepository.findById(id)).thenReturn(Optional.empty());

        AppException exception = assertThrows(AppException.class, () -> voucherService.updateVoucher(id, request));
        assertEquals(ErrorCode.VOUCHER_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void deleteVoucherShouldSoftDeleteAndSave() {
        UUID id = UUID.randomUUID();
        Voucher voucher = activeVoucher().build();
        when(voucherRepository.findById(id)).thenReturn(Optional.of(voucher));
        when(voucherRepository.save(voucher)).thenReturn(voucher);

        voucherService.deleteVoucher(id);

        assertEquals(VoucherStatus.DELETED, voucher.getStatus());
        verify(voucherRepository).save(voucher);
    }

    @Test
    void listVouchersShouldExcludeDeletedAndMapPage() {
        Voucher voucher = activeVoucher().build();
        Page<Voucher> page = new PageImpl<>(List.of(voucher), PageRequest.of(0, 20), 1);
        when(voucherRepository.findByStatusNot(eqStatus(VoucherStatus.DELETED), any())).thenReturn(page);

        PagedResponse<VoucherResponse> result = voucherService.listVouchers(0, 20);

        assertEquals(1, result.items().size());
    }

    private VoucherStatus eqStatus(VoucherStatus status) {
        return org.mockito.ArgumentMatchers.eq(status);
    }

    @Test
    void validateVoucherWhenActiveAndEligibleShouldReturnDiscount() {
        Voucher voucher = activeVoucher().discountValue(new BigDecimal("10")).build();
        when(voucherRepository.findByCode("WELCOME10")).thenReturn(Optional.of(voucher));

        ValidateVoucherResponse response = voucherService.validateVoucher("welcome10", new BigDecimal("100"));

        assertEquals(new BigDecimal("10.00"), response.discountAmount());
    }

    @Test
    void validateVoucherWhenNotFoundShouldThrowNotFound() {
        when(voucherRepository.findByCode("MISSING")).thenReturn(Optional.empty());

        AppException exception = assertThrows(AppException.class,
                () -> voucherService.validateVoucher("missing", BigDecimal.TEN));
        assertEquals(ErrorCode.VOUCHER_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void validateVoucherWhenInactiveShouldThrowNotActive() {
        Voucher voucher = activeVoucher().status(VoucherStatus.INACTIVE).build();
        when(voucherRepository.findByCode("WELCOME10")).thenReturn(Optional.of(voucher));

        AppException exception = assertThrows(AppException.class,
                () -> voucherService.validateVoucher("WELCOME10", BigDecimal.TEN));
        assertEquals(ErrorCode.VOUCHER_NOT_ACTIVE, exception.getErrorCode());
    }

    @Test
    void validateVoucherWhenExpiredShouldThrowExpired() {
        Voucher voucher = activeVoucher()
                .startsAt(now.minus(Duration.ofDays(10)))
                .expiresAt(now.minus(Duration.ofDays(1)))
                .build();
        when(voucherRepository.findByCode("WELCOME10")).thenReturn(Optional.of(voucher));

        AppException exception = assertThrows(AppException.class,
                () -> voucherService.validateVoucher("WELCOME10", BigDecimal.TEN));
        assertEquals(ErrorCode.VOUCHER_EXPIRED, exception.getErrorCode());
    }

    @Test
    void validateVoucherWhenUsageLimitReachedShouldThrowLimitReached() {
        Voucher voucher = activeVoucher().usageLimit(1).usedCount(1).build();
        when(voucherRepository.findByCode("WELCOME10")).thenReturn(Optional.of(voucher));

        AppException exception = assertThrows(AppException.class,
                () -> voucherService.validateVoucher("WELCOME10", BigDecimal.TEN));
        assertEquals(ErrorCode.VOUCHER_USAGE_LIMIT_REACHED, exception.getErrorCode());
    }

    @Test
    void validateVoucherWhenBelowMinOrderShouldThrowMinOrderNotMet() {
        Voucher voucher = activeVoucher().minOrderAmount(new BigDecimal("200")).build();
        when(voucherRepository.findByCode("WELCOME10")).thenReturn(Optional.of(voucher));

        AppException exception = assertThrows(AppException.class,
                () -> voucherService.validateVoucher("WELCOME10", new BigDecimal("50")));
        assertEquals(ErrorCode.VOUCHER_MIN_ORDER_NOT_MET, exception.getErrorCode());
    }

    @Test
    void computeDiscountShouldCapAtMaxDiscountAmountForPercentage() {
        Voucher voucher = activeVoucher()
                .discountType(DiscountType.PERCENTAGE)
                .discountValue(new BigDecimal("50"))
                .maxDiscountAmount(new BigDecimal("20"))
                .build();

        BigDecimal discount = voucher.computeDiscount(new BigDecimal("100"));

        assertEquals(new BigDecimal("20.00"), discount);
    }

    @Test
    void computeDiscountShouldNotExceedOrderSubtotalForFixedAmount() {
        Voucher voucher = activeVoucher()
                .discountType(DiscountType.FIXED_AMOUNT)
                .discountValue(new BigDecimal("999"))
                .build();

        BigDecimal discount = voucher.computeDiscount(new BigDecimal("30"));

        assertEquals(new BigDecimal("30.00"), discount);
    }
}
