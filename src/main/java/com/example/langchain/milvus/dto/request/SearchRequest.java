package com.example.langchain.milvus.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.web.multipart.MultipartFile;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchRequest {
    @NotBlank
    private String queryText;
    private MultipartFile queryImage;
    private String collectionName = "default";
    private Integer topK = 10;
    private Float similarityThreshold = 0.3f;
    private Boolean withImages = false;
    private String tenantId = "default";
}