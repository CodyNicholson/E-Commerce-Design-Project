package com.example.mromirror.config;

import com.example.mromirror.api.Accounts;
import com.example.mromirror.api.ApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private static final Logger log = LoggerFactory.getLogger("http");

    private final Accounts accounts;

    public WebConfig(Accounts accounts) {
        this.accounts = accounts;
    }

    /**
     * Resolves the acting persona for any handler that takes an Accounts.User.
     * The mirror has no login: the UI's persona switcher sends X-User-ID so the
     * same SKU can be viewed through different corporate accounts side by side.
     */
    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.getParameterType() == Accounts.User.class;
            }

            @Override
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest request,
                                          WebDataBinderFactory binderFactory) {
                String id = request.getHeader("X-User-ID");
                if (id == null || id.isEmpty()) {
                    id = "u-dana";
                }
                try {
                    return accounts.loadUser(id);
                } catch (EmptyResultDataAccessException e) {
                    throw ApiException.badRequest("unknown user " + id);
                }
            }
        });
    }

    @Bean
    public OncePerRequestFilter requestLog() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                    throws ServletException, IOException {
                long start = System.nanoTime();
                try {
                    chain.doFilter(req, res);
                } finally {
                    log.info("{} {} {}ms", req.getMethod(), req.getRequestURI(), (System.nanoTime() - start) / 1_000_000);
                }
            }
        };
    }
}
