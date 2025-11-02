package com.transaction.batch.master.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Transaction {
    private String customerId;
    private String transNum;
    private String transactionId;
    private LocalDate transactionDate;
    private BigDecimal withdrawalAmount;
    private BigDecimal depositAmount;
    private String transactionType;
    private BigDecimal balance;
}