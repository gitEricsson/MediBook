package com.medibook.common.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CursorPageResponse<T> {

    private List<T> items;
    private String nextCursor;
    private boolean hasMore;
    private int limit;
}
