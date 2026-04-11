package org.maria.compliance.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.util.Arrays;

@Configuration
@Profile("!test")
public class FlywayConfig {

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

    private String getSchemaName() {
        String[] activeProfiles = environment.getActiveProfiles();

        if (activeProfiles.length == 0) {
            return "dev";
        }

        String profile = activeProfiles[0];

        return Arrays.asList("dev", "train", "prod").contains(profile) ? profile : "dev";
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

        System.out.println("===========================================");
        System.out.println("Flyway: Active Profile=" + String.join(",", environment.getActiveProfiles())
                + " Schema=" + schemaName);
        System.out.println("===========================================");

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