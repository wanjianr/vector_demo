package com.example.langchain.milvus.service;

import com.example.langchain.milvus.dto.MilvusConfig;
import com.example.langchain.milvus.dto.model.TextEmbedding;
import com.example.langchain.milvus.dto.request.DocumentImportRequest;
import com.example.langchain.milvus.dto.request.SearchRequest;
import com.example.langchain.milvus.dto.response.DocumentImportResult;
import com.example.langchain.milvus.dto.response.SearchResult;
import com.example.langchain.milvus.dto.response.ServiceStatus;
import com.example.langchain.milvus.dto.model.DocumentChunkWithImages;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

public interface MilvusService {

    // 文档导入
    DocumentImportResult importDocument(MultipartFile file,
                                        DocumentImportRequest request) throws Exception;

    // 文本搜索
    List<SearchResult> semanticSearch(String query,
                                      String collectionName,
                                      int topK) throws Exception;

    // 混合搜索
    List<SearchResult> hybridSearch(SearchRequest request) throws Exception;

    // 图片搜索
    List<SearchResult> imageSearch(byte[] imageData,
                                   String collectionName,
                                   int topK) throws Exception;

    // 集合管理
    Boolean createCollection(String collectionName) throws Exception;
    Boolean dropCollection(String collectionName) throws Exception;
    Boolean hasCollection(String collectionName) throws Exception;

    // 带图片的插入
    MilvusInsertResult insertWithImages(
            List<DocumentChunkWithImages> chunks,
            List<TextEmbedding> embeddings,
            MilvusConfig config
    ) throws Exception;

    // 插入结果
    class MilvusInsertResult {
        public Boolean success = false;
        public Long insertedCount = 0L;
        public String collectionName;
        public String error;
        public Long startTime = System.currentTimeMillis();
        public Long endTime = System.currentTimeMillis();
        public Long elapsedTime = 0L;

        public void calculateElapsedTime() {
            this.elapsedTime = this.endTime - this.startTime;
        }
    }
}