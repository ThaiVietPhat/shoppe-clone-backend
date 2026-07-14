package com.shopee.monolith.modules.media.controller;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.common.response.ApiResponse;
import com.shopee.monolith.modules.auth.dto.internal.AccessTokenClaims;
import com.shopee.monolith.modules.media.dto.internal.MediaFileData;
import com.shopee.monolith.modules.media.dto.internal.MediaOwnerTypeCode;
import com.shopee.monolith.modules.media.dto.internal.MediaPurposeCode;
import com.shopee.monolith.modules.media.dto.response.MediaAssetResponse;
import com.shopee.monolith.modules.media.service.MediaService;
import com.shopee.monolith.modules.user.dto.internal.ShopLookupData;
import com.shopee.monolith.modules.user.service.ShopService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

@Tag(name = "Media", description = "Media upload and asset management APIs")
@RestController
@RequestMapping("/api/media")
@RequiredArgsConstructor
public class MediaController {

    private final MediaService mediaService;
    private final ShopService shopService;

    @Value("#{${app.media.max-file-size-mb:10} * 1048576}")
    private long maxImageSizeBytes;

    @Operation(
            summary = "Upload an image",
            description = "Uploads a product/avatar/shop-logo image. "
                    + "Validates MIME type via magic bytes (not Content-Type header), file extension (jpg/jpeg/png/webp) "
                    + "and configured size limit. Returns metadata with a public URL. "
                    + "For SHOP-owned media, ownerId must be a shop owned by the current user. "
                    + "For USER-owned media, ownerId must be the current user ID.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Image uploaded successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Invalid file type, extension or file too large",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Authentication required"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Media owner mismatch")
    })
    @PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<MediaAssetResponse> uploadImage(
            @Parameter(description = "The image file (jpg, jpeg, png, webp only)", required = true)
            @RequestParam("file") MultipartFile file,

            @Parameter(description = "Owner entity ID (shopId for PRODUCT_IMAGE / SHOP_LOGO)", required = true)
            @RequestParam("ownerId") UUID ownerId,

            @Parameter(description = "Owner type: SHOP or USER", example = "SHOP", required = true)
            @RequestParam("ownerType") MediaOwnerTypeCode ownerType,

            @Parameter(description = "Purpose: PRODUCT_IMAGE, AVATAR, SHOP_LOGO, RETURN_EVIDENCE", example = "PRODUCT_IMAGE", required = true)
            @RequestParam("purpose") MediaPurposeCode purpose,

            @AuthenticationPrincipal AccessTokenClaims claims
    ) throws IOException {
        if (claims == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        validateUploadSize(file);
        validateOwner(claims, ownerId, ownerType, purpose);
        MediaAssetResponse response = mediaService.uploadImage(
                ownerId,
                ownerType,
                purpose,
                file.getOriginalFilename(),
                file.getBytes(),
                file.getContentType()
        );
        return ApiResponse.success(response);
    }

    private void validateUploadSize(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_FILE_TYPE);
        }
        if (file.getSize() > maxImageSizeBytes) {
            throw new AppException(ErrorCode.FILE_TOO_LARGE);
        }
    }

    private void validateOwner(AccessTokenClaims claims, UUID ownerId, MediaOwnerTypeCode ownerType, MediaPurposeCode purpose) {
        if (ownerType == MediaOwnerTypeCode.USER) {
            validateUserOwner(claims, ownerId, purpose);
            return;
        }
        validateShopOwner(claims, ownerId, purpose);
    }

    private void validateUserOwner(AccessTokenClaims claims, UUID ownerId, MediaPurposeCode purpose) {
        boolean userScopedPurpose = purpose == MediaPurposeCode.AVATAR || purpose == MediaPurposeCode.RETURN_EVIDENCE;
        if (!userScopedPurpose || !claims.userId().equals(ownerId)) {
            throw new AppException(ErrorCode.MEDIA_OWNERSHIP_VIOLATION);
        }
    }

    private void validateShopOwner(AccessTokenClaims claims, UUID ownerId, MediaPurposeCode purpose) {
        if (purpose == MediaPurposeCode.AVATAR || purpose == MediaPurposeCode.RETURN_EVIDENCE) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        ShopLookupData shop = shopService.findShopLookupDataById(ownerId)
                .orElseThrow(() -> new AppException(ErrorCode.SHOP_NOT_FOUND));
        if (!claims.userId().equals(shop.ownerId())) {
            throw new AppException(ErrorCode.MEDIA_OWNERSHIP_VIOLATION);
        }
    }

    @Operation(
            summary = "Get media asset metadata",
            description = "Returns metadata and public URL for a media asset. Does not serve raw bytes."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Media asset found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Media asset not found")
    })
    @GetMapping("/{mediaId}")
    public ApiResponse<MediaAssetResponse> getMedia(
            @PathVariable UUID mediaId
    ) {
        return ApiResponse.success(mediaService.getMediaById(mediaId));
    }

    @Operation(
            summary = "Serve local media file",
            description = "Serves raw media bytes for local/test storage public URLs."
    )
    @GetMapping("/files/{objectKey}")
    public ResponseEntity<byte[]> getMediaFile(@PathVariable String objectKey) {
        MediaFileData file = mediaService.loadFile(objectKey);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.bytes());
    }
}
