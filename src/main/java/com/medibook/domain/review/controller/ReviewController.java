package com.medibook.domain.review.controller;

import com.medibook.common.response.ApiResponse;
import com.medibook.domain.review.dto.ReviewRequest;
import com.medibook.domain.review.dto.ReviewResponse;
import com.medibook.domain.review.service.ReviewService;
import com.medibook.security.CurrentUser;
import com.medibook.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/reviews")
@RequiredArgsConstructor
@Tag(name = "Reviews", description = "Doctor review and rating operations")
public class ReviewController {

    private final ReviewService reviewService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('PATIENT')")
    @Operation(summary = "Submit a review for a completed appointment")
    public ApiResponse<ReviewResponse> submitReview(
            @Valid @RequestBody ReviewRequest request,
            @CurrentUser UserPrincipal principal) {
        return ApiResponse.ok(reviewService.submitReview(request, principal));
    }

    @GetMapping("/doctors/{doctorId}")
    @Operation(summary = "Get approved reviews for a doctor (public)")
    public ApiResponse<Page<ReviewResponse>> getDoctorReviews(
            @PathVariable Long doctorId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok(reviewService.getApprovedReviewsForDoctor(doctorId, pageable));
    }

    @GetMapping("/my")
    @PreAuthorize("hasRole('PATIENT')")
    @Operation(summary = "Get my submitted reviews")
    public ApiResponse<Page<ReviewResponse>> getMyReviews(
            @CurrentUser UserPrincipal principal,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok(reviewService.getMyReviews(principal, pageable));
    }

    @PatchMapping("/{id}/moderate")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Approve or reject a review (admin only)")
    public ApiResponse<ReviewResponse> moderateReview(
            @PathVariable Long id,
            @RequestParam String action,
            @CurrentUser UserPrincipal principal) {
        return ApiResponse.ok(reviewService.moderateReview(id, action, principal));
    }

    @GetMapping("/admin/pending")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "List all reviews pending moderation (admin/super_admin only)")
    public ApiResponse<Page<ReviewResponse>> getPendingReviews(
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok(reviewService.getPendingReviews(pageable));
    }
}
