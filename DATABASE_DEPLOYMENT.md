# Database Deployment Guide

## Overview
This guide ensures safe database schema management across multiple deployments without table recreation or data loss.

## Configuration Changes

### application.yml
```yaml
spring:
  batch:
    jdbc:
      initialize-schema: never  # Tables managed manually
```

**Why `never`?**
- Prevents automatic table creation on every startup
- Avoids "table already exists" errors
- Gives you full control over schema changes
- Prevents accidental data loss

## Initial Deployment (First Time)

### Step 1: Create Spring Batch Metadata Tables
Run the Spring Batch schema script **once**:

```bash
sqlplus username/password@database @src/main/resources/spring-batch-schema-oracle.sql
```

This creates:
- `BATCH_JOB_INSTANCE`
- `BATCH_JOB_EXECUTION`
- `BATCH_JOB_EXECUTION_PARAMS`
- `BATCH_STEP_EXECUTION`
- `BATCH_STEP_EXECUTION_CONTEXT`
- `BATCH_JOB_EXECUTION_CONTEXT`
- Related sequences

### Step 2: Create Application Tables
Run the application schema script **once**:

```bash
sqlplus username/password@database @src/main/resources/schema.sql
```

This creates:
- `TRANSACTION` table
- Indexes: `IDX_CUSTOMER_ID`, `IDX_TRANSACTION_DATE`

### Step 3: Deploy Application
```bash
java -jar target/transaction-batch-master-1.0.0.jar
```

## Subsequent Deployments (Bug Fixes, Updates)

### Safe Deployment Process

1. **Stop the application**
   ```bash
   # Kill the running process or stop the container
   pkill -f transaction-batch-master
   ```

2. **Build the new version**
   ```bash
   mvn clean package
   ```

3. **Deploy the new JAR**
   ```bash
   java -jar target/transaction-batch-master-1.0.0.jar
   ```

4. **Verify startup**
   - Check logs for successful startup
   - No "table already exists" errors should appear
   - Application should connect to existing tables

### What Happens on Restart?

✅ **Spring Batch tables**: Reused (no recreation)
✅ **TRANSACTION table**: Reused (no recreation)
✅ **Existing data**: Preserved
✅ **Job history**: Preserved in metadata tables

## Schema Changes (Adding New Fields/Tables)

If you need to modify the schema:

### Option 1: Manual Migration Scripts

Create a new migration script:
```sql
-- migration-v1.1.sql
BEGIN
    EXECUTE IMMEDIATE 'ALTER TABLE TRANSACTION ADD (
        ACCOUNT_TYPE VARCHAR2(20),
        BRANCH_CODE VARCHAR2(10)
    )';
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE = -1430 THEN NULL; -- Column already exists
        ELSE RAISE;
        END IF;
END;
/
```

Run it:
```bash
sqlplus username/password@database @migration-v1.1.sql
```

### Option 2: Use Flyway or Liquibase

Add to `pom.xml`:
```xml
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
```

Configure in `application.yml`:
```yaml
spring:
  flyway:
    enabled: true
    baseline-on-migrate: true
    locations: classpath:db/migration
```

Create versioned migrations:
```
src/main/resources/db/migration/
  V1__initial_schema.sql
  V2__add_account_type.sql
  V3__add_branch_code.sql
```

## Verification

### Check if tables exist:
```sql
-- Check application tables
SELECT table_name FROM user_tables WHERE table_name = 'TRANSACTION';

-- Check Spring Batch tables
SELECT table_name FROM user_tables WHERE table_name LIKE 'BATCH%';

-- Check table row counts
SELECT 'TRANSACTION' AS table_name, COUNT(*) AS row_count FROM TRANSACTION
UNION ALL
SELECT 'BATCH_JOB_INSTANCE', COUNT(*) FROM BATCH_JOB_INSTANCE
UNION ALL
SELECT 'BATCH_JOB_EXECUTION', COUNT(*) FROM BATCH_JOB_EXECUTION;
```

### Check recent job executions:
```sql
SELECT 
    ji.JOB_NAME,
    je.JOB_EXECUTION_ID,
    je.STATUS,
    je.START_TIME,
    je.END_TIME,
    je.EXIT_CODE
FROM BATCH_JOB_EXECUTION je
JOIN BATCH_JOB_INSTANCE ji ON je.JOB_INSTANCE_ID = ji.JOB_INSTANCE_ID
ORDER BY je.CREATE_TIME DESC
FETCH FIRST 10 ROWS ONLY;
```

## Common Scenarios

### Scenario 1: Fresh Environment (Dev/Test)
```bash
# Run schema scripts
sqlplus user/pass@db @spring-batch-schema-oracle.sql
sqlplus user/pass@db @schema.sql

# Deploy application
java -jar app.jar
```

### Scenario 2: Production Update (No Schema Changes)
```bash
# Just redeploy
mvn clean package
java -jar target/transaction-batch-master-1.0.0.jar
```

### Scenario 3: Production Update (With Schema Changes)
```bash
# 1. Create and test migration script
# 2. Run migration in production
sqlplus user/pass@db @migration-v1.1.sql

# 3. Deploy new application version
java -jar target/transaction-batch-master-1.0.0.jar
```

### Scenario 4: Complete Reset (Development Only!)
```sql
-- WARNING: This deletes all data!
DROP TABLE BATCH_JOB_EXECUTION_CONTEXT CASCADE CONSTRAINTS;
DROP TABLE BATCH_STEP_EXECUTION_CONTEXT CASCADE CONSTRAINTS;
DROP TABLE BATCH_STEP_EXECUTION CASCADE CONSTRAINTS;
DROP TABLE BATCH_JOB_EXECUTION_PARAMS CASCADE CONSTRAINTS;
DROP TABLE BATCH_JOB_EXECUTION CASCADE CONSTRAINTS;
DROP TABLE BATCH_JOB_INSTANCE CASCADE CONSTRAINTS;
DROP TABLE TRANSACTION CASCADE CONSTRAINTS;

DROP SEQUENCE BATCH_STEP_EXECUTION_SEQ;
DROP SEQUENCE BATCH_JOB_EXECUTION_SEQ;
DROP SEQUENCE BATCH_JOB_SEQ;

-- Then run schema scripts again
@spring-batch-schema-oracle.sql
@schema.sql
```

## Troubleshooting

### Error: "Table already exists"
**Cause**: `initialize-schema: always` in application.yml
**Solution**: Change to `initialize-schema: never`

### Error: "Table or view does not exist"
**Cause**: Schema scripts not run
**Solution**: Run `spring-batch-schema-oracle.sql` and `schema.sql`

### Error: "Sequence does not exist"
**Cause**: Spring Batch sequences not created
**Solution**: Run `spring-batch-schema-oracle.sql`

### Data Loss After Restart
**Cause**: Schema recreation scripts without IF NOT EXISTS
**Solution**: Use the provided idempotent scripts (already fixed)

## Best Practices

1. ✅ **Never use `initialize-schema: always` in production**
2. ✅ **Always run schema scripts manually for first deployment**
3. ✅ **Use migration tools (Flyway/Liquibase) for schema evolution**
4. ✅ **Test schema changes in dev/test first**
5. ✅ **Backup database before schema changes**
6. ✅ **Version control all schema scripts**
7. ✅ **Document schema changes in migration files**

## Summary

| Configuration | First Deploy | Subsequent Deploys |
|--------------|--------------|-------------------|
| `initialize-schema: always` | ✅ Creates tables | ❌ Fails with "table exists" |
| `initialize-schema: never` | ❌ Need manual setup | ✅ Safe, reuses tables |

**Recommended**: `initialize-schema: never` + manual schema management
