# Transaction Batch Master - AI Coding Agent Instructions

## Project Overview
This is a **distributed Spring Batch master application** that partitions large CSV files from S3 into byte ranges and publishes partition metadata to Kafka for parallel processing by separate worker applications. It's NOT a traditional Spring Batch application with worker steps in the same JVM.

## Critical Architecture Patterns

### Distributed Processing Flow
```
S3 Upload → SQS Event → Master (this app) → Byte Partitioner → Kafka → Worker Apps (separate)
```

- **Master responsibility**: File partitioning and Kafka message publishing only
- **Worker responsibility**: Processing individual partitions (separate applications)
- **No worker steps** in this master application - workers consume from Kafka independently

### Record Boundary Preservation
The core business logic ensures CSV records are NEVER split across partitions:

```java
// In ByteRangePartitioner.findNextLineEnding()
long adjustedEndByte = findNextLineEnding(proposedEndByte, fileSize);
```

This uses S3 range requests with 1MB buffers to find the next `\n` character after each 50MB boundary, ensuring data integrity.

### Spring Batch Tasklet Pattern
Uses tasklet approach instead of traditional chunk processing:

```java
@Bean
@StepScope
public Tasklet partitionTasklet(@Value("#{jobParameters['bucketName']}") String bucketName...) {
    return (contribution, chunkContext) -> {
        ByteRangePartitioner partitioner = new ByteRangePartitioner(...);
        partitioner.partition(100);
        return RepeatStatus.FINISHED;
    };
}
```

**Why Tasklet**: No worker steps needed since workers are separate applications consuming Kafka.

## Key Configuration Patterns

### Oracle Database Requirements
- **SERVICE NAME format**: `jdbc:oracle:thin:@localhost:1521/XEPDB1` (not SID format)
- **Schema management**: `spring.batch.jdbc.initialize-schema: never` - manual SQL execution required
- **Transaction isolation**: `TRANSACTION_READ_COMMITTED` for Oracle compatibility

### AWS Integration
- **SQS Listener**: Handles both direct S3 events and SNS-wrapped messages automatically
- **S3 Event Processing**: Validates bucket/key, launches Spring Batch jobs asynchronously
- **IAM Requirements**: Needs `s3:GetObject` for byte-range requests (record boundary detection)

### Kafka Producer Pattern
- **Idempotent publishing**: Prevents duplicate partition messages
- **Async callbacks**: Uses `whenComplete()` for send confirmation logging
- **Error counting**: Tracks failed sends and aborts job if any partition fails

## Development Workflows

### Running the Application
```bash
# Requires: Oracle DB running, Kafka topic created, AWS credentials configured
mvn spring-boot:run

# Or run JAR with profile
java -jar target/transaction-batch-master-1.0.0.jar --spring.profiles.active=dev
```

### Testing File Processing
1. Upload CSV file to configured S3 bucket
2. Check SQS queue receives S3 ObjectCreated event
3. Monitor logs for partition creation and Kafka publishing
4. Verify Kafka topic receives partition metadata

### Database Schema Setup
```bash
# Run these SQL files manually (application doesn't auto-create):
sqlplus amit/Welcome123@XEPDB1 @src/main/resources/spring-batch-schema-oracle.sql
sqlplus amit/Welcome123@XEPDB1 @src/main/resources/schema.sql
```

### Kafka Topic Creation
```bash
# For Docker Kafka (connect to localhost:29092, not 9092)
docker exec -it kafka-container kafka-topics --create \
  --topic amit-transaction-partitions \
  --bootstrap-server localhost:9092 \
  --partitions 3 --replication-factor 1
```

## File Structure Conventions

### Package Organization
- `config/`: Spring configurations (AWS, Batch, DataSource, Kafka)
- `listener/`: SQS event listeners with robust JSON parsing
- `service/`: Async services with comprehensive error handling
- `partitioner/`: Core business logic for file partitioning
- `model/`: Serializable data transfer objects for Kafka

### Naming Patterns
- Job: `transactionJob` (single job for all file types)
- Step: `masterStep` (executes partitioning tasklet)
- Kafka Key: `"partition-" + partitionNumber`
- Job Parameters: `bucketName`, `key`, `timestamp` (for uniqueness)

## Error Handling Patterns

### SQS Message Processing
```java
// In S3EventListener - rethrow to prevent message deletion
catch (Exception e) {
    log.error("Error processing S3 event: {}", message, e);
    throw new RuntimeException("Failed to process S3 event", e);
}
```

### Spring Batch Job Idempotence
```java
// In FileProcessingService - handle duplicate job executions gracefully
catch (JobInstanceAlreadyCompleteException e) {
    log.warn("Job already completed for file: {}", key);
    // Don't rethrow - this is not an error
}
```

### Kafka Send Failures
```java
// In ByteRangePartitioner - abort entire job if any partition fails to send
if (failedKafkaSends > 0) {
    throw new RuntimeException(String.format(
        "Failed to send %d partitions to Kafka. Aborting job.", failedKafkaSends));
}
```

## Integration Points

### AWS Dependencies
- **S3**: HeadObject (file metadata), GetObject with ranges (boundary detection)
- **SQS**: Message polling with visibility timeout handling
- **Region**: Configured for `ap-south-1` (Mumbai)

### Kafka Dependencies
- **Topic**: `amit-transaction-partitions` (3 partitions, replication factor 1)
- **Serialization**: JSON for PartitionData objects
- **Delivery**: `acks=all`, idempotent producer, 3 retries

### Database Dependencies
- **Spring Batch Metadata**: 6 tables for job execution tracking
- **Application Tables**: TRANSACTION table for business data
- **Connection Pool**: HikariCP with Oracle-specific settings

## Common Debugging Scenarios

### "No Records array found in message"
Check SQS message format - may need SNS unwrapping (handled automatically by S3EventListener).

### "Job execution failed" 
Verify Oracle connection string uses SERVICE NAME format, not SID.

### "Failed to send partition to Kafka"
Check Kafka broker connectivity and topic existence. Use `localhost:29092` for Docker Kafka.

### Partition boundaries incorrect
Check S3 permissions for `GetObject` with range requests - needed for `findNextLineEnding()`.

This master application focuses solely on intelligent file partitioning and reliable message publishing. Worker applications handle the actual transaction processing from Kafka partition messages.