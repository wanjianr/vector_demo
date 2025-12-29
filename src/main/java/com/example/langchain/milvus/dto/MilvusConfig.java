package com.example.langchain.milvus.dto;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import lombok.Builder;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Data
@Builder
public class MilvusConfig {

    private String collection;

    private Integer dimension;

    private String indexType;

    private String metricType;
}