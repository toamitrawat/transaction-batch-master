package com.transaction.batch.master.listener;

import com.transaction.batch.master.service.FileProcessingService;
import io.awspring.cloud.sqs.annotation.SqsListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
@Slf4j
public class S3EventListener {

    private final FileProcessingService fileProcessingService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @SqsListener("${aws.sqs.queue.name}")
    public void receiveMessage(String message) {
        try {
            log.info("Received S3 event message");
            log.debug("Raw message: {}", message);
            
            if (message == null || message.trim().isEmpty()) {
                log.warn("Received empty or null message");
                return;
            }
            
            JsonNode root = objectMapper.readTree(message);
            log.debug("Parsed JSON root keys: {}", root.fieldNames());
            
            // Check if this is an SNS notification wrapper
            JsonNode records = root.get("Records");
            
            if (records == null) {
                // Try to unwrap SNS message
                JsonNode messageNode = root.get("Message");
                if (messageNode != null) {
                    log.info("Detected SNS wrapper, unwrapping Message field");
                    String innerMessage = messageNode.asText();
                    JsonNode innerRoot = objectMapper.readTree(innerMessage);
                    records = innerRoot.get("Records");
                }
            }

            if (records == null || !records.isArray()) {
                log.warn("No Records array found in message");
                log.warn("Available fields in message: {}", root.fieldNames());
                log.warn("Full message structure: {}", root.toPrettyString());
                return;
            }

            for (JsonNode record : records) {
                processRecord(record);
            }
        } catch (Exception e) {
            log.error("Error processing S3 event: {}", message, e);
            // Re-throw to prevent SQS message deletion and allow retry
            throw new RuntimeException("Failed to process S3 event", e);
        }
    }

    private void processRecord(JsonNode record) {
        try {
            JsonNode eventNameNode = record.get("eventName");
            if (eventNameNode == null) {
                log.warn("Missing eventName in record");
                return;
            }

            String eventName = eventNameNode.asText();
            if (!eventName.startsWith("ObjectCreated")) {
                log.debug("Ignoring non-ObjectCreated event: {}", eventName);
                return;
            }

            JsonNode s3Node = record.get("s3");
            if (s3Node == null) {
                log.warn("Missing s3 node in record");
                return;
            }

            JsonNode bucketNode = s3Node.get("bucket");
            JsonNode objectNode = s3Node.get("object");
            
            if (bucketNode == null || objectNode == null) {
                log.warn("Missing bucket or object node in s3 record");
                return;
            }

            JsonNode bucketNameNode = bucketNode.get("name");
            JsonNode keyNode = objectNode.get("key");
            
            if (bucketNameNode == null || keyNode == null) {
                log.warn("Missing bucket name or object key");
                return;
            }

            String bucketName = bucketNameNode.asText();
            String key = keyNode.asText();

            if (bucketName.isEmpty() || key.isEmpty()) {
                log.warn("Empty bucket name or key");
                return;
            }
            
            log.info("Processing file: s3://{}/{}", bucketName, key);
            fileProcessingService.processFile(bucketName, key);
            
        } catch (Exception e) {
            log.error("Error processing individual record", e);
            throw new RuntimeException("Failed to process S3 event record", e);
        }
    }
}