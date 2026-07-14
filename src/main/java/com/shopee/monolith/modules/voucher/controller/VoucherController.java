package com.shopee.monolith.modules.voucher.controller;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.common.response.ApiResponse;
import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.modules.auth.dto.internal.AccessTokenClaims;
import com.shopee.monolith.modules.voucher.dto.request.CreateVoucherRequest;
import com.shopee.monolith.modules.voucher.dto.request.UpdateVoucherRequest;
import com.shopee.monolith.modules.voucher.dto.request.ValidateVoucherRequest;
import com.shopee.monolith.modules.voucher.dto.response.ValidateVoucherResponse;
import com.shopee.monolith.modules.voucher.dto.response.VoucherResponse;
import com.shopee.monolith.modules.voucher.service.VoucherService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Vouchers", description = "Platform-wide voucher management (admin) and validation (buyer)")
public class VoucherController {

    private final VoucherService voucherService;

    @Operation(summary = "Create voucher", description = "Creates a new platform-wide voucher.", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/api/admin/vouchers")
    public ApiResponse<VoucherResponse> createVoucher(@Valid @RequestBody CreateVoucherRequest request) {
        return ApiResponse.success(voucherService.createVoucher(request));
    }

    @Operation(summary = "Update voucher", description = "Updates an existing voucher's terms.", security = @SecurityRequirement(name = "bearerAuth"))
    @PutMapping("/api/admin/vouchers/{voucherId}")
    public ApiResponse<VoucherResponse> updateVoucher(
            @Parameter(description = "Voucher unique ID") @PathVariable UUID voucherId,
            @Valid @RequestBody UpdateVoucherRequest request) {
        return ApiResponse.success(voucherService.updateVoucher(voucherId, request));
    }

    @Operation(summary = "List vouchers", description = "Paginated list of vouchers, excluding deleted ones.", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/api/admin/vouchers")
    public ApiResponse<PagedResponse<VoucherResponse>> listVouchers(
            @Parameter(description = "Page index (0-indexed)") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size (max 100)") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(voucherService.listVouchers(page, size));
    }

    @Operation(summary = "Get voucher", description = "Retrieves a single voucher by ID.", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/api/admin/vouchers/{voucherId}")
    public ApiResponse<VoucherResponse> getVoucher(
            @Parameter(description = "Voucher unique ID") @PathVariable UUID voucherId) {
        return ApiResponse.success(voucherService.getVoucher(voucherId));
    }

    @Operation(summary = "Activate voucher", description = "Reactivates an inactive voucher.", security = @SecurityRequirement(name = "bearerAuth"))
    @PatchMapping("/api/admin/vouchers/{voucherId}/activate")
    public ApiResponse<Void> activateVoucher(@Parameter(description = "Voucher unique ID") @PathVariable UUID voucherId) {
        voucherService.activateVoucher(voucherId);
        return ApiResponse.success();
    }

    @Operation(summary = "Deactivate voucher", description = "Deactivates an active voucher without deleting it.", security = @SecurityRequirement(name = "bearerAuth"))
    @PatchMapping("/api/admin/vouchers/{voucherId}/deactivate")
    public ApiResponse<Void> deactivateVoucher(@Parameter(description = "Voucher unique ID") @PathVariable UUID voucherId) {
        voucherService.deactivateVoucher(voucherId);
        return ApiResponse.success();
    }

    @Operation(summary = "Delete voucher", description = "Soft-deletes a voucher.", security = @SecurityRequirement(name = "bearerAuth"))
    @DeleteMapping("/api/admin/vouchers/{voucherId}")
    public ApiResponse<Void> deleteVoucher(@Parameter(description = "Voucher unique ID") @PathVariable UUID voucherId) {
        voucherService.deleteVoucher(voucherId);
        return ApiResponse.success();
    }

    @Operation(summary = "Validate voucher", description = "Validates a voucher code against an order subtotal and previews the discount amount.", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/api/vouchers/validate")
    public ApiResponse<ValidateVoucherResponse> validateVoucher(
            @Valid @RequestBody ValidateVoucherRequest request,
            @AuthenticationPrincipal AccessTokenClaims claims) {
        if (claims == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        return ApiResponse.success(voucherService.validateVoucher(request.code(), request.orderSubtotal()));
    }
}
