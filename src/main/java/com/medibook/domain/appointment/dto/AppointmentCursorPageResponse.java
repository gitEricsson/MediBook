package com.medibook.domain.appointment.dto;

import com.medibook.common.response.CursorPageResponse;

public final class AppointmentCursorPageResponse {

    private AppointmentCursorPageResponse() {
    }

    public static CursorPageResponse<AppointmentResponse> of(
            java.util.List<AppointmentResponse> items,
            String nextCursor,
            boolean hasMore,
            int limit) {
        return CursorPageResponse.<AppointmentResponse>builder()
                .items(items)
                .nextCursor(nextCursor)
                .hasMore(hasMore)
                .limit(limit)
                .build();
    }
}
