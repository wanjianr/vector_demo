package com.example.langchain.milvus.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;
import java.util.Map;

@Data
public class DocumentImportRequest {
    @NotNull
    private MultipartFile file;
    private String collectionName = "default";
    private Boolean extractImages = true;
    private String chunkStrategy = "semantic";
    private Integer chunkSize = 5000;
    private Integer overlapSize = 200;
    private Map<String, Object> metadata;
    private String tenantId = "default";
}