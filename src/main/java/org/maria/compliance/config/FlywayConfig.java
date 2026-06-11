package org.maria.compliance.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.util.List;

@Slf4j
@Configuration
@Profile("!test")
public class FlywayConfig {

    private static final List<String> SCHEMA_PROFILES = List.of("dev", "train", "prod");

    private final Environment environment;

    @Value("${spring.datasource.url:jdbc:postgresql://localhost:5432/compliance_db}")
    private String jdbcUrl;

    @Value("${spring.datasource.username:postgres}")
    private String username;

    @Value("${spring.datasource.password:postgres}")
    private String password;

    public FlywayConfig(Environment environment) {
        this.environment = environment;
    }

    /**
     * Scans ALL active profiles for a schema-mapped one, not just the first
     * Profile activation order is caller-controlled: "scrape,prod" used to resolve
     * to the dev schema because index 0 was "scrape" — a scrape run intended for
     * prod would silently ingest into dev
     */
    private String getSchemaName() {
        for (String profile : environment.getActiveProfiles()) {
            if (SCHEMA_PROFILES.contains(profile)) {
                return profile;
            }
        }
        return "dev";
    }

    @Bean
    public DataSource dataSource() {
        String schemaName = getSchemaName();

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName("org.postgresql.Driver");
        config.setSchema(schemaName);
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
        config.setPoolName("ComplianceHikariPool-" + schemaName);

        return new HikariDataSource(config);
    }

    @Bean(initMethod = "migrate")
    public Flyway flyway(DataSource dataSource) {
        String schemaName = getSchemaName();

        log.info("Flyway: activeProfiles={} schema={}",
                String.join(",", environment.getActiveProfiles()), schemaName);

        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .validateOnMigrate(true)
                .defaultSchema(schemaName)
                .createSchemas(true)
                .schemas(schemaName)
                .load();
    }
}