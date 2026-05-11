package com.medibook.domain.payment.dto;

import com.medibook.domain.payment.entity.Invoice;
import com.medibook.domain.payment.entity.InvoiceLineItem;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class InvoiceResponse {

    private Long id;
    private String invoiceNumber;
    private Long patientId;
    private String patientName;
    private Long doctorId;
    private String doctorName;
    private BigDecimal subtotal;
    private BigDecimal discount;
    private BigDecimal total;
    private String currency;
    private String status;
    private LocalDateTime issuedAt;
    private LocalDateTime paidAt;
    private List<LineItemDto> lineItems;

    @Data @Builder
    public static class LineItemDto {
        private String description;
        private int quantity;
        private BigDecimal unitPrice;
        private BigDecimal subtotal;
    }

    public static InvoiceResponse fromEntity(Invoice inv) {
        List<LineItemDto> items = inv.getLineItems().stream()
                .map(li -> LineItemDto.builder()
                        .description(li.getDescription())
                        .quantity(li.getQuantity())
                        .unitPrice(li.getUnitPrice())
                        .subtotal(li.getSubtotal())
                        .build())
                .toList();

        return InvoiceResponse.builder()
                .id(inv.getId())
                .invoiceNumber(inv.getInvoiceNumber())
                .patientId(inv.getPatient().getId())
                .patientName(inv.getPatient().getFullName())
                .doctorId(inv.getDoctor().getId())
                .doctorName(inv.getDoctor().getUser().getFullName())
                .subtotal(inv.getSubtotal())
                .discount(inv.getDiscount())
                .total(inv.getTotal())
                .currency(inv.getCurrency())
                .status(inv.getStatus())
                .issuedAt(inv.getIssuedAt())
                .paidAt(inv.getPaidAt())
                .lineItems(items)
                .build();
    }
}
