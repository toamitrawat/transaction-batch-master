package com.transaction.batch.master.partitioner;

import com.transaction.batch.master.model.PartitionData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.kafka.core.KafkaTemplate;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class ByteRangePartitioner implements Partitioner {

    private static final long PARTITION_SIZE = 50 * 1024 * 1024; // 50 MB
    private static final int BUFFER_SIZE = 1024 * 1024; // 1 MB buffer to find line ending

    private final S3Client s3Client;
    private final KafkaTemplate<String, PartitionData> kafkaTemplate;
    private final String bucketName;
    private final String key;
    private final String topicName;
    private final String jobExecutionId;

    public ByteRangePartitioner(S3Client s3Client, KafkaTemplate<String, PartitionData> kafkaTemplate,
                                String bucketName, String key, String topicName, String jobExecutionId) {
        this.s3Client = s3Client;
        this.kafkaTemplate = kafkaTemplate;
        this.bucketName = bucketName;
        this.key = key;
        this.topicName = topicName;
        this.jobExecutionId = jobExecutionId;
    }

    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        if (bucketName == null || bucketName.trim().isEmpty()) {
            throw new IllegalArgumentException("Bucket name cannot be null or empty");
        }
        
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("File key cannot be null or empty");
        }
        
        Map<String, ExecutionContext> result = new HashMap<>();
        int failedKafkaSends = 0;

        try {
            HeadObjectResponse headObject = s3Client.headObject(
                    HeadObjectRequest.builder()
                            .bucket(bucketName)
                            .key(key)
                            .build()
            );

            long fileSize = headObject.contentLength();
            
            if (fileSize <= 0) {
                throw new IllegalStateException("File size is zero or negative: " + fileSize);
            }
            
            log.info("File size: {} bytes for s3://{}/{}", fileSize, bucketName, key);

            int partitionNumber = 0;
            long startByte = 0;

            while (startByte < fileSize) {
                // Calculate initial end byte
                long proposedEndByte = Math.min(startByte + PARTITION_SIZE - 1, fileSize - 1);
                
                // Adjust end byte to the next newline to avoid splitting records
                long adjustedEndByte = findNextLineEnding(proposedEndByte, fileSize);

                ExecutionContext context = new ExecutionContext();
                context.putString("bucketName", bucketName);
                context.putString("key", key);
                context.putLong("startByte", startByte);
                context.putLong("endByte", adjustedEndByte);
                context.putInt("partitionNumber", partitionNumber);

                result.put("partition" + partitionNumber, context);

                // Send partition data to Kafka
                PartitionData partitionData = new PartitionData(
                        bucketName, key, startByte, adjustedEndByte, partitionNumber, jobExecutionId
                );

                // Create final copies for lambda
                final int currentPartitionNumber = partitionNumber;
                final long currentStartByte = startByte;
                final long currentEndByte = adjustedEndByte;

                try {
                    kafkaTemplate.send(topicName, "partition-" + partitionNumber, partitionData)
                            .whenComplete((sendResult, exception) -> {
                                if (exception == null) {
                                    log.info("Sent partition {} to Kafka: bytes {}-{}", 
                                            currentPartitionNumber, currentStartByte, currentEndByte);
                                } else {
                                    log.error("Failed to send partition {} to Kafka: bytes {}-{}", 
                                            currentPartitionNumber, currentStartByte, currentEndByte, exception);
                                }
                            });
                } catch (Exception kafkaException) {
                    failedKafkaSends++;
                    log.error("Failed to send partition {} to Kafka immediately", currentPartitionNumber, kafkaException);
                }

                partitionNumber++;
                startByte = adjustedEndByte + 1; // Start next partition after the newline
            }

            log.info("Created {} partitions for file: {}", partitionNumber, key);
            
            if (failedKafkaSends > 0) {
                log.error("Failed to send {} out of {} partitions to Kafka", failedKafkaSends, partitionNumber);
                throw new RuntimeException(String.format(
                    "Failed to send %d partitions to Kafka. Aborting job.", failedKafkaSends));
            }
            
        } catch (software.amazon.awssdk.services.s3.model.NoSuchKeyException e) {
            log.error("File not found in S3: s3://{}/{}", bucketName, key, e);
            throw new RuntimeException("S3 file not found: " + key, e);
            
        } catch (software.amazon.awssdk.services.s3.model.S3Exception e) {
            log.error("S3 error while accessing file s3://{}/{}: {}", bucketName, key, e.getMessage(), e);
            throw new RuntimeException("S3 error: " + e.awsErrorDetails().errorMessage(), e);
            
        } catch (IllegalArgumentException | IllegalStateException e) {
            // Re-throw validation errors as-is
            throw e;
            
        } catch (Exception e) {
            log.error("Unexpected error during partitioning of s3://{}/{}", bucketName, key, e);
            throw new RuntimeException("Failed to partition file: " + key, e);
        }

        return result;
    }
    
    /**
     * Finds the next line ending (newline character) after the proposed end byte.
     * This ensures that records are not split across partitions.
     * 
     * @param proposedEndByte The initially calculated end byte (50MB boundary)
     * @param fileSize The total file size
     * @return The adjusted end byte position at the next newline, or fileSize-1 if at end of file
     */
    private long findNextLineEnding(long proposedEndByte, long fileSize) {
        // If we're at or near the end of file, just return the last byte
        if (proposedEndByte >= fileSize - 1) {
            return fileSize - 1;
        }
        
        try {
            // Read a small range of bytes starting from the proposed end position
            // to find the next newline character
            long rangeStart = proposedEndByte;
            long rangeEnd = Math.min(proposedEndByte + BUFFER_SIZE, fileSize - 1);
            
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .range("bytes=" + rangeStart + "-" + rangeEnd)
                    .build();
            
            try (ResponseInputStream<GetObjectResponse> s3Object = s3Client.getObject(getObjectRequest);
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(s3Object, StandardCharsets.UTF_8))) {
                
                int bytesRead = 0;
                int ch;
                
                // Read byte by byte until we find a newline
                while ((ch = reader.read()) != -1) {
                    if (ch == '\n') {
                        // Found newline! Return this position
                        long adjustedEndByte = rangeStart + bytesRead;
                        log.debug("Adjusted partition boundary from {} to {} (found newline)", 
                                proposedEndByte, adjustedEndByte);
                        return adjustedEndByte;
                    }
                    bytesRead++;
                }
                
                // If no newline found in buffer, use the end of buffer
                // (This shouldn't happen with a 1MB buffer unless you have very long lines)
                log.warn("No newline found within {} bytes after position {}. Using buffer end.", 
                        BUFFER_SIZE, proposedEndByte);
                return rangeEnd;
            }
            
        } catch (Exception e) {
            log.error("Error finding line ending at position {}. Using proposed end byte.", proposedEndByte, e);
            // Fallback to proposed end byte if there's an error
            return proposedEndByte;
        }
    }
}