package com.finsentinel.platform;

import com.finsentinel.platform.security.Tenancy;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Test code itself (fixtures, direct SQL, assertions through repositories) runs in the system scope, like a DBA. The
 * code under test sets its own scope at every entry point (requests, intake, the worker, the agents' tools), which
 * replaces this one; code on other threads (worker tasks, graph nodes) never sees it.
 */
class SystemScopeExtension implements BeforeEachCallback, AfterEachCallback {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(SystemScopeExtension.class);

    @Override
    public void beforeEach(ExtensionContext context) {
        context.getStore(NAMESPACE).put("scope", Tenancy.enter(Tenancy.SYSTEM));
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        AutoCloseable scope = context.getStore(NAMESPACE).remove("scope", AutoCloseable.class);
        if (scope != null) {
            scope.close();
        }
    }
}
