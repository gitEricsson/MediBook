package com.medibook.chat.dto;

/** Optionally include edited body when approving a draft. If null, original draft is sent. */
public record DraftApprovalRequest(String editedBody) {}
