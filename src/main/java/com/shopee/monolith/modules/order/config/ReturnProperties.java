package com.shopee.monolith.modules.order.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Configuration
@ConfigurationProperties(prefix = "app.order.return")
@Validated
@Getter
@Setter
public class ReturnProperties {

    private int windowDays = 7;

    @PostConstruct
    public void validate() {
        if (windowDays <= 0) {
            throw new IllegalStateException("app.order.return.window-days must be > 0");
        }
    }
}
