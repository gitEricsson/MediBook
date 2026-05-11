package com.medibook.domain.payment.provider;

import com.medibook.common.exception.MediBookException;
import com.medibook.domain.payment.entity.PaymentProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class PaymentProviderFactory {

    private final List<PaymentProviderPort> providers;

    private Map<PaymentProvider, PaymentProviderPort> providerMap;

    public PaymentProviderPort get(PaymentProvider provider) {
        if (providerMap == null) {
            providerMap = providers.stream()
                    .collect(Collectors.toMap(PaymentProviderPort::getProvider, Function.identity()));
        }
        PaymentProviderPort port = providerMap.get(provider);
        if (port == null) {
            throw new MediBookException(
                    "Payment provider not configured: " + provider,
                    HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_NOT_CONFIGURED");
        }
        return port;
    }
}
