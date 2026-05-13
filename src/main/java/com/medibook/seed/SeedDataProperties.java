package com.medibook.seed;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.seed")
public class SeedDataProperties {
    private boolean enabled = false;
}
