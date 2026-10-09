package com.finsentinel.platform.security;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Whose data the current thread may touch in the database (ADR 0015). Every connection the platform hands out is set
 * to this scope ({@link TenantAwareDataSource}), and Postgres row-level security enforces it: a tenant sees only its
 * rows, the system scope sees all, and no scope is an error on any tenant table.
 *
 * <p>Scopes are set where the tenant is known from a trusted source, never from a method parameter deep inside: the
 * access token (HTTP and MCP requests), the alert or transaction itself (Kafka intake), the claimed case (the worker,
 * the agents' tools, the checkpoint saver). Thread-bound: code that hands work to another thread sets it again there.
 */
public final class Tenancy {

    /** A tenant id, or {@link #SYSTEM}. */
    public sealed interface Scope permits TenantScope, SystemScope {
    }

    public record TenantScope(String tenant) implements Scope {

        public TenantScope {
            if (tenant == null || tenant.isBlank()) {
                throw new IllegalArgumentException("a tenant scope needs a tenant");
            }
        }
    }

    public record SystemScope() implements Scope {
    }

    /** Cross-tenant platform work: claiming queued cases, timing out abandoned ones. Bypasses row-level security. */
    public static final Scope SYSTEM = new SystemScope();

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private Tenancy() {
    }

    public static Optional<Scope> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static <T> T call(String tenant, Supplier<T> work) {
        return within(new TenantScope(tenant), work);
    }

    public static void run(String tenant, Runnable work) {
        within(new TenantScope(tenant), () -> {
            work.run();
            return null;
        });
    }

    public static <T> T callAsSystem(Supplier<T> work) {
        return within(SYSTEM, work);
    }

    /** {@code scope} null: no scope, so tenant tables are refused (a request whose token has no tenant). */
    public static <T> T within(Scope scope, Supplier<T> work) {
        Scope previous = CURRENT.get();
        if (scope == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(scope);
        }
        try {
            return work.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /**
     * Enters a scope until the handle is closed, for code that cannot wrap its work in a lambda (test lifecycles).
     * Prefer {@link #call} and {@link #run}.
     */
    public static AutoCloseable enter(Scope scope) {
        Scope previous = CURRENT.get();
        CURRENT.set(scope);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    static String describe(Scope scope) {
        return Objects.toString(scope, "none");
    }
}
