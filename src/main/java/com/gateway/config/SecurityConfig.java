package com.gateway.config;

import com.gateway.filter.ApiKeyAuthFilter;
import com.gateway.repository.ApiKeyRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SecurityConfig implements WebMvcConfigurer {

    private final ApiKeyRepository apiKeyRepository;

    @Value("${gateway.admin.allowed-ips:}")
    private String allowedIps;

    public SecurityConfig(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    public FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilter() {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(apiKeyRepository);
        FilterRegistrationBean<ApiKeyAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/v1/*");
        registration.setName("apiKeyAuthFilter");
        registration.setOrder(10);
        return registration;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AdminAuthInterceptor(allowedIps))
                .addPathPatterns("/api/**", "/", "/index.html");
    }
}
