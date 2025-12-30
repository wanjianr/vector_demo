package com.example.langchain.milvus.controller;

import com.example.langchain.milvus.dto.DocumentImportRequest;
import com.example.langchain.milvus.dto.DocumentImportResult;
import com.example.langchain.milvus.service.MilvusServiceImplV2;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/documents")
@Slf4j
@RequiredArgsConstructor
public class DocumentController {

    private final MilvusServiceImplV2 milvusService;

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
}
