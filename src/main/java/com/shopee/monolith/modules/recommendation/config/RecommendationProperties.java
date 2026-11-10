package com.shopee.monolith.modules.recommendation.config;

import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Configuration
@ConfigurationProperties(prefix = "app.recommendation")
@Validated
@Getter
@Setter
public class RecommendationProperties {

    @NotNull
    private Duration homeCacheTtl = Duration.ofDays(30);

    @PostConstruct
    public void validateProperties() {
        if (homeCacheTtl == null || homeCacheTtl.isNegative() || homeCacheTtl.isZero()) {
            throw new IllegalStateException("Recommendation home cache TTL must be positive");
        }
    }
}
