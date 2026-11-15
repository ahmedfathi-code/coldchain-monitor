package com.coldchain.api.outbox;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * ShedLock: a {@code @Scheduled} method that also has {@code @SchedulerLock} runs on only one
 * instance at a time. The lock is a row in the MySQL table {@code shedlock}.
 * <p>
 * {@code usingDbTime()}: lock times come from MySQL's clock, so instances whose clocks
 * disagree cannot both believe they hold the lock.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT1M")
class SchedulerLockConfig {

    @Bean
    LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }
}
