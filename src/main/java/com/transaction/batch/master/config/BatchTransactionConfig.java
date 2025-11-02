package com.transaction.batch.master.config;

import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.JobRepositoryFactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Configuration
public class BatchTransactionConfig {

    @Autowired
    private DataSource dataSource;

    @Bean
    @Primary
    public PlatformTransactionManager batchTransactionManager() {
        JdbcTransactionManager transactionManager = new JdbcTransactionManager(dataSource);
        transactionManager.setDefaultTimeout(300); // 5 minutes
        return transactionManager;
    }

    @Bean
    public JobRepository jobRepository(PlatformTransactionManager batchTransactionManager) throws Exception {
        JobRepositoryFactoryBean factory = new JobRepositoryFactoryBean();
        factory.setDataSource(dataSource);
        factory.setTransactionManager(batchTransactionManager);
        factory.setIsolationLevelForCreate("ISOLATION_READ_COMMITTED");
        factory.setTablePrefix("BATCH_");
        factory.afterPropertiesSet();
        return factory.getObject();
    }
}

