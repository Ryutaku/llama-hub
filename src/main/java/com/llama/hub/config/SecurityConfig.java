package com.llama.hub.config;

import com.llama.hub.filter.ApiKeyAuthFilter;
import com.llama.hub.mapper.ApiKeyMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SecurityConfig implements WebMvcConfigurer {

    private final ApiKeyMapper apiKeyMapper;

    @Value("${gateway.admin.allowed-ips:}")
    private String allowedIps;

    public SecurityConfig(ApiKeyMapper apiKeyMapper) {
        this.apiKeyMapper = apiKeyMapper;
    }

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    public FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilter() {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(apiKeyMapper);
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
