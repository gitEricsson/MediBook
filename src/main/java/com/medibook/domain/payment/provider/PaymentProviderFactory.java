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
        PaymentProviderPort port = ensureMap().get(provider);
        if (port == null) {
            throw new MediBookException(
                    "Payment provider not configured: " + provider,
                    HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_NOT_CONFIGURED");
        }
        return port;
    }

    /**
     * Returns the set of payment providers currently wired into the application.
     *
     * <p>Each provider class is {@code @ConditionalOnProperty}-gated by
     * {@code app.payment.<name>.enabled=true} in the yaml/env config, so this is
     * effectively the deployment's payment-method allow-list. The FE pulls it
     * before rendering the gateway buttons so we don't surface options that
     * would 503 on click with {@code PROVIDER_NOT_CONFIGURED}.
     */
    public java.util.Set<PaymentProvider> getEnabledProviders() {
        return ensureMap().keySet();
    }

    private Map<PaymentProvider, PaymentProviderPort> ensureMap() {
        if (providerMap == null) {
            providerMap = providers.stream()
                    .collect(Collectors.toMap(PaymentProviderPort::getProvider, Function.identity()));
        }
        return providerMap;
    }
}
