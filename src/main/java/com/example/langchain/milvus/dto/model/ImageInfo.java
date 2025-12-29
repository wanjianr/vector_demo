package com.example.langchain.milvus.dto.model;

import lombok.Builder;
import lombok.Data;
import lombok.Setter;

import java.util.Map;

@Data
@Setter
@Builder
public class ImageInfo {
    private String originalName;
    private String storedName;
    private String filePath;
    private String fileType;
    private Integer width;
    private Integer height;
    private Long size;
    private String caption;
    private String altText;
    private Integer pageNumber;
    private Integer position;
    private Map<String, Object> metadata;
}