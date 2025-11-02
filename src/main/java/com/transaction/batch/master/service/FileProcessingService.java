package com.transaction.batch.master.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class FileProcessingService {

    private final JobLauncher jobLauncher;
    private final Job transactionJob;

    @Async
    public void processFile(String bucketName, String key) {
        if (bucketName == null || bucketName.trim().isEmpty()) {
            log.error("Invalid bucket name: {}", bucketName);
            throw new IllegalArgumentException("Bucket name cannot be null or empty");
        }
        
        if (key == null || key.trim().isEmpty()) {
            log.error("Invalid file key: {}", key);
            throw new IllegalArgumentException("File key cannot be null or empty");
        }
        
        try {
            JobParameters jobParameters = new JobParametersBuilder()
                    .addString("bucketName", bucketName)
                    .addString("key", key)
                    .addLong("timestamp", System.currentTimeMillis())
                    .toJobParameters();

            log.info("Starting batch job for file: s3://{}/{}", bucketName, key);
            jobLauncher.run(transactionJob, jobParameters);
            log.info("Batch job completed successfully for file: {}", key);
            
        } catch (org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException e) {
            log.warn("Job already completed for file: {}", key);
            // This is not an error, job was already successfully processed
            
        } catch (org.springframework.batch.core.repository.JobExecutionAlreadyRunningException e) {
            log.warn("Job already running for file: {}", key);
            // Job is being processed, don't retry
            
        } catch (org.springframework.batch.core.JobExecutionException e) {
            log.error("Job execution failed for file: {}", key, e);
            throw new RuntimeException("Failed to execute batch job for file: " + key, e);
            
        } catch (Exception e) {
            log.error("Unexpected error processing file: {}", key, e);
            throw new RuntimeException("Unexpected error processing file: " + key, e);
        }
    }
}