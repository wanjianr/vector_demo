package com.example.langchain.milvus.dto.response;

import com.example.langchain.milvus.component.DocumentParserWithStructure;
import com.example.langchain.milvus.dto.model.ImageInfo;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchResult {
    private String resultId;
    private String documentId;
    private String documentName;
    private String chunkId;
    private Integer chunkIndex;
    private String text;
    private Double similarityScore = 0.0;
    private Integer pageNumber = 1;
    private String sectionTitle;
    private Map<String, Object> metadata;
    private Boolean hasImages = false;
    private Integer imageCount = 0;
    private List<DocumentParserWithStructure.ImageInfo> images;
    private String fileType;
    private String filePath;
}
