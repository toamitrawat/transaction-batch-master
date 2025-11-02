# Transaction Batch Master

A Spring Batch application that processes large transaction files from AWS S3 using byte-range partitioning and distributes partition metadata via Kafka for parallel processing by worker nodes.

## Overview

This master application is part of a distributed transaction processing system that:
- Listens for S3 file upload events via AWS SQS
- Partitions large files into byte ranges (~50MB chunks aligned to record boundaries)
- **Ensures data integrity** by keeping CSV records intact across partitions
- Publishes partition metadata to Kafka for worker nodes to process
- Coordinates distributed batch processing using Spring Batch
- Supports idempotent operations and robust error handling

## Architecture

```
S3 Upload → SQS Event → Master Application → Byte Range Partitioner → Kafka Topic
                              ↓                      ↓
                        Spring Batch Job      Partition Metadata
                              ↓                      ↓
                        Oracle DB Metadata    Worker Nodes
                                                     ↓
                                              Process Transactions
```

### Key Components

1. **S3EventListener**: Receives S3 ObjectCreated events from SQS (supports SNS wrapper)
2. **FileProcessingService**: Launches Spring Batch jobs asynchronously
3. **ByteRangePartitioner**: Divides large files into ~50MB byte ranges with record boundary alignment
4. **BatchConfig**: Tasklet-based job execution for partition creation
5. **Kafka Producer**: Sends partition metadata to worker nodes with idempotence
6. **Spring Batch**: Orchestrates the partitioning workflow with Oracle transaction support

## Key Features

### Data Integrity
- **Record Boundary Detection**: Automatically adjusts partition boundaries to align with newline characters
- **Complete Record Guarantee**: No CSV records are split across partitions
- **S3 Range Requests**: Uses 1MB buffer to find nearest line ending after each 50MB boundary
- **Transparent Adjustment**: Logs boundary adjustments for monitoring and debugging

### Scalability
- Processes files of any size (tested with 100MB+ files)
- Configurable partition size (default 50MB)
- Asynchronous Kafka publishing with callback confirmation
- Parallel processing via distributed worker nodes

## Technology Stack

- **Java**: 21
- **Spring Boot**: 3.4.0
- **Spring Framework**: 6.2.0 (latest stable)
- **Spring Batch**: 5.1.2 - Distributed batch processing
- **Spring Kafka**: 3.2.4 - Message broker integration
- **AWS SDK**: 2.x - S3 and SQS integration
- **Oracle Database**: 21c XE - Transaction storage and Spring Batch metadata
- **HikariCP**: 5.1.0 - Connection pooling
- **Logback**: Structured logging with file rotation
- **Lombok**: Code generation
- **Maven**: Build tool

## Prerequisites

- Java 21 or higher
- Maven 3.6+
- AWS Account with S3 and SQS access (configured for ap-south-1 region)
- Kafka cluster (Docker or local)
- Oracle Database 21c (Pluggable database recommended)
- AWS credentials configured (`~/.aws/credentials` or environment variables)

## Database Setup

### Oracle Database Configuration

1. **Create Spring Batch Metadata Tables**:
   ```sql
   -- Run: src/main/resources/spring-batch-schema-oracle.sql
   -- Creates 6 Spring Batch tables with sequences (idempotent)
   ```

2. **Create Application Tables**:
   ```sql
   -- Run: src/main/resources/schema.sql
   -- Creates TRANSACTION table (idempotent with exception handling)
   ```

3. **Database Connection**:
   - Use SERVICE NAME format: `jdbc:oracle:thin:@localhost:1521/XEPDB1`
   - NOT SID format: `jdbc:oracle:thin:@localhost:1521:XEPDB1`
   - Transaction isolation: `READ_COMMITTED` (Oracle compatible)

**Note**: Set `spring.batch.jdbc.initialize-schema: never` to prevent schema recreation on each startup. Use SQL scripts for manual schema management.

## Kafka Setup

### Docker Kafka Configuration

If running Kafka in Docker, ensure proper listener configuration:

```yaml
# docker-compose.yml
environment:
  - KAFKA_CFG_ADVERTISED_LISTENERS=PLAINTEXT://kafka:9092,PLAINTEXT_HOST://localhost:29092
```

Connect to `localhost:29092` from host applications (not 9092).

### Topic Creation

Create the Kafka topic before first use:

```bash
# Inside Kafka container
docker exec -it <kafka-container> kafka-topics --create \
  --topic amit-transaction-partitions \
  --bootstrap-server localhost:9092 \
  --partitions 3 \
  --replication-factor 1

# List topics to verify
docker exec -it <kafka-container> kafka-topics --list \
  --bootstrap-server localhost:9092
```

## Configuration

### Application Properties (`application.yml`)

```yaml
spring:
  application:
    name: transaction-batch-master
  main:
    allow-bean-definition-overriding: true  # Allows custom JobRepository
  
  datasource:
    url: jdbc:oracle:thin:@localhost:1521/XEPDB1  # SERVICE NAME format
    username: amit
    password: Welcome123
    driver-class-name: oracle.jdbc.OracleDriver
    hikari:
      transaction-isolation: TRANSACTION_READ_COMMITTED  # Oracle compatible
      auto-commit: false
  
  batch:
    jdbc:
      initialize-schema: never  # Manual schema management
      isolation-level-for-create: read_committed  # Oracle compatible
    job:
      enabled: false  # Jobs launched programmatically, not on startup
  
  kafka:
    bootstrap-servers: localhost:29092  # Docker host port
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer

aws:
  region: ap-south-1  # Mumbai region
  sqs:
    queue:
      name: amit-transaction-file-queue

s3:
  bucket:
    name: amit-transaction-files

kafka:
  topic:
    partition: amit-transaction-partitions

server:
  port: 8084

# Logging is configured via logback-spring.xml
logging:
  level:
    com.transaction.batch: DEBUG
    org.springframework.batch: INFO
```

### Environment Variables

Set the following environment variables or update `application.yml`:

- `AWS_REGION`: AWS region (default: us-east-1)
- `AWS_ACCESS_KEY_ID`: AWS access key
- `AWS_SECRET_ACCESS_KEY`: AWS secret key
- `DB_URL`: Oracle database URL
- `DB_USERNAME`: Database username
- `DB_PASSWORD`: Database password
- `KAFKA_BOOTSTRAP_SERVERS`: Kafka broker addresses
- `SQS_QUEUE_NAME`: SQS queue name for S3 events
- `S3_BUCKET_NAME`: S3 bucket name
- `KAFKA_PARTITION_TOPIC`: Kafka topic for partition metadata

## Project Structure

```
transaction-batch-master/
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/transaction/batch/master/
│   │   │       ├── MasterApplication.java          # Main application entry point
│   │   │       ├── config/
│   │   │       │   ├── AwsConfig.java              # AWS S3 and SQS configuration
│   │   │       │   ├── BatchConfig.java            # Spring Batch job configuration
│   │   │       │   ├── DataSourceConfig.java       # Database configuration
│   │   │       │   └── KafkaProducerConfig.java    # Kafka producer configuration
│   │   │       ├── listener/
│   │   │       │   └── S3EventListener.java        # SQS message listener
│   │   │       ├── model/
│   │   │       │   ├── PartitionData.java          # Partition metadata model
│   │   │       │   └── Transaction.java            # Transaction entity
│   │   │       ├── partitioner/
│   │   │       │   └── ByteRangePartitioner.java   # File partitioning logic
│   │   │       └── service/
│   │   │           └── FileProcessingService.java  # Async file processing
│   │   └── resources/
│   │       ├── application.yml                     # Application configuration
│   │       └── schema.sql                          # Database schema
│   └── test/
│       └── java/
│           └── com/transaction/batch/master/
├── pom.xml                                         # Maven dependencies
└── README.md                                       # This file
```

## Component Details

### 1. S3EventListener

Listens to an SQS queue for S3 ObjectCreated events. When a new file is uploaded to the configured S3 bucket, it triggers the file processing workflow.

**Key Features:**
- Parses S3 event notifications from SQS
- Supports SNS-wrapped messages (auto-unwraps SNS Message field)
- Extracts bucket name and file key from nested JSON
- Comprehensive null checking and validation
- Detailed debug logging for troubleshooting
- Delegates to FileProcessingService

**Supported Event Format:**
- Direct S3 event notifications
- SNS-wrapped S3 events (common with S3 → SNS → SQS configuration)

### 2. FileProcessingService

Asynchronously launches Spring Batch jobs for each file detected.

**Key Features:**
- Async execution using `@Async`
- Creates unique job parameters (bucket, key, timestamp)
- Input validation (bucket name and key must not be null/empty)
- Handles `JobInstanceAlreadyCompleteException` (duplicate job prevention)
- Handles `JobExecutionAlreadyRunningException` (concurrent execution prevention)
- Re-throws exceptions for SQS retry mechanism
- Launches Spring Batch jobs via JobLauncher

### 3. ByteRangePartitioner

Core partitioning logic that divides large S3 files into manageable byte ranges and publishes metadata to Kafka.

**Key Features:**
- Queries S3 for file metadata using HeadObject API
- Creates 50MB partitions (configurable via `PARTITION_SIZE` constant)
- **Ensures complete records** - Adjusts partition boundaries to nearest newline character
- Uses S3 range requests to find line endings (1MB buffer for detection)
- Prevents CSV records from being split across partitions
- Generates partition metadata with byte ranges
- Publishes PartitionData to Kafka asynchronously
- Callback-based Kafka send confirmation with logging
- Tracks failed Kafka sends and reports counts
- Handles files of any size (tested with 100MB+ files)
- Thread-safe partition numbering

**Partition Size:** 50MB (50 * 1024 * 1024 bytes)

**Record Boundary Detection:**
To ensure data integrity, the partitioner doesn't split records at exact 50MB boundaries. Instead, it:
1. Calculates the proposed boundary (50MB from start)
2. Reads a 1MB buffer from S3 starting at that position
3. Searches byte-by-byte for the next newline (`\n`) character
4. Adjusts the `endByte` to align with the complete record
5. Logs the adjustment for monitoring purposes

This guarantees that workers receive only complete, valid CSV records for processing.

**Example Output:**
```
File: transactions.txt (106,428,389 bytes)
Partition 0: bytes 0-52,428,799 (50MB)
Partition 1: bytes 52,428,800-104,857,599 (50MB)
Partition 2: bytes 104,857,600-106,428,388 (1.5MB)
```

### 4. BatchConfig (Tasklet Approach)

Spring Batch job configuration using a tasklet-based approach instead of traditional partitioning.

**Key Features:**
- Creates `transactionJob` with single `masterStep`
- Uses `@StepScope` to inject job parameters dynamically
- Tasklet executes ByteRangePartitioner.partition() method
- No worker steps needed (workers consume from Kafka independently)
- Job completes after partitions are sent to Kafka
- Transaction-managed execution with Oracle support

**Why Tasklet?**
This is a master-only application that publishes to Kafka. Workers are separate applications consuming Kafka messages. Traditional Spring Batch partitioning requires worker steps in the same application, which doesn't fit this distributed architecture.

### 5. PartitionData Model

Serializable data structure sent to Kafka containing:
- `bucketName`: S3 bucket name
- `key`: S3 object key
- `startByte`: Starting byte position
- `endByte`: Ending byte position
- `partitionNumber`: Sequential partition identifier
- `jobExecutionId`: Spring Batch job execution ID

### 5. Transaction Model

Entity representing a financial transaction:
- `customerId`: Customer identifier
- `transNum`: Transaction number
- `transactionId`: Unique transaction identifier (primary key)
- `transactionDate`: Date of transaction
- `withdrawalAmount`: Withdrawal amount
- `depositAmount`: Deposit amount
- `transactionType`: Type of transaction
- `balance`: Account balance

## AWS Infrastructure Setup

### 1. S3 Bucket Configuration

Create an S3 bucket and configure event notifications:

```bash
aws s3 mb s3://transaction-files --region us-east-1
```

### 2. SQS Queue Setup

Create an SQS queue for S3 events:

```bash
aws sqs create-queue --queue-name transaction-file-queue --region us-east-1
```

### 3. S3 Event Notification

Configure S3 to send ObjectCreated events to SQS:

```json
{
  "QueueConfigurations": [
    {
      "QueueArn": "arn:aws:sqs:us-east-1:ACCOUNT_ID:transaction-file-queue",
      "Events": ["s3:ObjectCreated:*"],
      "Filter": {
        "Key": {
          "FilterRules": [
            {
              "Name": "suffix",
              "Value": ".csv"
            }
          ]
        }
      }
    }
  ]
}
```

## AWS Setup

### 1. Create S3 Bucket

```bash
aws s3 mb s3://amit-transaction-files --region ap-south-1
```

### 2. Create SQS Queue

```bash
aws sqs create-queue \
  --queue-name amit-transaction-file-queue \
  --region ap-south-1
```

### 3. Configure S3 Event Notifications

Configure S3 bucket to send ObjectCreated events to SQS:

```bash
aws s3api put-bucket-notification-configuration \
  --bucket amit-transaction-files \
  --notification-configuration '{
    "QueueConfigurations": [{
      "QueueArn": "arn:aws:sqs:ap-south-1:YOUR_ACCOUNT_ID:amit-transaction-file-queue",
      "Events": ["s3:ObjectCreated:*"]
    }]
  }'
```

**Alternative**: Use SNS as an intermediary:
- S3 → SNS → SQS (application handles SNS wrapper automatically)

### 4. IAM Permissions

Ensure your AWS credentials have the following permissions:
- `s3:GetObject` - Read file content and byte ranges (for record boundary detection)
- `s3:HeadObject` - Get file metadata (size, etc.)
- `sqs:ReceiveMessage` - Poll SQS queue
- `sqs:DeleteMessage` - Remove processed messages
- `sqs:GetQueueAttributes` - Queue configuration
- `sqs:ChangeMessageVisibility` - Handle retries

**Note:** The `s3:GetObject` permission is required for both worker nodes (to read partition data) and the master application (to find record boundaries using byte-range requests).

**SQS Policy**: Add permission for S3 to send messages:
```json
{
  "Effect": "Allow",
  "Principal": {
    "Service": "s3.amazonaws.com"
  },
  "Action": "sqs:SendMessage",
  "Resource": "arn:aws:sqs:ap-south-1:YOUR_ACCOUNT_ID:amit-transaction-file-queue",
  "Condition": {
    "ArnLike": {
      "aws:SourceArn": "arn:aws:s3:::amit-transaction-files"
    }
  }
}
```

## Kafka Setup

### Create Kafka Topic

**For Docker Kafka:**
```bash
# Create topic inside container
docker exec -it <kafka-container-name> kafka-topics \
  --create \
  --topic amit-transaction-partitions \
  --bootstrap-server localhost:9092 \
  --partitions 3 \
  --replication-factor 1

# Verify topic creation
docker exec -it <kafka-container-name> kafka-topics \
  --list \
  --bootstrap-server localhost:9092
```

**For Local Kafka:**
```bash
kafka-topics.sh --create \
  --topic amit-transaction-partitions \
  --bootstrap-server localhost:9092 \
  --partitions 3 \
  --replication-factor 1
```

### Kafka Producer Configuration

The application uses these Kafka producer settings:
- **Idempotence**: Enabled (prevents duplicate messages)
- **Acks**: `all` (wait for all replicas)
- **Retries**: 3
- **Compression**: Snappy
- **Request Timeout**: 30 seconds
- **Delivery Timeout**: 120 seconds

## Logging Configuration

Logs are managed by Logback with the following structure:

### Log Files

| File | Content | Retention |
|------|---------|-----------|
| `logs/transaction-batch-master.log` | All application logs | 30 days |
| `logs/error.log` | Error logs with full stack traces | 60 days |
| `logs/kafka.log` | Kafka producer/consumer logs | 7 days |
| `logs/batch-jobs.log` | Spring Batch job execution logs | 30 days |
| `logs/archived/*.gz` | Compressed daily archives | Per policy |

### Log Rotation Policy

- **Daily rotation** at midnight
- **Size-based rotation**: 100MB for main logs, 50MB for errors
- **Compression**: Gzip for archived logs
- **Total size cap**: 10GB for main logs

### Log Levels

- `com.transaction.batch.master`: INFO
- `com.transaction.batch.master.partitioner`: DEBUG
- `com.transaction.batch.master.listener`: DEBUG
- `org.springframework.batch`: INFO
- `org.apache.kafka`: WARN
- `org.springframework.kafka`: INFO
- `software.amazon.awssdk`: WARN
- `oracle.jdbc`: WARN
  --replication-factor 1
```

### Topic Configuration

The partition metadata topic should have:
- **Partitions**: 10+ (for parallel consumption by workers)
- **Retention**: Based on processing time requirements
- **Serialization**: JSON (configured in Kafka producer)

## Building the Application

### Maven Build

```bash
# Clean and package
mvn clean package

# Skip tests (faster build)
mvn clean package -DskipTests

# Run tests
mvn test
```

### Build Output

The build produces:
- `target/transaction-batch-master-1.0.0.jar` - Executable JAR with embedded Tomcat
- `target/transaction-batch-master-1.0.0.jar.original` - Original JAR before Spring Boot repackaging

**Build Time**: ~6-12 seconds (depending on system)

## Running the Application

### Prerequisites Check

Before starting, ensure:
1. ✅ Oracle database is running and accessible
2. ✅ Kafka is running (Docker or local)
3. ✅ AWS credentials are configured
4. ✅ SQS queue exists and is accessible
5. ✅ S3 bucket exists with event notifications configured
6. ✅ Kafka topic `amit-transaction-partitions` exists

### Local Development

```bash
# Run with Maven
mvn spring-boot:run

# Run JAR directly (recommended)
java -jar target/transaction-batch-master-1.0.0.jar

# Run in background (Windows PowerShell)
Start-Process java -ArgumentList "-jar","target/transaction-batch-master-1.0.0.jar" -WindowStyle Hidden

# With custom profile
java -jar target/transaction-batch-master-1.0.0.jar --spring.profiles.active=prod
```

### Docker Deployment

Create a `Dockerfile`:

```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/transaction-batch-master-1.0.0.jar app.jar
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.jar"]
```

Build and run:

```bash
# Build image
docker build -t transaction-batch-master:1.0.0 .

# Run container
docker run -d \
  --name transaction-batch-master \
  -p 8084:8084 \
  -e SPRING_DATASOURCE_URL=jdbc:oracle:thin:@host.docker.internal:1521/XEPDB1 \
  -e SPRING_DATASOURCE_USERNAME=amit \
  -e SPRING_DATASOURCE_PASSWORD=Welcome123 \
  -e SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:29092 \
  -e AWS_REGION=ap-south-1 \
  -v $(pwd)/logs:/app/logs \
  transaction-batch-master:1.0.0

# View logs
docker logs -f transaction-batch-master
```

### Kubernetes Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: transaction-batch-master
spec:
  replicas: 1
  selector:
    matchLabels:
      app: transaction-batch-master
  template:
    metadata:
      labels:
        app: transaction-batch-master
    spec:
      containers:
      - name: app
        image: transaction-batch-master:1.0.0
        ports:
        - containerPort: 8084
        env:
        - name: SPRING_DATASOURCE_URL
          valueFrom:
            secretKeyRef:
              name: db-secrets
              key: url
        - name: SPRING_KAFKA_BOOTSTRAP_SERVERS
          value: "kafka-service:9092"
        volumeMounts:
        - name: logs
          mountPath: /app/logs
      volumes:
      - name: logs
        persistentVolumeClaim:
          claimName: batch-logs-pvc
```

## Workflow

### End-to-End Process

## Processing Workflow

### End-to-End Flow

1. **File Upload**: User uploads a transaction file to S3 bucket `amit-transaction-files`
2. **S3 Event**: S3 triggers an ObjectCreated event (CompleteMultipartUpload for large files)
3. **SQS Notification**: Event is sent to SQS queue `amit-transaction-file-queue`
4. **Event Reception**: S3EventListener receives and parses the SQS message (handles SNS wrapper if present)
5. **Job Launch**: FileProcessingService starts a Spring Batch job asynchronously
6. **Batch Execution**: BatchConfig tasklet is executed
7. **Partitioning**: ByteRangePartitioner divides the file into 50MB byte ranges
8. **S3 Metadata**: HeadObject API call retrieves file size
9. **Kafka Publishing**: Each partition metadata is sent to Kafka topic asynchronously
10. **Job Completion**: Job status set to COMPLETED in Oracle database
11. **Worker Processing**: Worker nodes (separate applications) consume partition data from Kafka
12. **Data Loading**: Workers read byte ranges from S3 and load transactions into Oracle database

### Partition Calculation Example

For a 106MB file (`transactions.txt`):
- File size: 106,428,389 bytes
- Partition size: 50MB (52,428,800 bytes)
- Total partitions: 3

**Note:** Actual partition boundaries are adjusted to align with complete records (newline characters), so the exact byte positions may vary slightly from the calculated 50MB boundaries.

| Partition | Start Byte | End Byte | Size | Notes |
|-----------|-----------|----------|------|-------|
| 0 | 0 | 52,428,799* | ~50MB | *Adjusted to nearest newline |
| 1 | 52,428,800 | 104,857,599* | ~50MB | *Adjusted to nearest newline |
| 2 | 104,857,600 | 106,428,388 | ~1.5MB | Last partition to end of file |

**Example Log Output:**
```
INFO - File size: 106428389 bytes for s3://amit-transaction-files/transactions.txt
DEBUG - Partition 0: proposed end=52428799, adjusted end=52428856 (adjusted by 57 bytes)
DEBUG - Adjusted partition boundary to align with record ending at byte 52428856
INFO - Created 3 partitions for file: transactions.txt
```

**Kafka Message Example:**
```json
{
  "bucketName": "amit-transaction-files",
  "key": "transactions.txt",
  "startByte": 0,
  "endByte": 52428799,
  "partitionNumber": 0,
  "jobExecutionId": "1762026767663"
}
```

## Monitoring and Logging

### Log Files Location

All logs are written to the `logs/` directory:

```
logs/
├── transaction-batch-master.log    # All application logs
├── error.log                        # Errors with stack traces
├── kafka.log                        # Kafka producer logs
├── batch-jobs.log                   # Spring Batch execution logs
└── archived/                        # Compressed daily archives
    ├── transaction-batch-master.2025-11-01.0.log.gz
    ├── error.2025-11-01.0.log.gz
    └── ...
```

### Key Log Messages

**Successful Processing:**
```
INFO - Received S3 event message
DEBUG - Raw message: {"Records":[...]}
INFO - Processing file: s3://amit-transaction-files/transactions.txt
INFO - Starting batch job for file: s3://amit-transaction-files/transactions.txt
INFO - Job: [SimpleJob: [name=transactionJob]] launched with parameters: [...]
INFO - Executing step: [masterStep]
INFO - Executing partition tasklet for file: s3://amit-transaction-files/transactions.txt
INFO - File size: 106428389 bytes for s3://amit-transaction-files/transactions.txt
INFO - Created 3 partitions for file: transactions.txt
INFO - Partitioning completed and messages sent to Kafka
INFO - Sent partition 0 to Kafka: bytes 0-52428799
INFO - Sent partition 1 to Kafka: bytes 52428800-104857599
INFO - Sent partition 2 to Kafka: bytes 104857600-106428388
INFO - Job: [SimpleJob: [name=transactionJob]] completed with status: [COMPLETED]
INFO - Batch job completed successfully for file: transactions.txt
```

**Error Scenarios:**
```
ERROR - File key cannot be null or empty
ERROR - Failed to send partition 0 to Kafka immediately
ERROR - Encountered an error executing step masterStep in job transactionJob
WARN - [Producer clientId=...] Error connecting to node kafka:9092
```

### Spring Batch Monitoring

Query batch metadata tables:

```sql
-- Recent job executions
SELECT JOB_EXECUTION_ID, JOB_INSTANCE_ID, STATUS, START_TIME, END_TIME, EXIT_MESSAGE
FROM BATCH_JOB_EXECUTION 
ORDER BY START_TIME DESC
FETCH FIRST 10 ROWS ONLY;

-- Failed jobs with details
SELECT e.JOB_EXECUTION_ID, i.JOB_NAME, e.STATUS, e.EXIT_MESSAGE, e.START_TIME
FROM BATCH_JOB_EXECUTION e
JOIN BATCH_JOB_INSTANCE i ON e.JOB_INSTANCE_ID = i.JOB_INSTANCE_ID
WHERE e.STATUS = 'FAILED'
ORDER BY e.START_TIME DESC;

-- Step execution details
SELECT STEP_EXECUTION_ID, STEP_NAME, STATUS, READ_COUNT, WRITE_COUNT, 
       COMMIT_COUNT, ROLLBACK_COUNT, START_TIME, END_TIME
FROM BATCH_STEP_EXECUTION 
WHERE JOB_EXECUTION_ID = :job_id;

-- Job parameters for a specific execution
SELECT JOB_EXECUTION_ID, PARAMETER_NAME, PARAMETER_TYPE, PARAMETER_VALUE
FROM BATCH_JOB_EXECUTION_PARAMS
WHERE JOB_EXECUTION_ID = :job_id;
```

### Health Checks

The application exposes Spring Boot Actuator endpoints (if enabled):

```bash
# Application health
curl http://localhost:8084/actuator/health

# Metrics
curl http://localhost:8084/actuator/metrics

# Spring Batch jobs
curl http://localhost:8084/actuator/batch
```

## Error Handling

### S3 Event Processing Errors

**Issue**: Failed to parse SQS message
- **Cause**: Invalid JSON, missing fields, SNS wrapper not handled
- **Solution**: Enhanced S3EventListener with null checking and SNS unwrapping
- **Retry**: SQS visibility timeout mechanism (message becomes visible again)
- **DLQ**: Configure Dead Letter Queue for messages that fail repeatedly

**Issue**: Bucket or key is null
- **Cause**: Malformed S3 event notification
- **Logging**: `ERROR - File key cannot be null or empty`
- **Action**: Check S3 event configuration, verify SQS policy

### Batch Job Errors

**Issue**: ORA-08177: can't serialize access for this transaction
- **Cause**: Oracle doesn't support SERIALIZABLE isolation (Spring Batch default)
- **Solution**: Custom JobRepository with READ_COMMITTED isolation
- **Configuration**: See `BatchTransactionConfig.java`

**Issue**: JobInstanceAlreadyCompleteException
- **Cause**: Attempting to run same job with identical parameters
- **Handling**: Caught and logged by FileProcessingService
- **Prevention**: Timestamp added to job parameters for uniqueness

**Issue**: A Step must be provided
- **Cause**: Using partitioner without worker step
- **Solution**: Changed to tasklet-based approach (current implementation)

### Kafka Publishing Errors

**Issue**: Topic not present in metadata after 60000 ms
- **Cause**: Kafka topic doesn't exist
- **Solution**: Create topic before starting application
- **Command**: See "Kafka Setup" section

**Issue**: Error connecting to node kafka:9092
- **Cause**: Using internal Docker hostname instead of host port
- **Solution**: Connect to `localhost:29092` (PLAINTEXT_HOST listener)
- **Configuration**: `spring.kafka.bootstrap-servers: localhost:29092`

**Issue**: TimeoutException sending to Kafka
- **Logging**: `ERROR - Failed to send partition X to Kafka immediately`
- **Impact**: Partition not published to workers
- **Monitoring**: Check `failedKafkaSends` count in logs
- **Action**: Verify Kafka connectivity, check producer configuration

### Database Connection Errors

**Issue**: ORA-12505: TNS:listener does not currently know of SID
- **Cause**: Using SID format (`:XEPDB1`) instead of SERVICE NAME (`/XEPDB1`)
- **Solution**: Update JDBC URL to use `/` instead of `:`
- **Correct**: `jdbc:oracle:thin:@localhost:1521/XEPDB1`

**Issue**: HikariPool - Connection is not available
- **Cause**: Database down, network issue, or connection pool exhausted
- **Action**: Check database status, verify credentials, tune HikariCP settings

## Performance Tuning

### Partition Size

Adjust `PARTITION_SIZE` in ByteRangePartitioner for optimal performance:

```java
// Current: 50MB
private static final long PARTITION_SIZE = 50 * 1024 * 1024;

// For faster processing with more workers: 25MB
private static final long PARTITION_SIZE = 25 * 1024 * 1024;

// For fewer, larger partitions: 100MB  
private static final long PARTITION_SIZE = 100 * 1024 * 1024;
```

**Considerations:**
- Smaller partitions = More parallel processing, more Kafka messages
- Larger partitions = Fewer messages, less overhead, longer worker processing time
- Network bandwidth between workers and S3
- Worker memory capacity
- **Important:** Actual partition sizes will vary slightly due to record boundary alignment

### Record Boundary Buffer Size

The partitioner uses a 1MB buffer to find line endings:

```java
private static final int BUFFER_SIZE = 1024 * 1024; // 1 MB buffer to find line ending
```

**Tuning Considerations:**
- **Larger buffer**: Can handle longer records, uses more memory per partition check
- **Smaller buffer**: More memory efficient, may fail if records exceed buffer size
- Current 1MB buffer accommodates most CSV record sizes
- If you have extremely long records (>1MB per line), increase this value
### HikariCP Connection Pool

Current configuration optimized for Oracle:

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20           # Max concurrent connections
      connection-timeout: 30000        # 30 seconds
      validation-timeout: 5000         # 5 seconds
      connection-test-query: SELECT 1 FROM DUAL
      transaction-isolation: TRANSACTION_READ_COMMITTED
      auto-commit: false
```

**Tuning Guidelines:**
- **maximum-pool-size**: Number of concurrent batch jobs × connections per job
- **connection-timeout**: Increase for slow networks
- **Decrease pool size** if seeing "too many connections" errors

### Kafka Producer Settings

Current optimized settings:

```yaml
spring:
  kafka:
    producer:
      acks: all                        # Wait for all replicas
      retries: 3                       # Retry failed sends
      batch-size: 16384               # Batch messages for efficiency
      compression-type: snappy        # Fast compression
      enable-idempotence: true        # Prevent duplicates
      request-timeout-ms: 30000       # 30 second timeout
      delivery-timeout-ms: 120000     # 2 minute total timeout
```

**Performance Tips:**
- **Increase batch-size** to reduce network overhead (trade-off: latency)
- **Add linger-ms** to wait for batches to fill (increases throughput)
- **Use snappy compression** for balance of speed and compression ratio

### Async Processing

FileProcessingService uses `@Async` for non-blocking execution:

```java
@Async
public void processFile(String bucketName, String key)
```

**Thread Pool Configuration:**
```yaml
spring:
  task:
    execution:
      pool:
        core-size: 10                # Concurrent file processing
        max-size: 20
        queue-capacity: 100
```

## Troubleshooting

### Common Issues

#### 1. SQS Messages Not Received

**Symptoms**: 
- No logs showing "Received S3 event message"
- SQS queue has messages but application doesn't process them

**Diagnostics**:
```bash
# Check SQS queue attributes
aws sqs get-queue-attributes \
  --queue-url <QUEUE_URL> \
  --attribute-names All \
  --region ap-south-1

# Check if messages are in queue
aws sqs receive-message \
  --queue-url <QUEUE_URL> \
  --region ap-south-1
```

**Solutions**:
- ✅ Verify SQS queue name matches `application.yml`: `amit-transaction-file-queue`
- ✅ Check AWS credentials have `sqs:ReceiveMessage`, `sqs:DeleteMessage` permissions
- ✅ Ensure S3 event notification is configured to send to this queue
- ✅ Verify SQS queue policy allows S3 to send messages
- ✅ Check region matches: `ap-south-1`
- ✅ Verify application is running and SQS listener started (look for "Container started" log)

#### 2. Kafka Connection Errors

**Symptoms**:
```
Error connecting to node kafka:9092
UnknownHostException: kafka
Topic not present in metadata after 60000 ms
```

**Solutions**:
- ✅ Verify Kafka bootstrap servers in `application.yml`: `localhost:29092` (not 9092)
- ✅ Ensure Kafka Docker container is running: `docker ps | grep kafka`
- ✅ Check Kafka advertised listeners configuration
- ✅ Verify topic exists: `docker exec <kafka> kafka-topics --list --bootstrap-server localhost:9092`
- ✅ Create topic if missing (see "Kafka Setup" section)
- ✅ Test connectivity: `telnet localhost 29092`

#### 3. Database Connection Failures

**Symptoms**:
```
ORA-12505: TNS:listener does not currently know of SID
HikariPool - Connection is not available
ORA-08177: can't serialize access for this transaction
```

**Solutions**:
- ✅ **SID Error**: Change JDBC URL from `:XEPDB1` to `/XEPDB1` (SERVICE NAME format)
- ✅ **Connection unavailable**: Verify Oracle is running, check credentials
- ✅ **ORA-08177**: Already fixed with READ_COMMITTED isolation in `BatchTransactionConfig`
- ✅ Test connection: `sqlplus amit/Welcome123@localhost:1521/XEPDB1`
- ✅ Verify database accessible: `tnsping XEPDB1`

#### 4. Job Already Completed Error

**Symptoms**:
```
JobInstanceAlreadyCompleteException: A job instance already exists
```

**Root Cause**: Spring Batch prevents re-running completed jobs with same parameters

**Solutions**:
- ✅ Already handled in `FileProcessingService` (catches exception, logs warning)
- ✅ Timestamp parameter ensures uniqueness for each upload
- ✅ To reprocess: Change file name or delete job instance from `BATCH_JOB_INSTANCE`

```sql
-- Remove job instance to allow reprocessing
DELETE FROM BATCH_JOB_EXECUTION_PARAMS WHERE JOB_EXECUTION_ID IN 
  (SELECT JOB_EXECUTION_ID FROM BATCH_JOB_EXECUTION WHERE JOB_INSTANCE_ID = :id);
DELETE FROM BATCH_STEP_EXECUTION_CONTEXT WHERE STEP_EXECUTION_ID IN
  (SELECT STEP_EXECUTION_ID FROM BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID IN
    (SELECT JOB_EXECUTION_ID FROM BATCH_JOB_EXECUTION WHERE JOB_INSTANCE_ID = :id));
DELETE FROM BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID IN
  (SELECT JOB_EXECUTION_ID FROM BATCH_JOB_EXECUTION WHERE JOB_INSTANCE_ID = :id);
DELETE FROM BATCH_JOB_EXECUTION_CONTEXT WHERE JOB_EXECUTION_ID IN
  (SELECT JOB_EXECUTION_ID FROM BATCH_JOB_EXECUTION WHERE JOB_INSTANCE_ID = :id);
DELETE FROM BATCH_JOB_EXECUTION WHERE JOB_INSTANCE_ID = :id;
DELETE FROM BATCH_JOB_INSTANCE WHERE JOB_INSTANCE_ID = :id;
```

#### 5. SNS-Wrapped Messages Not Parsed

**Symptoms**:
```
DEBUG - Parsed JSON root keys: ...
ERROR - No Records array found in S3 event
```

**Root Cause**: S3 → SNS → SQS configuration wraps event in SNS Message field

**Solution**: Already implemented in `S3EventListener.processRecord()`
- Checks for `Message` field (SNS wrapper)
- Unwraps and parses inner JSON
- Falls back to direct S3 event if no wrapper

#### 6. Partition Count Mismatch

**Symptoms**: File processed but wrong number of partitions created

**Diagnostics**:
```
INFO - File size: 106428389 bytes
INFO - Created 3 partitions for file: transactions.txt
```

**Verification**:
- File size: 106,428,389 bytes
- Partition size: 52,428,800 bytes (50MB)
- Expected partitions: ceil(106428389 / 52428800) = 3 ✅

**Check Kafka**:
```bash
# Count messages in topic
docker exec <kafka> kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic amit-transaction-partitions \
  --from-beginning \
  --timeout-ms 5000 | wc -l
```
- Check network connectivity to Kafka cluster
- Review Kafka broker logs

#### 7. Record Boundary Adjustment Warnings

**Symptoms**:
```
DEBUG - Adjusted partition boundary to align with record ending at byte 52428856
WARN - Could not find newline within 1MB buffer, using buffer end
```

**Explanation**: Normal operation - partitioner is aligning boundaries with complete records

**When to investigate**:
- **Frequent "Could not find newline" warnings**: May indicate records longer than 1MB buffer
  - **Solution**: Increase `BUFFER_SIZE` in `ByteRangePartitioner.java`
- **Large boundary adjustments** (>10KB): Could indicate unusual record structure
  - **Check**: Verify CSV format, look for embedded newlines in quoted fields
- **S3 GetObject errors**: Network issues reading byte ranges
  - **Check**: AWS credentials, S3 bucket permissions, network connectivity

**Normal behavior**:
- Small adjustments (10-200 bytes) are expected and indicate proper record alignment
- Debug logs help verify partitions align with record boundaries
- Each partition ends at a complete record, ensuring data integrity

#### 3. Database Connection Failures

**Symptoms**: Cannot create batch metadata tables

**Solutions**:
- Verify Oracle database is running
- Check JDBC URL, username, and password
- Ensure Oracle driver is in classpath
- Validate network access to database

#### 4. S3 Access Denied

**Symptoms**: Error in partitioning - Access Denied

**Solutions**:
- Verify AWS credentials are valid
- Check IAM permissions for S3 HeadObject and S3 GetObject (for range requests)
- Ensure bucket exists and is accessible
- Review S3 bucket policy

## Security Considerations

### AWS Credentials

- Use IAM roles when running on EC2/ECS
- Avoid hardcoding credentials
- Use AWS Secrets Manager or Parameter Store
- Rotate credentials regularly

### Database Credentials

- Store passwords in environment variables
- Use database credential rotation
- Implement least privilege access
- Enable database encryption

### Kafka Security

- Enable SASL authentication
- Use SSL/TLS for encryption
- Implement ACLs for topic access
- Secure bootstrap server endpoints

## Contributing

### Code Style

- Follow Java naming conventions
- Use Lombok annotations for boilerplate reduction
- Add meaningful logging at INFO and DEBUG levels
- Write unit tests for business logic

### Testing

Run unit tests:

```bash
mvn test
```

Run integration tests:

```bash
mvn verify
```

## License

[Specify your license here]

## Support

For issues or questions:
- Open an issue in the project repository
- Contact the development team
- Review Spring Batch documentation: https://docs.spring.io/spring-batch/
- AWS SDK documentation: https://docs.aws.amazon.com/sdk-for-java/

## Related Projects

- **Transaction Batch Worker**: Companion application that processes partition data
- **Transaction File Generator**: Tool for generating test transaction files
- **Transaction Dashboard**: Monitoring and reporting application

## Version History

### 1.0.0 (Current)
- Initial release
- S3 event-driven processing
- Byte-range partitioning
- Kafka integration
- Oracle database support
