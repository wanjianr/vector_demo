package com.example.langchain.milvus.controller;

import com.example.langchain.milvus.dto.request.DocumentImportRequest;
import com.example.langchain.milvus.dto.request.SearchRequest;
import com.example.langchain.milvus.dto.response.DocumentImportResult;
import com.example.langchain.milvus.dto.response.SearchResult;
import com.example.langchain.milvus.dto.response.ServiceStatus;
import com.example.langchain.milvus.service.MilvusService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

@RestController
@RequestMapping("/api/documents")
@Slf4j
@RequiredArgsConstructor
public class DocumentController {

    private final MilvusService milvusService;

    /**
     * 导入文档
     */
    @PostMapping("/import")
    public ResponseEntity<DocumentImportResult> importDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "collectionName", defaultValue = "default") String collectionName,
            @RequestParam(value = "extractImages", defaultValue = "true") Boolean extractImages) {

        try {
            DocumentImportRequest request = new DocumentImportRequest();
            request.setFile(file);
            request.setCollectionName(collectionName);
            request.setExtractImages(extractImages);

            DocumentImportResult result = milvusService.importDocument(file, request);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("导入文档失败", e);
            return ResponseEntity.internalServerError()
                    .body(DocumentImportResult.builder()
                            .success(false)
                            .error(e.getMessage())
                            .build());
        }
    }

    /**
     * 文本搜索
     */
    @GetMapping("/search")
    public ResponseEntity<List<SearchResult>> search(
            @RequestParam(value = "query", defaultValue = "支付登记查询操作流程") String query,
            @RequestParam(value = "collectionName", defaultValue = "opdoc1") String collectionName,
            @RequestParam(value = "topK", defaultValue = "10") Integer topK) {

        try {
            List<SearchResult> results = milvusService.semanticSearch(query, collectionName, topK);
            return ResponseEntity.ok(results);

        } catch (Exception e) {
            log.error("搜索失败", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 混合搜索
     */
    @PostMapping("/hybrid-search")
    public ResponseEntity<List<SearchResult>> hybridSearch(
            @Valid @RequestBody SearchRequest request) {

        try {
            List<SearchResult> results = milvusService.hybridSearch(request);
            return ResponseEntity.ok(results);

        } catch (Exception e) {
            log.error("混合搜索失败", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 创建集合
     */
    @PostMapping("/collections/{collectionName}")
    public ResponseEntity<String> createCollection(
            @PathVariable("collectionName") String collectionName) {

        try {
            Boolean success = milvusService.createCollection(collectionName);
            return success ?
                    ResponseEntity.ok("集合创建成功") :
                    ResponseEntity.badRequest().body("集合创建失败");

        } catch (Exception e) {
            log.error("创建集合失败", e);
            return ResponseEntity.internalServerError().body("创建集合失败: " + e.getMessage());
        }
    }

    /**
     * 删除集合
     */
    @DeleteMapping("/collections/{collectionName}")
    public ResponseEntity<String> dropCollection(
            @PathVariable("collectionName") String collectionName) {

        try {
            Boolean success = milvusService.dropCollection(collectionName);
            return success ?
                    ResponseEntity.ok("集合删除成功") :
                    ResponseEntity.badRequest().body("集合删除失败");

        } catch (Exception e) {
            log.error("删除集合失败", e);
            return ResponseEntity.internalServerError().body("删除集合失败: " + e.getMessage());
        }
    }

    /**
     * 服务状态
     */
//    @GetMapping("/status")
//    public ResponseEntity<ServiceStatus> getServiceStatus() {
//        try {
//            ServiceStatus status = milvusService.getServiceStatus();
//            return ResponseEntity.ok(status);
//
//        } catch (Exception e) {
//            log.error("获取服务状态失败", e);
//            return ResponseEntity.internalServerError()
//                    .body(ServiceStatus.builder()
//                            .healthy(false)
//                            .error(e.getMessage())
//                            .build());
//        }
//    }
}
