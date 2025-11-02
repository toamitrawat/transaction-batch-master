-- Transaction table for storing financial transactions
-- Run this script manually on first deployment
-- Safe to run multiple times due to conditional creation

-- Drop existing objects if recreating (commented out for safety)
-- DROP TABLE TRANSACTION CASCADE CONSTRAINTS;

-- Create transaction table only if it doesn't exist
BEGIN
    EXECUTE IMMEDIATE 'CREATE TABLE TRANSACTION (
        CUSTOMER_ID VARCHAR2(50),
        TRANS_NUM VARCHAR2(50),
        TRANSACTION_ID VARCHAR2(50) PRIMARY KEY,
        TRANSACTION_DATE DATE,
        WITHDRAWAL_AMOUNT NUMBER(19,2),
        DEPOSIT_AMOUNT NUMBER(19,2),
        TRANSACTION_TYPE VARCHAR2(20),
        BALANCE NUMBER(19,2)
    )';
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE = -955 THEN
            NULL; -- Table already exists, ignore error
        ELSE
            RAISE; -- Re-raise other errors
        END IF;
END;
/

-- Create indexes only if they don't exist
BEGIN
    EXECUTE IMMEDIATE 'CREATE INDEX IDX_CUSTOMER_ID ON TRANSACTION(CUSTOMER_ID)';
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE = -955 THEN
            NULL; -- Index already exists, ignore error
        ELSE
            RAISE;
        END IF;
END;
/

BEGIN
    EXECUTE IMMEDIATE 'CREATE INDEX IDX_TRANSACTION_DATE ON TRANSACTION(TRANSACTION_DATE)';
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE = -955 THEN
            NULL; -- Index already exists, ignore error
        ELSE
            RAISE;
        END IF;
END;
/