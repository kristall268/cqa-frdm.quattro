package com.cqafrdm.common;

import org.springframework.beans.factory.annotation.Value;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig {

    /** CORS_ORIGINS=http://localhost:5500,http://192.168.0.10:5500 — список через запятую (для демо с другого устройства). */
    @Value("${CORS_ORIGINS:http://localhost:5500,http://127.0.0.1:5500}")
    private String origins;

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins(java.util.Arrays.stream(origins.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toArray(String[]::new))
                        .allowedMethods("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("*");
            }
        };
    }
}