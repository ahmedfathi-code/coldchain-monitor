package com.coldchain.api.telemetry;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A second database connection, to ClickHouse (the first one, MySQL, is auto-configured by
 * Spring Boot and used by JPA and Flyway).
 * <p>
 * {@code defaultCandidate = false} means: this bean is only injected where the "clickhouse"
 * qualifier is asked for explicitly. So Spring Boot still creates the MySQL DataSource, and
 * JPA can never accidentally talk to ClickHouse.
 */
@Configuration
public class ClickHouseConfig {

    public static final String CLICKHOUSE = "clickhouse";

    /** Settings under {@code clickhouse.*} in application.yml. */
    @ConfigurationProperties("clickhouse")
    public record ClickHouseProperties(String url, String username, String password) {
    }

    @Bean(defaultCandidate = false)
    @Qualifier(CLICKHOUSE)
    HikariDataSource clickHouseDataSource(ClickHouseProperties properties) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("clickhouse");
        dataSource.setJdbcUrl(properties.url());
        dataSource.setUsername(properties.username());
        dataSource.setPassword(properties.password());
        dataSource.setMaximumPoolSize(5);
        return dataSource;
    }

    @Bean(defaultCandidate = false)
    @Qualifier(CLICKHOUSE)
    JdbcClient clickHouseJdbcClient(@Qualifier(CLICKHOUSE) HikariDataSource dataSource) {
        return JdbcClient.create(dataSource);
    }
}
