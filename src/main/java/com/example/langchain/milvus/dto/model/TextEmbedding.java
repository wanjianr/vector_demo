package com.example.langchain.milvus.dto.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 文本嵌入向量 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TextEmbedding {

    @JsonProperty("id")
    private String id;

    @JsonProperty("text")
    private String text;

    @JsonProperty("vector")
    private List<Float> vector;

    @JsonProperty("dimension")
    private Integer dimension;

    @JsonProperty("model")
    private String model;

    @JsonProperty("timestamp")
    private Long timestamp;

    @JsonProperty("metadata")
    private Object metadata;
}