package com.finsentinel.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Runs the rest of an authenticated request in the caller's tenant scope (from the token), or in no scope when the
 * token has no tenant. Always sets it, so a request never inherits whatever scope its thread had.
 */
class TenancyFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Tenancy.Scope scope = Caller.current().map(Caller::tenant).filter(t -> !t.isBlank())
                .<Tenancy.Scope>map(Tenancy.TenantScope::new).orElse(null);
        try {
            Tenancy.within(scope, () -> {
                try {
                    chain.doFilter(request, response);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                } catch (ServletException e) {
                    throw new FilterFailure(e);
                }
                return null;
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        } catch (FilterFailure e) {
            throw e.servletException;
        }
    }

    private static final class FilterFailure extends RuntimeException {

        private final ServletException servletException;

        FilterFailure(ServletException e) {
            super(e);
            this.servletException = e;
        }
    }
}
