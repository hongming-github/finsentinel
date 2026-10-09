package com.finsentinel.platform.security;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
class TenancyConfig {

    /**
     * Wraps the pool, so repositories, JdbcClient, transactions and the Kafka listeners all get scoped connections
     * without knowing it. Flyway has its own connection as the schema owner ({@code spring.flyway.user}).
     */
    @Bean
    static BeanPostProcessor tenantAwareDataSource() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                return bean instanceof DataSource ds && !(bean instanceof TenantAwareDataSource)
                        ? new TenantAwareDataSource(ds) : bean;
            }
        };
    }
}
