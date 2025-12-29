package com.example.langchain.milvus.enums;

public enum ChunkStrategy {
    FIXED_SIZE,     // 固定大小分块
    SEMANTIC,       // 语义分块
    SMART           // 智能分块（保持结构）
}
