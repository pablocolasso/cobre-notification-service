package com.cobre.notification.adapter.in.web;

import com.cobre.notification.application.port.in.Requester;
import com.cobre.notification.config.SecurityProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecurityProperties.class)
class WebMvcConfiguration implements WebMvcConfigurer {

    @Bean
    FilterRegistrationBean<ApiKeyAuthenticationFilter> apiKeyAuthenticationFilter(SecurityProperties properties,
                                                                                  ObjectMapper objectMapper) {
        var filter = new ApiKeyAuthenticationFilter(properties, objectMapper);
        var registration = new FilterRegistrationBean<>(filter);
        registration.setName("apiKeyAuthenticationFilter");
        registration.addUrlPatterns("/notification_events", "/notification_events/*");
        registration.setOrder(0);
        return registration;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new RequesterArgumentResolver());
        resolvers.add(new CorrelationIdArgumentResolver());
    }

    static final class RequesterArgumentResolver implements HandlerMethodArgumentResolver {

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return Requester.class.isAssignableFrom(parameter.getParameterType());
        }

        @Override
        public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                      NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
            Object requester = webRequest.getAttribute(ApiKeyAuthenticationFilter.REQUESTER_ATTRIBUTE,
                    NativeWebRequest.SCOPE_REQUEST);
            if (requester instanceof Requester value) {
                return value;
            }
            throw new IllegalStateException("Requester is missing; the API key filter did not run");
        }
    }

    static final class CorrelationIdArgumentResolver implements HandlerMethodArgumentResolver {

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.hasParameterAnnotation(CorrelationId.class) && parameter.getParameterType() == String.class;
        }

        @Override
        public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                      NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
            Object value = webRequest.getAttribute(ApiKeyAuthenticationFilter.CORRELATION_ID_ATTRIBUTE,
                    NativeWebRequest.SCOPE_REQUEST);
            return value instanceof String correlationId ? correlationId : null;
        }
    }
}
