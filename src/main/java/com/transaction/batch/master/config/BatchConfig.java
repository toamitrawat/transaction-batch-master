package com.transaction.batch.master.config;

import com.transaction.batch.master.model.PartitionData;
import com.transaction.batch.master.partitioner.ByteRangePartitioner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import software.amazon.awssdk.services.s3.S3Client;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class BatchConfig {

    private final S3Client s3Client;
    private final KafkaTemplate<String, PartitionData> kafkaTemplate;

    @Value("${kafka.topic.partition}")
    private String partitionTopic;

    @Bean
    public Job transactionJob(JobRepository jobRepository, Step masterStep) {
        return new JobBuilder("transactionJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .start(masterStep)
                .build();
    }

    @Bean
    public Step masterStep(JobRepository jobRepository, PlatformTransactionManager transactionManager) {
        return new StepBuilder("masterStep", jobRepository)
                .tasklet(partitionTasklet(null, null, null), transactionManager)
                .build();
    }

    @Bean
    @StepScope
    public Tasklet partitionTasklet(
            @Value("#{jobParameters['bucketName']}") String bucketName,
            @Value("#{jobParameters['key']}") String key,
            @Value("#{jobParameters['timestamp']}") Long timestamp) {
        return (contribution, chunkContext) -> {
            log.info("Executing partition tasklet for file: s3://{}/{}", bucketName, key);
            
            ByteRangePartitioner partitioner = new ByteRangePartitioner(
                    s3Client, kafkaTemplate, bucketName, key, partitionTopic, String.valueOf(timestamp));
            
            // Execute partitioning - this will send messages to Kafka
            partitioner.partition(100);
            
            log.info("Partitioning completed and messages sent to Kafka");
            return RepeatStatus.FINISHED;
        };
    }
}