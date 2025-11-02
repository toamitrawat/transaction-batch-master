package com.transaction.batch.master.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;

@Configuration
public class DataSourceConfig {

    @Value("${spring.datasource.url}")
    private String url;

    @Value("${spring.datasource.username}")
    private String username;

    @Value("${spring.datasource.password}")
    private String password;

    @Bean
    public DataSource dataSource() {
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalStateException("Database URL is not configured");
        }
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalStateException("Database username is not configured");
        }
        if (password == null) {
            throw new IllegalStateException("Database password is not configured");
        }
        
        try {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(url);
            config.setUsername(username);
            config.setPassword(password);
            config.setDriverClassName("oracle.jdbc.OracleDriver");
            config.setMaximumPoolSize(20);
            config.setMinimumIdle(5);
            config.setConnectionTimeout(30000);
            config.setIdleTimeout(600000);
            config.setMaxLifetime(1800000);
            
            // Transaction isolation level - use READ_COMMITTED for Oracle to avoid ORA-08177
            config.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
            
            // Validation settings
            config.setConnectionTestQuery("SELECT 1 FROM DUAL");
            config.setValidationTimeout(5000);
            
            return new HikariDataSource(config);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create DataSource: " + e.getMessage(), e);
        }
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}