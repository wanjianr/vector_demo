package com.example.langchain.milvus.dto.response;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class DocumentImportResult {
    private Boolean success = false;
    private String documentId;
    private String documentName;
    private String collectionName;
    private Integer chunkCount = 0;
    private Integer imageCount = 0;
    private Integer vectorCount = 0;
    private String error;
    private List<String> chunkIds;
    private List<Long> vectorIds;
    private LocalDateTime startTime = LocalDateTime.now();
    private LocalDateTime endTime = LocalDateTime.now();
    private Long durationMs = 0L;

    public void calculateDuration() {
        this.durationMs = java.time.Duration.between(startTime, endTime).toMillis();
    }
}