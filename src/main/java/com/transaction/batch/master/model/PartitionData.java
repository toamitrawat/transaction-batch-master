package com.transaction.batch.master.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PartitionData implements Serializable {
    private String bucketName;
    private String key;
    private long startByte;
    private long endByte;
    private int partitionNumber;
    private String jobExecutionId;
}