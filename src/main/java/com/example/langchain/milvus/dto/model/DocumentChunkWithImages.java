package com.example.langchain.milvus.dto.model;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class DocumentChunkWithImages {
    private String chunkId;
    private String documentId;
    private Integer chunkIndex;
    private Integer totalChunks;
    private String text;
    private Integer pageNumber;
    private String sectionTitle;
    private String fileType;
    private String fileName;
    private List<ImageInfo> images;
    private Map<String, Object> metadata;
    private LocalDateTime createTime = LocalDateTime.now();
}