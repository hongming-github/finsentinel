package com.finsentinel.platform.security;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Sets every connection it hands out to the current {@link Tenancy} scope before anyone uses it: the session's
 * {@code app.tenant} (read by the row-level security policies) and its role ({@value #SYSTEM_ROLE} for the system
 * scope, the login role otherwise). Set on every borrow, so a pooled connection never carries the previous borrower's
 * tenant. One round trip per borrow, which with Spring's transactions is once per transaction.
 *
 * <p>Runs while the connection is still in auto-commit (as the pool hands it out), so the settings are committed at
 * once: a transaction that later rolls back cannot undo them.
 */
class TenantAwareDataSource extends DelegatingDataSource {

    static final String SYSTEM_ROLE = "finsentinel_system";

    TenantAwareDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return scoped(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return scoped(super.getConnection(username, password));
    }

    private static Connection scoped(Connection connection) throws SQLException {
        Tenancy.Scope scope = Tenancy.current().orElse(null);
        String role = scope instanceof Tenancy.SystemScope ? SYSTEM_ROLE : "none";
        String tenant = scope instanceof Tenancy.TenantScope t ? t.tenant() : "";
        try (PreparedStatement statement = connection.prepareStatement(
                "select set_config('role', ?, false), set_config('app.tenant', ?, false)")) {
            statement.setString(1, role);
            statement.setString(2, tenant);
            statement.execute();
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }
}
