package com.example.langchain.milvus.service.impl;

import com.example.langchain.milvus.component.DocumentParserWithStructure;
import com.example.langchain.milvus.component.ImageExtractorWithPosition;
import com.example.langchain.milvus.dto.MilvusConfig;
import com.example.langchain.milvus.dto.model.TextEmbedding;
import com.example.langchain.milvus.service.MilvusService;
import com.example.langchain.milvus.dto.request.DocumentImportRequest;
import com.example.langchain.milvus.dto.request.SearchRequest;
import com.example.langchain.milvus.dto.response.DocumentImportResult;
import com.example.langchain.milvus.dto.response.SearchResult;
import com.example.langchain.milvus.dto.response.ServiceStatus;
import com.example.langchain.milvus.dto.model.DocumentChunkWithImages;
import com.example.langchain.milvus.dto.model.ImageInfo;
import com.example.langchain.milvus.utils.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.gson.JsonObject;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.*;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.FieldDataWrapper;
import io.milvus.response.MutationResultWrapper;
import io.milvus.response.QueryResultsWrapper;
import io.milvus.response.SearchResultsWrapper;
import io.milvus.grpc.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class MilvusServiceImpl implements MilvusService {

    private final MilvusServiceClient milvusClient;
    private final DocumentParser documentParser;
    private final DocumentParserWithStructure documentParserWithStructure;
    private final ImageExtractor imageExtractor;
    private final ImageExtractorWithPosition imageExtractorWithPosition;
    private final DocumentChunker documentChunker;
    private final ManualDocumentChunker manualDocumentChunker;

    @Autowired
    private EmbeddingModel embeddingModel;  // 已有的嵌入模型

    @Value("${app.milvus.vector-dimension:1024}")
    private Integer vectorDimension;

    @Value("${app.milvus.index-type:HNSW}")
    private String indexType;

    @Value("${app.milvus.metric-type:IP}")
    private String metricType;

    @Value("${app.milvus.default-collection:document_collection}")
    private String defaultCollection;

    @Value("${app.document.image-output-dir:uploads/images/}")
    private String imageOutputDir;

    // 字段常量
    private static final String FIELD_ID = "id";
    private static final String FIELD_VECTOR = "vector";
    private static final String FIELD_TEXT = "text";
    private static final String FIELD_DOCUMENT_ID = "document_id";
    private static final String FIELD_CHUNK_ID = "chunk_id";
    private static final String FIELD_IMAGES = "images";
    private static final String FIELD_METADATA = "metadata";
    private static final String FIELD_CREATE_TIME = "create_time";

    @PostConstruct
    public void init() throws Exception {
        // 初始化默认集合
        if (!hasCollection(defaultCollection)) {
            createCollection(defaultCollection);
        }
        // 创建图片输出目录
        new File(imageOutputDir).mkdirs();
    }

    @Override
    public DocumentImportResult importDocument(MultipartFile file,
                                               DocumentImportRequest request) throws Exception {

        DocumentImportResult result = DocumentImportResult.builder()
                .success(false)
                .documentName(file.getOriginalFilename())
                .collectionName(request.getCollectionName())
                .startTime(LocalDateTime.now())
                .build();

        try {
            String documentId = generateDocumentId(file);
            result.setDocumentId(documentId);

            // 1. 确保集合存在
            String collectionName = request.getCollectionName();
            ensureCollectionExists(collectionName);

            // 2. 解析文档
//            String text = documentParser.parseDocument(file);
            DocumentParserWithStructure.DocumentContent docContent = documentParserWithStructure.parseDocumentWithStructure(file);

            // 3. 提取图片
            List<DocumentParserWithStructure.ImageInfo> images = new ArrayList<>();
            if (Boolean.TRUE.equals(request.getExtractImages())) {
//                images = imageExtractor.extractImages(file, imageOutputDir);
                images = imageExtractorWithPosition.extractImages(file, imageOutputDir);
            }

            // 4. 文档分块
            Map<Integer, List<DocumentParserWithStructure.ImagePosition>> imagePositions = associateImagesWithDocument(
                    docContent.getStructure(), images, docContent.getParagraphs());
//            List<String> textChunks = chunkDocumentForManual(
//                    text, request);

            // 5. 关联图片到分块
//            List<DocumentChunkWithImages> chunks = associateImagesWithChunks(
//                    textChunks, images, file.getOriginalFilename(), documentId);
//

            // 6. 智能分块（保持文档结构）
            List<DocumentParserWithStructure.DocumentChunk> chunks = chunkDocumentIntelligently(
                    docContent, imagePositions, request.getChunkSize());
//            // 6. 生成向量嵌入
//            List<TextEmbedding> embeddings = generateEmbeddings(chunks);

            List<DocumentParserWithStructure.TextEmbedding> embeddings = generateEmbeddingsForChunks(chunks);
            // 7. 准备插入配置
            MilvusConfig config = MilvusConfig.builder()
                    .collection(collectionName)
                    .dimension(vectorDimension)
                    .indexType(indexType)
                    .metricType(metricType)
                    .build();

            // 8. 插入到Milvus
//            MilvusInsertResult insertResult = insertWithImages(chunks, embeddings, config);

            // 8. 准备Milvus数据
            List<InsertParam.Field> fields = prepareMilvusInsertFields(
                    chunks, embeddings, documentId, file.getOriginalFilename());

            // 逐个字段检查
            for (InsertParam.Field field : fields) {
                System.out.println("\n--- 检查字段: " + field.getName() + " ---");
                List<?> values = field.getValues();

                for (int i = 0; i < values.size(); i++) {
                    Object value = values.get(i);

                    if (value == null) {
                        System.err.println("❌ 索引 " + i + ": NULL");
                        continue;
                    }

                    String displayValue;
                    if (value instanceof String) {
                        String str = (String) value;
                        if (str.isEmpty()) {
                            System.out.println("⚠️ 索引 " + i + ": 空字符串");
                        } else if ("images".equals(field.getName()) ||
                                "metadata".equals(field.getName()) ||
                                FIELD_METADATA.equals(field.getName())) {
                            // 检查JSON字段
                            try {
                                new ObjectMapper().readTree(str);
                                System.out.println("✅ 索引 " + i + ": 有效JSON, 长度: " + str.length());
                            } catch (Exception e) {
                                System.err.println("❌ 索引 " + i + ": 无效JSON - " + e.getMessage());
                                System.err.println("   内容: " + (str.length() > 100 ? str.substring(0, 100) + "..." : str));
                            }
                        }
                    } else if (value instanceof List) {
                        System.out.println("📊 索引 " + i + ": List, 大小: " + ((List<?>) value).size());
                    } else {
                        System.out.println("📄 索引 " + i + ": " + value.getClass().getSimpleName() + " = " + value);
                    }
                }
            }

            // 修复所有字段
            List<InsertParam.Field> fixedFields = validateAndFixAllFields(fields);

            // 9. 插入到Milvus
            R<MutationResult> insertResult = milvusClient.insert(
                    InsertParam.newBuilder()
                            .withCollectionName(collectionName)
                            .withFields(fixedFields)
                            .build()
            );

            if (insertResult.getStatus() != R.Status.Success.getCode()) {
                throw new Exception("插入Milvus失败: " + insertResult.getMessage());
            }
//            if (!insertResult.success) {
//                throw new Exception("插入到Milvus失败: " + insertResult.error);
//            }

            // 9. 构建结果
            result.setSuccess(true);
            result.setChunkCount(chunks.size());
            result.setImageCount(images.size());
            result.setVectorCount(embeddings.size());

//            if (insertResult.insertedCount != null) {
//                result.setVectorIds(Collections.singletonList(insertResult.insertedCount));
//            }

        } catch (Exception e) {
            log.error("文档导入失败", e);
            result.setError(e.getMessage());
            throw e;
        } finally {
            result.setEndTime(LocalDateTime.now());
            result.calculateDuration();
        }

        return result;
    }

    /**
     * 修复所有字段
     */
    private List<InsertParam.Field> validateAndFixAllFields(List<InsertParam.Field> fields) {
        List<InsertParam.Field> fixedFields = new ArrayList<>();

        for (InsertParam.Field field : fields) {
            String fieldName = field.getName();
            List<?> originalValues = field.getValues();
            List<Object> fixedValues = new ArrayList<>();

            for (Object value : originalValues) {
                Object fixedValue = fixFieldValue(fieldName, value);
                fixedValues.add(fixedValue);
            }

            fixedFields.add(new InsertParam.Field(fieldName, fixedValues));
        }

        return fixedFields;
    }

    /**
     * 修复单个字段值
     */
    private Object fixFieldValue(String fieldName, Object value) {
        if (value == null) {
            return getDefaultForField(fieldName);
        }

        // 字符串字段处理
        if (value instanceof String) {
            String str = (String) value;

            // 空字符串处理
            if (str.isEmpty()) {
                return getDefaultForField(fieldName);
            }

            // JSON字段特殊处理
            if (isJsonField(fieldName)) {
                return fixJsonStringForMilvus(str);
            }

            return str;
        }

        // List字段处理
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            if (list.contains(null)) {
                // 清理list中的null
                List<Object> cleaned = new ArrayList<>();
                for (Object item : list) {
                    if (item != null) {
                        cleaned.add(item);
                    }
                }
                return cleaned;
            }
            return value;
        }

        return value;
    }

    /**
     * 修复JSON字符串
     */
    private String fixJsonStringForMilvus(String jsonStr) {
        if (jsonStr == null) {
            return "{}";
        }

        jsonStr = jsonStr.trim();

        // 快速处理常见问题
        if (jsonStr.isEmpty() ||
                "[]".equals(jsonStr) ||
                "null".equalsIgnoreCase(jsonStr) ||
                "undefined".equalsIgnoreCase(jsonStr)) {
            return "{}";
        }

        // 确保是有效JSON
        try {
            ObjectMapper mapper = new ObjectMapper();

            // 尝试解析
            JsonNode node = mapper.readTree(jsonStr);

            // 如果是数组，转换为对象
            if (node.isArray()) {
                ArrayNode array = (ArrayNode) node;
                if (array.isEmpty()) {
                    return "{}";
                }

                // 数组转为对象
                ObjectNode obj = mapper.createObjectNode();
                obj.set("data", array);
                return mapper.writeValueAsString(obj);
            }

            return jsonStr;
        } catch (Exception e) {
            // 不是有效JSON，转为空对象
            return "{}";
        }
    }

    /**
     * 获取字段默认值
     */
    private Object getDefaultForField(String fieldName) {
        if (isJsonField(fieldName)) {
            return "{}";
        } else if ("text".equals(fieldName) ||
                "document_id".equals(fieldName) ||
                "chunk_id".equals(fieldName)) {
            return "";
        } else if (FIELD_CREATE_TIME.equals(fieldName)) {
            return System.currentTimeMillis();
        } else if ("vector".equals(fieldName)) {
            return new ArrayList<Float>();
        }
        return "";
    }

    private boolean isJsonField(String fieldName) {
        return "images".equals(fieldName) ||
                "metadata".equals(fieldName) ||
                FIELD_METADATA.equals(fieldName);
    }

    // 6. 准备Milvus插入数据
    private List<InsertParam.Field> prepareMilvusInsertFields(
            List<DocumentParserWithStructure.DocumentChunk> chunks,
            List<DocumentParserWithStructure.TextEmbedding> embeddings,
            String documentId,
            String fileName) {

        List<InsertParam.Field> fields = new ArrayList<>();

        // 向量字段
        List<List<Float>> vectors = embeddings.stream()
                .map(DocumentParserWithStructure.TextEmbedding::getVector)
                .collect(Collectors.toList());
        fields.add(new InsertParam.Field("vector", vectors));

        // 文本字段
        List<String> texts = chunks.stream()
                .map(DocumentParserWithStructure.DocumentChunk::getText)
                .collect(Collectors.toList());
        fields.add(new InsertParam.Field("text", texts));

        // 元数据字段
        List<String> metadataList = chunks.stream()
                .map(chunk -> convertMetadataToString(chunk, documentId, fileName))
                .collect(Collectors.toList());
        fields.add(new InsertParam.Field("metadata", metadataList));

        // 图片信息字段
//        List<String> imageInfoList = chunks.stream()
//                .map(this::convertImagesToString)
//                .map(this::fixJsonForMilvus)
//                .collect(Collectors.toList());
//        fields.add(new InsertParam.Field("images", imageInfoList));

        // 4. images字段 - 确保无null
        List<String> imageInfoList = new ArrayList<>();
        for (DocumentParserWithStructure.DocumentChunk chunk : chunks) {
//            String imageJson = convertImagesToStringSafe(chunk);
//            imageInfoList.add(imageJson);
            String imageJson = "{\"absolute_directory_path\":\"C:\\\\Users\\\\zxf28\\\\AppData\\\\Local\\\\Temp\\\\milvus_imports\",\"index\":\"1\",\"file_name\":\"医保基金财务管理系统-用户操作手册.docx\"}";
            imageInfoList.add(imageJson);
        }
//        fields.add(new InsertParam.Field("images", imageInfoList));
        fields.add(new InsertParam.Field("images", imageInfoList));

        // 文档ID字段
        List<String> docIds = Collections.nCopies(chunks.size(), documentId);
        fields.add(new InsertParam.Field("document_id", docIds));

        // 分块ID字段
        List<Integer> chunkIds = chunks.stream()
                .map(DocumentParserWithStructure.DocumentChunk::getChunkId)
                .collect(Collectors.toList());
        List<String> chunIdStr = new ArrayList<>();
        for (Integer id :
                chunkIds) {
            chunIdStr.add(id.toString());
        }
        fields.add(new InsertParam.Field("chunk_id", chunIdStr));


        // 元数据字段
        List<String> metadataJson = chunks.stream()
                .map(chunk -> JsonUtils.toJson(chunk.getMetadata()))
                .collect(Collectors.toList());
        fields.add(new InsertParam.Field(FIELD_METADATA, metadataJson));

        // 创建时间字段
        List<Long> createTimes = chunks.stream()
                .map(chunk -> System.currentTimeMillis())
                .collect(Collectors.toList());
        fields.add(new InsertParam.Field(FIELD_CREATE_TIME, createTimes));

        return fields;
    }

    /**
     * 安全的images转换
     */
    private String convertImagesToStringSafe(DocumentParserWithStructure.DocumentChunk chunk) {
        if (chunk == null || chunk.getImages() == null || chunk.getImages().isEmpty()) {
            return "{}";  // 直接返回空对象
        }

        List<Map<String, Object>> imageInfoList = new ArrayList<>();

        for (DocumentParserWithStructure.ImageInfo image : chunk.getImages()) {
            if (image == null) continue;

            Map<String, Object> imageInfo = new HashMap<>();

            // 确保所有字符串字段有值
            imageInfo.put("file_name",
                    image.getFileName() != null ? image.getFileName() : "");
            imageInfo.put("file_path",
                    image.getFilePath() != null ? image.getFilePath() : "");
            imageInfo.put("format",
                    image.getFormat() != null ? image.getFormat() : "");

            // 暂时注释掉base64
            // if (image.getData() != null && image.getData().length > 0) {
            //     imageInfo.put("data", Base64.getEncoder().encodeToString(image.getData()));
            // }

            if (image.getPosition() != null) {
                Map<String, Object> position = new HashMap<>();
                position.put("paragraph_index", image.getPosition().getParagraphIndex());
                position.put("char_position", image.getPosition().getCharPosition());

                String paragraphText = image.getPosition().getParagraphText();
                position.put("paragraph_text",
                        paragraphText != null ? paragraphText : "");

                imageInfo.put("position", position);
            }

            imageInfoList.add(imageInfo);
        }

        if (imageInfoList.isEmpty()) {
            return "{}";
        }

        try {
            return JsonUtils.toJson(imageInfoList);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String convertMetadataToString(DocumentParserWithStructure.DocumentChunk chunk, String documentId, String fileName) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("document_id", documentId);
        metadata.put("document_name", fileName);
        metadata.put("chunk_id", chunk.getChunkId());
        metadata.put("start_para", chunk.getStartParagraphIndex());
        metadata.put("end_para", chunk.getEndParagraphIndex());
        metadata.put("word_count", chunk.getWordCount());
        metadata.put("char_count", chunk.getCharCount());
        metadata.put("has_images", !chunk.getImages().isEmpty());

        if (chunk.getMetadata() != null) {
            metadata.putAll(chunk.getMetadata());
        }

        try {
            ObjectMapper objectMapper = new ObjectMapper();
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private String convertImagesToString(DocumentParserWithStructure.DocumentChunk chunk) {
        List<Map<String, Object>> imageInfoList = new ArrayList<>();

        for (DocumentParserWithStructure.ImageInfo image : chunk.getImages()) {
            Map<String, Object> imageInfo = new HashMap<>();
            imageInfo.put("file_name", image.getFileName()==null?"":image.getFileName());
            imageInfo.put("file_path", image.getFilePath()==null?"":image.getFilePath());
            imageInfo.put("format", image.getFormat());
            imageInfo.put("data", Base64.getEncoder().encodeToString(image.getData()));

            if (image.getPosition() != null) {
                Map<String, Object> position = new HashMap<>();
                position.put("paragraph_index", image.getPosition().getParagraphIndex()==null?0:image.getPosition().getParagraphIndex());
                position.put("char_position", image.getPosition().getCharPosition()==null?0:image.getPosition().getCharPosition());
                position.put("paragraph_text", image.getPosition().getParagraphText() == null? "":image.getPosition().getParagraphText());
                imageInfo.put("position", position);
            }

            imageInfoList.add(imageInfo);
        }
        if (imageInfoList.size() == 0) {
            return "{}";
        }
        return JsonUtils.toJson(imageInfoList);
    }

    /**
     * 统一修复JSON字符串，确保符合Milvus要求
     */
    private String fixJsonForMilvus(String jsonStr) {
        if (jsonStr == null || jsonStr.trim().isEmpty()) {
            return "{}";
        }

        jsonStr = jsonStr.trim();

        // 修复1: 空数组转为空对象
        if ("[]".equals(jsonStr)) {
            return "{}";
        }

        // 修复2: 空对象保持
        if ("{}".equals(jsonStr)) {
            return jsonStr;
        }

        // 修复3: 检查是否为有效JSON
        try {
            // 尝试解析，确保JSON格式正确
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(jsonStr);

            // 修复嵌套的空数组
            jsonStr = fixNestedArrays(node);

            return jsonStr;
        } catch (Exception e) {
            // 如果不是有效JSON，转为空对象
            return "{}";
        }
    }

    /**
     * 修复嵌套的空数组
     */
    private String fixNestedArrays(JsonNode node) throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();

        if (node.isArray()) {
            ArrayNode arrayNode = (ArrayNode) node;
            if (arrayNode.isEmpty()) {
                return "{}";  // 顶层空数组转空对象
            }
            // 递归修复数组中的元素
            ArrayNode newArray = mapper.createArrayNode();
            for (JsonNode element : arrayNode) {
                if (element.isArray() && element.isEmpty()) {
                    newArray.add(mapper.createObjectNode());
                } else {
                    newArray.add(element);
                }
            }
            return mapper.writeValueAsString(newArray);
        }

        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                JsonNode value = entry.getValue();

                if (value.isArray() && value.isEmpty()) {
                    // 将空数组转为空对象
                    objectNode.set(entry.getKey(), mapper.createObjectNode());
                } else if (value.isObject() || value.isArray()) {
                    // 递归修复嵌套结构
                    String fixed = fixNestedArrays(value);
                    objectNode.set(entry.getKey(), mapper.readTree(fixed));
                }
            }
        }

        return mapper.writeValueAsString(node);
    }

    /**
     * 确保字符串不为null
     */
    private String ensureNotNull(String str) {
        return str != null ? str : "";
    }

    private List<DocumentParserWithStructure.TextEmbedding> generateEmbeddingsForChunks(List<DocumentParserWithStructure.DocumentChunk> chunks) {
        List<DocumentParserWithStructure.TextEmbedding> embeddings = new ArrayList<>();

        for (DocumentParserWithStructure.DocumentChunk chunk : chunks) {
            try {
                // 使用embedding服务生成向量
//                dev.langchain4j.data.embedding
                Embedding embedding1 = embeddingModel.embed(chunk.getText()).content();

                List<Float> vector = embedding1.vectorAsList();

                DocumentParserWithStructure.TextEmbedding embedding = new DocumentParserWithStructure.TextEmbedding();
                embedding.setText(chunk.getText());
                embedding.setVector(vector);
                embedding.setChunkId(chunk.getChunkId());

                embeddings.add(embedding);
            } catch (Exception e) {
                log.warn("生成向量失败: chunkId={}", chunk.getChunkId(), e);
                // 使用零向量作为兜底
                List<Float> zeroVector = Collections.nCopies(vectorDimension, 0f);

                DocumentParserWithStructure.TextEmbedding embedding = new DocumentParserWithStructure.TextEmbedding();
                embedding.setText(chunk.getText());
                embedding.setVector(zeroVector);
                embedding.setChunkId(chunk.getChunkId());

                embeddings.add(embedding);
            }
        }

        return embeddings;
    }

    // 简化版的 findImageByPosition 方法
    private DocumentParserWithStructure.ImageInfo findImageByPosition(DocumentParserWithStructure.ImagePosition pos, List<DocumentParserWithStructure.ImageInfo> images) {
        if (images == null || pos == null) {
            return null;
        }

        // 直接遍历匹配段落索引
        for (DocumentParserWithStructure.ImageInfo image : images) {
            if (image.getPosition() != null &&
                    image.getPosition().getParagraphIndex() != null &&
                    image.getPosition().getParagraphIndex().equals(pos.getParagraphIndex())) {
                return image;
            }
        }

        return null;
    }

    // 简化版的 hasHeadings 方法
    private boolean hasHeadings(List<DocumentParserWithStructure.Paragraph> paragraphs, int startIndex, int endIndex) {
        if (paragraphs == null || startIndex < 0 || endIndex >= paragraphs.size()) {
            return false;
        }

        for (int i = startIndex; i <= endIndex && i < paragraphs.size(); i++) {
            DocumentParserWithStructure.Paragraph para = paragraphs.get(i);
            if (para.getType() != null &&
                    (para.getType().equals("heading") ||
                            (para.getLevel() != null && para.getLevel() > 0))) {
                return true;
            }
        }

        return false;
    }

    // 4. 图片与文档关联
    private Map<Integer, List<DocumentParserWithStructure.ImagePosition>> associateImagesWithDocument(
            DocumentParserWithStructure.DocumentStructure structure,
            List<DocumentParserWithStructure.ImageInfo> images,
            List<DocumentParserWithStructure.Paragraph> paragraphs) {

        Map<Integer, List<DocumentParserWithStructure.ImagePosition>> imagePositions = new HashMap<>();

        for (DocumentParserWithStructure.ImageInfo image : images) {
            if (image.getPosition() != null) {
                int paraIndex = image.getPosition().getParagraphIndex();

                if (!imagePositions.containsKey(paraIndex)) {
                    imagePositions.put(paraIndex, new ArrayList<>());
                }

                // 计算图片的精确字符位置
                DocumentParserWithStructure.ImagePosition pos = image.getPosition();
                if (paraIndex < paragraphs.size()) {
                    DocumentParserWithStructure.Paragraph para = paragraphs.get(paraIndex);
                    pos.setCharPosition(calculateCharPosition(para, pos.getRunIndex()));

                    // 获取图片上下文
                    String context = getImageContext(paragraphs, paraIndex);
                    pos.setContext(context);
                }

                imagePositions.get(paraIndex).add(pos);
            }
        }

        return imagePositions;
    }

    private int calculateCharPosition(DocumentParserWithStructure.Paragraph para, int runIndex) {
        // 计算图片在段落文本中的大概位置
        if (para.getRuns() == null || para.getRuns().size() <= runIndex) {
            return 0;
        }

        int position = 0;
        for (int i = 0; i < runIndex; i++) {
            if (i < para.getRuns().size()) {
                DocumentParserWithStructure.RunInfo run = para.getRuns().get(i);
                if (run.getText() != null) {
                    position += run.getText().length();
                }
            }
        }

        return position;
    }

    private String getImageContext(List<DocumentParserWithStructure.Paragraph> paragraphs, int paraIndex) {
        StringBuilder context = new StringBuilder();
        int start = Math.max(0, paraIndex - 2);
        int end = Math.min(paragraphs.size() - 1, paraIndex + 2);

        for (int i = start; i <= end; i++) {
            context.append(paragraphs.get(i).getText()).append("\n");
        }

        return context.toString();
    }

    // 5. 智能分块（保持文档结构）
    private List<DocumentParserWithStructure.DocumentChunk> chunkDocumentIntelligently(
            DocumentParserWithStructure.DocumentContent docContent,
            Map<Integer, List<DocumentParserWithStructure.ImagePosition>> imagePositions,
            int chunkSize) {

        List<DocumentParserWithStructure.DocumentChunk> chunks = new ArrayList<>();
        List<DocumentParserWithStructure.Paragraph> paragraphs = docContent.getParagraphs();

        int currentChunkStart = 0;
        StringBuilder chunkText = new StringBuilder();
        List<DocumentParserWithStructure.ImageInfo> chunkImages = new ArrayList<>();
        int chunkId = 0;

        for (int i = 0; i < paragraphs.size(); i++) {
            DocumentParserWithStructure.Paragraph para = paragraphs.get(i);
            String paraText = para.getText() + "\n";

            // 检查是否需要新分块
            if (chunkText.length() + paraText.length() > chunkSize && chunkText.length() > 0) {
                // 保存当前分块
                DocumentParserWithStructure.DocumentChunk chunk = createDocumentChunk(
                        chunkId++, chunkText.toString(), chunkImages,
                        currentChunkStart, i - 1, paragraphs
                );
                chunks.add(chunk);

                // 开始新分块
                currentChunkStart = i;
                chunkText = new StringBuilder();
                chunkImages = new ArrayList<>();
            }

            // 添加段落文本
            chunkText.append(paraText);

            // 添加关联图片
            if (imagePositions.containsKey(i)) {
                for (DocumentParserWithStructure.ImagePosition pos : imagePositions.get(i)) {
                    DocumentParserWithStructure.ImageInfo image = findImageByPosition(pos, docContent.getImages());
                    if (image != null && !chunkImages.contains(image)) {
                        chunkImages.add(image);
                    }
                }
            }

            // 如果是标题，强制分块
            if (para.getType().equals("heading") && para.getLevel() <= 2) {
                if (chunkText.length() > 0) {
                    DocumentParserWithStructure.DocumentChunk chunk = createDocumentChunk(
                            chunkId++, chunkText.toString(), chunkImages,
                            currentChunkStart, i, paragraphs
                    );
                    chunks.add(chunk);

                    currentChunkStart = i + 1;
                    chunkText = new StringBuilder();
                    chunkImages = new ArrayList<>();
                }
            }
        }

        // 添加最后一个分块
        if (chunkText.length() > 0) {
            DocumentParserWithStructure.DocumentChunk chunk = createDocumentChunk(
                    chunkId, chunkText.toString(), chunkImages,
                    currentChunkStart, paragraphs.size() - 1, paragraphs
            );
            chunks.add(chunk);
        }

        return chunks;
    }

    private DocumentParserWithStructure.DocumentChunk createDocumentChunk(int chunkId, String text, List<DocumentParserWithStructure.ImageInfo> images,
                                                                          int startParaIndex, int endParaIndex,
                                                                          List<DocumentParserWithStructure.Paragraph> paragraphs) {
        DocumentParserWithStructure.DocumentChunk chunk = new DocumentParserWithStructure.DocumentChunk();
        chunk.setChunkId(chunkId);
        chunk.setText(text.trim());
        chunk.setImages(images);
        chunk.setStartParagraphIndex(startParaIndex);
        chunk.setEndParagraphIndex(endParaIndex);
        chunk.setWordCount(countWords(text));
        chunk.setCharCount(text.length());

        // 记录分块的元数据
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("start_paragraph_id", paragraphs.get(startParaIndex).getId());
        metadata.put("end_paragraph_id", paragraphs.get(endParaIndex).getId());
        metadata.put("contains_headings", hasHeadings(paragraphs, startParaIndex, endParaIndex));
        metadata.put("image_count", images.size());

        chunk.setMetadata(metadata);
        return chunk;
    }

    /**
     * 为操作手册文档进行智能分块
     */
    private List<String> chunkDocumentForManual(String text, DocumentImportRequest request) {
        // 检测文档类型
        DocumentType docType = detectDocumentType(text);

        List<String> chunks = new ArrayList<>();

        switch (docType) {
            case MANUAL:
                // 操作手册：使用章节分块
                chunks = manualDocumentChunker.chunkBySemantic(text, null, null);
                break;

            case REPORT:
                // 报告：按章节和图表分块
                //todo: 差异性处理其他类型文档
                break;

            case PAPER:
                // 论文：按章节和参考文献分块
                //todo: 差异性处理其他类型文档
                break;

            default:
                // 默认：智能分块
                chunks = documentChunker.chunkBySemantic(
                        text,
                        request.getChunkSize() != null ? request.getChunkSize() : 1000,
                        request.getOverlapSize() != null ? request.getOverlapSize() : 200
                );
        }

        log.info("文档分块完成，类型: {}, 块数: {}", docType, chunks.size());

        // 添加分块元信息
        return addChunkMetadata(chunks, docType);
    }

    /**
     * 检测文档类型
     */
    private DocumentType detectDocumentType(String text) {
        String lowerText = text.toLowerCase();

        // 检查操作手册特征
        if (lowerText.contains("操作手册") || lowerText.contains("用户手册") ||
                lowerText.contains("使用说明") || lowerText.contains("安装指南") ||
                lowerText.contains("配置说明") || lowerText.contains("快速入门")) {
            return DocumentType.MANUAL;
        }

        // 检查报告特征
        if (lowerText.contains("报告") || lowerText.contains("analysis") ||
                lowerText.contains("总结") || lowerText.contains("结论") ||
                lowerText.contains("abstract") || lowerText.contains("introduction")) {
            return DocumentType.REPORT;
        }

        // 检查论文特征
        if (lowerText.contains("参考文献") || lowerText.contains("reference") ||
                lowerText.contains("摘要") || lowerText.contains("abstract") ||
                lowerText.contains("引言") || lowerText.contains("结论")) {
            return DocumentType.PAPER;
        }

        return DocumentType.GENERAL;
    }

    /**
     * 添加分块元信息
     */
    private List<String> addChunkMetadata(List<String> chunks, DocumentType docType) {
        List<String> enhancedChunks = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            String metadata = String.format(
                    "【分块信息】\n类型: %s\n序号: %d/%d\n\n%s",
                    docType.getName(), i + 1, chunks.size(), chunk
            );
            enhancedChunks.add(metadata);
        }

        return enhancedChunks;
    }

    /**
     * 文档类型枚举
     */
    private enum DocumentType {
        MANUAL("操作手册"),
        REPORT("报告文档"),
        PAPER("学术论文"),
        GENERAL("通用文档");

        private final String name;

        DocumentType(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    @Override
    public List<SearchResult> semanticSearch(String query,
                                             String collectionName,
                                             int topK) throws Exception {

        List<SearchResult> results = new ArrayList<>();

        try {
            // 生成查询向量
            TextEmbedding queryEmbedding = generateQueryEmbedding(query);

            // 构建搜索参数
            List<String> outputFields = Arrays.asList(
                    FIELD_ID, FIELD_TEXT, FIELD_DOCUMENT_ID, FIELD_CHUNK_ID,
                    FIELD_IMAGES, FIELD_METADATA, FIELD_CREATE_TIME
            );

            SearchParam searchParam = SearchParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withMetricType(getMetricType(metricType))
                    .withOutFields(outputFields)
                    .withTopK(topK)
                    .withVectors(Collections.singletonList(queryEmbedding.getVector()))
                    .withVectorFieldName(FIELD_VECTOR)
                    .build();

            // 执行搜索
            R<SearchResults> response = milvusClient.search(searchParam);

            if (response.getStatus() != R.Status.Success.getCode()) {
                throw new Exception("搜索失败: " + response.getMessage());
            }

            // 解析结果
            SearchResultsWrapper wrapper = new SearchResultsWrapper(response.getData().getResults());
            List<SearchResultsWrapper.IDScore> idScores = wrapper.getIDScore(0);

            for (int i = 0; i < idScores.size(); i++) {
                SearchResultsWrapper.IDScore idScore = idScores.get(i);

                SearchResult result = new SearchResult();
                result.setResultId(UUID.randomUUID().toString());
                result.setSimilarityScore((double) idScore.getScore());

                // 获取字段值
                getFieldValuesSimplified(result, wrapper, i);

                results.add(result);
            }

        } catch (Exception e) {
            log.error("语义搜索失败", e);
            throw e;
        }

        return results;
    }

    /**
     * 简化的字段获取方法
     */
    private void getFieldValuesSimplified(SearchResult result, SearchResultsWrapper wrapper, int index) {
        try {
            // 获取文本字段
            try {
                FieldDataWrapper textWrapper = wrapper.getFieldWrapper(FIELD_TEXT);
                if (textWrapper != null) {
                    List<?> textData = textWrapper.getFieldData();
                    if (textData != null && index < textData.size()) {
                        result.setText(textData.get(index).toString());
                    }
                }
            } catch (Exception e) {
                log.warn("获取文本字段失败", e);
            }

            // 获取文档ID字段
            try {
                FieldDataWrapper docIdWrapper = wrapper.getFieldWrapper(FIELD_DOCUMENT_ID);
                if (docIdWrapper != null) {
                    List<?> docIdData = docIdWrapper.getFieldData();
                    if (docIdData != null && index < docIdData.size()) {
                        result.setDocumentId(docIdData.get(index).toString());
                    }
                }
            } catch (Exception e) {
                log.warn("获取文档ID字段失败", e);
            }

            // 获取分块ID字段
            try {
                FieldDataWrapper chunkIdWrapper = wrapper.getFieldWrapper(FIELD_CHUNK_ID);
                if (chunkIdWrapper != null) {
                    List<?> chunkIdData = chunkIdWrapper.getFieldData();
                    if (chunkIdData != null && index < chunkIdData.size()) {
                        result.setChunkId(chunkIdData.get(index).toString());
                    }
                }
            } catch (Exception e) {
                log.warn("获取分块ID字段失败", e);
            }

            // 获取图片字段
            try {
                FieldDataWrapper imagesWrapper = wrapper.getFieldWrapper(FIELD_IMAGES);
                if (imagesWrapper != null) {
                    List<?> imagesData = imagesWrapper.getFieldData();
                    if (imagesData != null && index < imagesData.size()) {
                        String imagesJson = imagesData.get(index).toString();
                        List<DocumentParserWithStructure.ImageInfo> images = JsonUtils.toList(imagesJson, DocumentParserWithStructure.ImageInfo.class);
                        result.setImages(images);
                        result.setHasImages(true);
                        result.setImageCount(images != null ? images.size() : 0);
                    }
                }
            } catch (Exception e) {
                log.warn("获取图片字段失败", e);
            }

            // 获取元数据字段
            try {
                FieldDataWrapper metadataWrapper = wrapper.getFieldWrapper(FIELD_METADATA);
                if (metadataWrapper != null) {
                    List<?> metadataData = metadataWrapper.getFieldData();
                    if (metadataData != null && index < metadataData.size()) {
                        String metadataJson = metadataData.get(index).toString();
                        Map<String, Object> metadata = JsonUtils.toMap(metadataJson);
                        result.setMetadata(metadata);

                        // 从元数据中提取常用字段
                        if (metadata.containsKey("pageNumber")) {
                            Object pageNum = metadata.get("pageNumber");
                            if (pageNum instanceof Number) {
                                result.setPageNumber(((Number) pageNum).intValue());
                            }
                        }
                        if (metadata.containsKey("sectionTitle")) {
                            Object sectionTitle = metadata.get("sectionTitle");
                            if (sectionTitle != null) {
                                result.setSectionTitle(sectionTitle.toString());
                            }
                        }
                        if (metadata.containsKey("fileType")) {
                            Object fileType = metadata.get("fileType");
                            if (fileType != null) {
                                result.setFileType(fileType.toString());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("获取元数据字段失败", e);
            }

        } catch (Exception e) {
            log.error("获取字段值失败", e);
        }
    }

    /**
     * 将字符串转换为 MetricType 枚举
     */
    private MetricType getMetricType(String metricTypeStr) {
        if (metricTypeStr == null) {
            return MetricType.IP;  // 默认内积
        }

        switch (metricTypeStr.toUpperCase()) {
            case "IP":
                return MetricType.IP;
            case "L2":
                return MetricType.L2;
            case "COSINE":
                return MetricType.COSINE;
            case "HAMMING":
                return MetricType.HAMMING;
            case "JACCARD":
                return MetricType.JACCARD;
            default:
                log.warn("未知的度量类型: {}, 使用默认值 IP", metricTypeStr);
                return MetricType.IP;
        }
    }

    @Override
    public List<SearchResult> hybridSearch(SearchRequest request) throws Exception {
        List<SearchResult> results = new ArrayList<>();

        // 简单的混合搜索实现：先文本搜索，再图片搜索
        String collectionName = request.getCollectionName();

        // 文本搜索
        List<SearchResult> textResults = semanticSearch(
                request.getQueryText(), collectionName, request.getTopK());

        // 如果有图片，进行图片搜索
        if (request.getQueryImage() != null) {
            byte[] imageData = request.getQueryImage().getBytes();
            List<SearchResult> imageResults = imageSearch(
                    imageData, collectionName, request.getTopK());

            // 合并结果
            results = mergeSearchResults(textResults, imageResults, request.getSimilarityThreshold());
        } else {
            results = textResults;
        }

        return results;
    }

    @Override
    public List<SearchResult> imageSearch(byte[] imageData,
                                          String collectionName,
                                          int topK) throws Exception {
        List<SearchResult> results = new ArrayList<>();

        try {
            // 这里应该调用图片特征提取和向量化
            // 简化实现：先进行文本搜索
            TextEmbedding imageEmbedding = generateImageEmbedding(imageData);

            List<String> outputFields = Arrays.asList(
                    FIELD_ID, FIELD_TEXT, FIELD_DOCUMENT_ID, FIELD_CHUNK_ID,
                    FIELD_IMAGES, FIELD_METADATA, FIELD_CREATE_TIME
            );

            SearchParam searchParam = SearchParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withMetricType(getMetricType(metricType))
                    .withOutFields(outputFields)
                    .withTopK(topK)
                    .withVectors(Collections.singletonList(imageEmbedding.getVector()))
                    .withVectorFieldName(FIELD_VECTOR)
                    .build();

            R<SearchResults> response = milvusClient.search(searchParam);

            if (response.getStatus() != R.Status.Success.getCode()) {
                throw new Exception("图片搜索失败: " + response.getMessage());
            }

            // 解析结果
            SearchResultsWrapper wrapper = new SearchResultsWrapper(response.getData().getResults());
            List<SearchResultsWrapper.IDScore> idScores = wrapper.getIDScore(0);

            for (int i = 0; i < idScores.size(); i++) {
                SearchResultsWrapper.IDScore idScore = idScores.get(i);

                SearchResult result = new SearchResult();
                result.setResultId(UUID.randomUUID().toString());
                result.setSimilarityScore((double) idScore.getScore());

                // 获取字段值
                getFieldValuesSimplified(result, wrapper, i);

                results.add(result);
            }

        } catch (Exception e) {
            log.error("图片搜索失败", e);
            throw e;
        }

        return results;
    }

    @Override
    public Boolean createCollection(String collectionName) throws Exception {
        try {
            // 定义字段
            List<FieldType> fields = new ArrayList<>();

            // ID字段
            fields.add(FieldType.newBuilder()
                    .withName(FIELD_ID)
                    .withDataType(DataType.Int64)
                    .withPrimaryKey(true)
                    .withAutoID(true)
                    .build());

            // 向量字段
            fields.add(FieldType.newBuilder()
                    .withName(FIELD_VECTOR)
                    .withDataType(DataType.FloatVector)
                    .withDimension(vectorDimension)
                    .build());

            // 文本字段
            fields.add(FieldType.newBuilder()
                    .withName(FIELD_TEXT)
                    .withDataType(DataType.VarChar)
                    .withMaxLength(65535)
                    .build());

            // 文档ID字段
            fields.add(FieldType.newBuilder()
                    .withName(FIELD_DOCUMENT_ID)
                    .withDataType(DataType.VarChar)
                    .withMaxLength(100)
                    .build());

            // 分块ID字段
            fields.add(FieldType.newBuilder()
                    .withName(FIELD_CHUNK_ID)
                    .withDataType(DataType.VarChar)
                    .withMaxLength(100)
                    .build());

            // 图片字段
            fields.add(FieldType.newBuilder()
                    .withName(FIELD_IMAGES)
                    .withDataType(DataType.JSON)
                    .build());

            // 元数据字段
            fields.add(FieldType.newBuilder()
                    .withName(FIELD_METADATA)
                    .withDataType(DataType.VarChar)
                    .withMaxLength(65535)
                    .build());

            // 创建时间字段
            fields.add(FieldType.newBuilder()
                    .withName(FIELD_CREATE_TIME)
                    .withDataType(DataType.Int64)
                    .build());

            // 2. 创建 CollectionSchema
            // 注意：在 2.5.4 中，使用 CollectionSchemaParam
            CollectionSchemaParam.Builder schemaBuilder = CollectionSchemaParam.newBuilder();

            // 添加所有字段
            for (FieldType fieldType : fields) {
                schemaBuilder.addFieldType(fieldType);
            }

            // 构建 CollectionSchemaParam
            CollectionSchemaParam schemaParam = schemaBuilder
                    .withEnableDynamicField(true)  // 允许动态字段
                    .build();

            // 3. 创建 CreateCollectionParam
            CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withSchema(schemaParam)
                    .build();

            // 4. 创建集合
            R<RpcStatus> response = milvusClient.createCollection(createParam);

            if (response.getStatus() != R.Status.Success.getCode()) {
                throw new Exception("创建集合失败: " + response.getMessage());
            }

            log.info("集合创建成功: {}", collectionName);

            // 创建索引
            CreateIndexParam indexParam = CreateIndexParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withFieldName(FIELD_VECTOR)
                    .withIndexType(getIndexType(indexType))
                    .withMetricType(getMetricType(metricType))
                    .withExtraParam("{\"M\": 16, \"efConstruction\": 200}")
                    .build();

            R<RpcStatus> indexResponse = milvusClient.createIndex(indexParam);

            if (indexResponse.getStatus() != R.Status.Success.getCode()) {
                throw new Exception("创建索引失败: " + indexResponse.getMessage());
            }

            return true;

        } catch (Exception e) {
            log.error("创建集合失败", e);
            throw e;
        }
    }

    /**
     * 将字符串转换为 IndexType 枚举
     */
    private IndexType getIndexType(String indexTypeStr) {
        if (indexTypeStr == null) {
            return IndexType.IVF_FLAT;  // 默认内积
        }

        switch (indexTypeStr.toUpperCase()) {
            case "None":
                return IndexType.None;
            case "FLAT":
                return IndexType.FLAT;
            case "IVF_FLAT":
                return IndexType.IVF_FLAT;
            case "IVF_SQ8":
                return IndexType.IVF_SQ8;
            case "IVF_PQ":
                return IndexType.IVF_PQ;
            case "HNSW":
                return IndexType.HNSW;
            case "DISKANN":
                return IndexType.DISKANN;
            case "AUTOINDEX":
                return IndexType.AUTOINDEX;
            case "SCANN":
                return IndexType.SCANN;
            case "GPU_IVF_FLAT":
                return IndexType.GPU_IVF_FLAT;
            case "GPU_IVF_PQ":
                return IndexType.GPU_IVF_PQ;
            case "GPU_BRUTE_FORCE":
                return IndexType.GPU_BRUTE_FORCE;
            case "GPU_CAGRA":
                return IndexType.GPU_CAGRA;
            case "BIN_FLAT":
                return IndexType.BIN_FLAT;
            case "BIN_IVF_FLAT":
                return IndexType.BIN_IVF_FLAT;
            case "TRIE":
                return IndexType.TRIE;
            case "STL_SORT":
                return IndexType.STL_SORT;
            case "INVERTED":
                return IndexType.INVERTED;
            case "BITMAP":
                return IndexType.BITMAP;
            case "SPARSE_INVERTED_INDEX":
                return IndexType.SPARSE_INVERTED_INDEX;
            case "SPARSE_WAND":
                return IndexType.SPARSE_WAND;
            default:
                log.warn("未知的度量类型: {}, 使用默认值 IP", indexTypeStr);
                return IndexType.HNSW;
        }
    }

    @Override
    public Boolean dropCollection(String collectionName) throws Exception {
        try {
            DropCollectionParam dropParam = DropCollectionParam.newBuilder()
                    .withCollectionName(collectionName)
                    .build();

            R<RpcStatus> response = milvusClient.dropCollection(dropParam);

            if (response.getStatus() != R.Status.Success.getCode()) {
                throw new Exception("删除集合失败: " + response.getMessage());
            }

            return true;

        } catch (Exception e) {
            log.error("删除集合失败", e);
            throw e;
        }
    }

    @Override
    public Boolean hasCollection(String collectionName) throws Exception {
        try {
            HasCollectionParam hasParam = HasCollectionParam.newBuilder()
                    .withCollectionName(collectionName)
                    .build();

            R<Boolean> response = milvusClient.hasCollection(hasParam);

            if (response.getStatus() != R.Status.Success.getCode()) {
                throw new Exception("检查集合失败: " + response.getMessage());
            }

            return response.getData();

        } catch (Exception e) {
            log.error("检查集合失败", e);
            throw e;
        }
    }

    @Override
    public MilvusInsertResult insertWithImages(
            List<DocumentChunkWithImages> chunks,
            List<TextEmbedding> embeddings,
            MilvusConfig config
    ) throws Exception {

        MilvusInsertResult result = new MilvusInsertResult();
        result.collectionName = config.getCollection();

        try {
            // 准备插入字段
            List<InsertParam.Field> fields = new ArrayList<>();
//            List<String> ids = new ArrayList<>();
//            for (long i = 1; i <= chunks.size(); i++) {
//                ids.add(UUID.randomUUID().toString());
//            }
//            fields.add(new InsertParam.Field(FIELD_ID, ids));

            // 向量字段
            List<List<Float>> vectors = embeddings.stream()
                    .map(TextEmbedding::getVector)
                    .collect(Collectors.toList());
            fields.add(new InsertParam.Field(FIELD_VECTOR, vectors));

            // 文本字段
            List<String> texts = chunks.stream()
                    .map(DocumentChunkWithImages::getText)
                    .collect(Collectors.toList());
            fields.add(new InsertParam.Field(FIELD_TEXT, texts));

            // 文档ID字段
            List<String> documentIds = chunks.stream()
                    .map(DocumentChunkWithImages::getDocumentId)
                    .collect(Collectors.toList());
            fields.add(new InsertParam.Field(FIELD_DOCUMENT_ID, documentIds));

            // 分块ID字段
            List<String> chunkIds = chunks.stream()
                    .map(DocumentChunkWithImages::getChunkId)
                    .collect(Collectors.toList());
            fields.add(new InsertParam.Field(FIELD_CHUNK_ID, chunkIds));

            // 图片字段
            List<String> imagesJson = chunks.stream()
                    .map(chunk -> JsonUtils.toJson(chunk.getImages()))
                    .collect(Collectors.toList());
            fields.add(new InsertParam.Field(FIELD_IMAGES, imagesJson));

            // 元数据字段
            List<String> metadataJson = chunks.stream()
                    .map(chunk -> JsonUtils.toJson(chunk.getMetadata()))
                    .collect(Collectors.toList());
            fields.add(new InsertParam.Field(FIELD_METADATA, metadataJson));

            // 创建时间字段
            List<Long> createTimes = chunks.stream()
                    .map(chunk -> System.currentTimeMillis())
                    .collect(Collectors.toList());
            fields.add(new InsertParam.Field(FIELD_CREATE_TIME, createTimes));

            // 执行插入
            InsertParam insertParam = InsertParam.newBuilder()
                    .withCollectionName(config.getCollection())
                    .withFields(fields)
                    .build();

            R<MutationResult> response = milvusClient.insert(insertParam);

            if (response.getStatus() != R.Status.Success.getCode()) {
                throw new Exception("插入失败: " + response.getMessage());
            }

            MutationResult mutationResult = response.getData();
            result.success = true;
            result.insertedCount = (long) mutationResult.getSuccIndexCount();

        } catch (Exception e) {
            log.error("插入失败", e);
            result.error = e.getMessage();
            throw e;
        } finally {
            result.endTime = System.currentTimeMillis();
            result.calculateElapsedTime();
        }

        return result;
    }

    //    @Override
    public MilvusInsertResult insertWithImages1(
            List<DocumentChunkWithImages> chunks,
            List<TextEmbedding> embeddings,
            MilvusConfig config
    ) throws Exception {

        MilvusInsertResult result = new MilvusInsertResult();
        result.collectionName = config.getCollection();

        try {
            // 准备插入字段
            List<InsertParam.Field> fields = new ArrayList<>();
//            List<String> ids = new ArrayList<>();
//            for (long i = 1; i <= chunks.size(); i++) {
//                ids.add(UUID.randomUUID().toString());
//            }
//            fields.add(new InsertParam.Field(FIELD_ID, ids));

            // 向量字段
            List<List<Float>> vectors = embeddings.stream()
                    .map(TextEmbedding::getVector)
                    .collect(Collectors.toList());
            fields.add(new InsertParam.Field(FIELD_VECTOR, vectors));

            // 文本字段
            List<String> texts = chunks.stream()
                    .map(DocumentChunkWithImages::getText)
                    .collect(Collectors.toList());
            fields.add(new InsertParam.Field(FIELD_TEXT, texts));

            // 文档ID字段
            List<String> documentIds = chunks.stream()
                    .map(DocumentChunkWithImages::getDocumentId)
                    .collect(Collectors.toList());
            fields.add(new InsertParam.Field(FIELD_DOCUMENT_ID, documentIds));

            // 分块ID字段
            List<String> chunkIds = chunks.stream()
                    .map(DocumentChunkWithImages::getChunkId)
                    .collect(Collectors.toList());
            fields.add(new InsertParam.Field(FIELD_CHUNK_ID, chunkIds));

            // 图片字段
            List<String> imagesJson = chunks.stream()
                    .map(chunk -> JsonUtils.toJson(chunk.getImages()))
                    .collect(Collectors.toList());
            fields.add(new InsertParam.Field(FIELD_IMAGES, imagesJson));

            // 元数据字段
            List<String> metadataJson = chunks.stream()
                    .map(chunk -> JsonUtils.toJson(chunk.getMetadata()))
                    .collect(Collectors.toList());
            fields.add(new InsertParam.Field(FIELD_METADATA, metadataJson));

            // 创建时间字段
            List<Long> createTimes = chunks.stream()
                    .map(chunk -> System.currentTimeMillis())
                    .collect(Collectors.toList());
            fields.add(new InsertParam.Field(FIELD_CREATE_TIME, createTimes));

            // 执行插入
            InsertParam insertParam = InsertParam.newBuilder()
                    .withCollectionName(config.getCollection())
                    .withFields(fields)
                    .build();

            R<MutationResult> response = milvusClient.insert(insertParam);

            if (response.getStatus() != R.Status.Success.getCode()) {
                throw new Exception("插入失败: " + response.getMessage());
            }

            MutationResult mutationResult = response.getData();
            result.success = true;
            result.insertedCount = (long) mutationResult.getSuccIndexCount();

        } catch (Exception e) {
            log.error("插入失败", e);
            result.error = e.getMessage();
            throw e;
        } finally {
            result.endTime = System.currentTimeMillis();
            result.calculateElapsedTime();
        }

        return result;
    }

    // ========== 私有方法 ==========

    private void ensureCollectionExists(String collectionName) throws Exception {
        if (!hasCollection(collectionName)) {
            createCollection(collectionName);
        }
    }

    private String generateDocumentId(MultipartFile file) {
        return file.getOriginalFilename() + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private List<DocumentChunkWithImages> associateImagesWithChunks(
            List<String> textChunks,
            List<ImageInfo> images,
            String fileName,
            String documentId) {

        List<DocumentChunkWithImages> chunks = new ArrayList<>();

        if (textChunks == null || textChunks.isEmpty()) {
            return chunks;
        }

        // 记录已使用的图片，避免重复分配
        Set<String> usedImageIds = new HashSet<>();

        for (int i = 0; i < textChunks.size(); i++) {
            String chunkText = textChunks.get(i);

            // 策略1：基于页码关联
            List<ImageInfo> chunkImagesByPage = findImagesByPage(images, i, usedImageIds);

            // 策略2：基于位置关联（如果图片有位置信息）
            List<ImageInfo> chunkImagesByPosition = findImagesByPosition(images, i, textChunks.size(), usedImageIds);

            // 策略3：基于语义关联
//            List<ImageInfo> chunkImagesBySemantic = findImagesBySemantic(chunkText, images, usedImageIds);

            // 合并所有策略找到的图片
            List<ImageInfo> chunkImages = new ArrayList<>();
            chunkImages.addAll(chunkImagesByPage);
            chunkImages.addAll(chunkImagesByPosition);
//            chunkImages.addAll(chunkImagesBySemantic);

            // 去重
            chunkImages = chunkImages.stream()
                    .distinct()
                    .collect(Collectors.toList());

            // 标记图片为已使用
            chunkImages.forEach(img -> {
                if (img.getStoredName() != null) {
                    usedImageIds.add(img.getStoredName());
                }
            });

            // 构建分块元数据
            Map<String, Object> metadata = buildChunkMetadata(
                    i, textChunks.size(), fileName, chunkImages, chunkText
            );

            // 构建文档块
            DocumentChunkWithImages chunk = DocumentChunkWithImages.builder()
                    .chunkId(generateChunkId(documentId, i))
                    .documentId(documentId)
                    .chunkIndex(i)
                    .totalChunks(textChunks.size())
                    .text(chunkText)
                    .images(chunkImages)
                    .metadata(metadata)
                    .pageNumber(extractPageNumber(metadata, i))
                    .fileType(getFileExtension(fileName))
                    .fileName(fileName)
                    .build();

            chunks.add(chunk);
        }

        // 处理未关联的图片
        processUnassignedImages(images, usedImageIds, chunks);

        return chunks;
    }

    /**
     * 策略1：基于页码关联图片
     */
    private List<ImageInfo> findImagesByPage(
            List<ImageInfo> allImages,
            int chunkIndex,
            Set<String> usedImageIds) {

        if (allImages == null || allImages.isEmpty()) {
            return new ArrayList<>();
        }

        return allImages.stream()
                .filter(img -> {
                    if (img == null) {
                        return false;
                    }

                    // 检查是否已使用
                    if (usedImageIds.contains(img.getStoredName())) {
                        return false;
                    }

                    Integer pageNum = img.getPageNumber();
                    if (pageNum == null) {
                        return false;
                    }

                    // 简单策略：图片页码 = 分块索引 + 1
                    return pageNum == (chunkIndex + 1);
                })
                .collect(Collectors.toList());
    }

    /**
     * 提取页码
     */
    private Integer extractPageNumber(Map<String, Object> metadata, int defaultPage) {
        if (metadata == null) {
            return defaultPage + 1;
        }

        Object pageObj = metadata.get("pageNumber");
        if (pageObj instanceof Integer) {
            return (Integer) pageObj;
        }

        return defaultPage + 1;
    }

    /**
     * 处理未关联的图片
     */
    private void processUnassignedImages(
            List<ImageInfo> allImages,
            Set<String> usedImageIds,
            List<DocumentChunkWithImages> chunks) {

        if (allImages == null || allImages.isEmpty() || chunks == null || chunks.isEmpty()) {
            return;
        }

        // 找到未使用的图片
        List<ImageInfo> unassignedImages = allImages.stream()
                .filter(img -> img != null && img.getStoredName() != null)
                .filter(img -> !usedImageIds.contains(img.getStoredName()))
                .collect(Collectors.toList());

        if (unassignedImages.isEmpty()) {
            return;
        }

        log.info("有 {} 张图片未关联到任何分块，尝试关联到最近的分块", unassignedImages.size());

        // 尝试将未关联的图片分配到最近的分块
        for (ImageInfo image : unassignedImages) {
            Integer pageNum = image.getPageNumber();

            if (pageNum != null) {
                // 按页码关联
                for (DocumentChunkWithImages chunk : chunks) {
                    if (chunk.getPageNumber() != null &&
                            chunk.getPageNumber().equals(pageNum)) {
                        chunk.getImages().add(image);
                        usedImageIds.add(image.getStoredName());
                        break;
                    }
                }
            } else {
                // 如果没有页码，关联到第一个分块
                if (!chunks.isEmpty()) {
                    chunks.get(0).getImages().add(image);
                    usedImageIds.add(image.getStoredName());
                }
            }
        }
    }

    /**
     * 构建分块元数据
     */
    private Map<String, Object> buildChunkMetadata(
            int chunkIndex,
            int totalChunks,
            String fileName,
            List<ImageInfo> images,
            String chunkText) {

        Map<String, Object> metadata = new HashMap<>();

        // 基础信息
        metadata.put("chunkIndex", chunkIndex);
        metadata.put("totalChunks", totalChunks);
        metadata.put("fileName", fileName);

        // 图片信息
        metadata.put("hasImages", images != null && !images.isEmpty());
        metadata.put("imageCount", images != null ? images.size() : 0);

        // 文本信息
        metadata.put("textLength", chunkText != null ? chunkText.length() : 0);
        metadata.put("wordCount", countWords(chunkText));

        // 提取关键词
        List<String> keywords = extractKeywords(chunkText);
        if (!keywords.isEmpty()) {
            metadata.put("keywords", keywords);
        }

        // 图片详细信息
        if (images != null && !images.isEmpty()) {
            List<Map<String, Object>> imageDetails = images.stream()
                    .map(this::buildImageDetail)
                    .collect(Collectors.toList());
            metadata.put("imageDetails", imageDetails);
        }

        // 时间戳
        metadata.put("createdAt", System.currentTimeMillis());

        return metadata;
    }

    /**
     * 构建图片详情
     */
    private Map<String, Object> buildImageDetail(ImageInfo image) {
        Map<String, Object> detail = new HashMap<>();

        if (image == null) {
            return detail;
        }

        detail.put("originalName", image.getOriginalName());
        detail.put("storedName", image.getStoredName());
        detail.put("fileType", image.getFileType());
//        detail.put("mimeType", image.getMimeType());
        detail.put("width", image.getWidth());
        detail.put("height", image.getHeight());
        detail.put("size", image.getSize());
        detail.put("pageNumber", image.getPageNumber());

        if (image.getMetadata() != null) {
            detail.put("metadata", image.getMetadata());
        }

        return detail;
    }

    /**
     * 计算单词数
     */
    private int countWords(String text) {
        if (text == null || text.trim().isEmpty()) {
            return 0;
        }

        return text.trim().split("\\s+").length;
    }

    /**
     * 提取关键词
     */
    private List<String> extractKeywords(String text) {
        List<String> keywords = new ArrayList<>();

        if (text == null || text.trim().isEmpty()) {
            return keywords;
        }

        // 简单实现：提取长度大于3的单词
        String[] words = text.toLowerCase().split("[\\s,;.!?]+");

        for (String word : words) {
            if (word.length() > 3 && !isStopWord(word)) {
                keywords.add(word);
            }
        }

        // 去重
        return keywords.stream().distinct().collect(Collectors.toList());
    }

    /**
     * 检查是否为停用词
     */
    private boolean isStopWord(String word) {
        Set<String> stopWords = Set.of(
                "the", "and", "that", "for", "with", "this", "from", "have", "what",
                "when", "where", "which", "who", "whom", "why", "how", "about"
        );

        return stopWords.contains(word.toLowerCase());
    }

    /**
     * 生成分块ID
     */
    private String generateChunkId(String documentId, int chunkIndex) {
        return String.format("%s_chunk_%03d", documentId, chunkIndex);
    }

    /**
     * 策略2：基于位置关联图片
     */
    private List<ImageInfo> findImagesByPosition(
            List<ImageInfo> allImages,
            int chunkIndex,
            int totalChunks,
            Set<String> usedImageIds) {

        if (allImages == null || allImages.isEmpty()) {
            return new ArrayList<>();
        }

        // 计算分块在整个文档中的位置比例
        float chunkPosition = (float) chunkIndex / totalChunks;

        return allImages.stream()
                .filter(img -> {
                    if (img == null) {
                        return false;
                    }

                    // 检查是否已使用
                    if (usedImageIds.contains(img.getStoredName())) {
                        return false;
                    }

                    // 如果图片有位置信息
                    Map<String, Object> imgMetadata = img.getMetadata();
                    if (imgMetadata == null || !imgMetadata.containsKey("position")) {
                        return false;
                    }

                    Object positionObj = imgMetadata.get("position");
                    if (!(positionObj instanceof Float)) {
                        return false;
                    }

                    float imgPosition = (Float) positionObj;

                    // 图片位置在分块位置的附近
                    return Math.abs(imgPosition - chunkPosition) < 0.1; // 10% 范围内
                })
                .collect(Collectors.toList());
    }

    private List<TextEmbedding> generateEmbeddings(List<DocumentChunkWithImages> chunks) {
        List<TextEmbedding> embeddings = new ArrayList<>();

        for (DocumentChunkWithImages chunk : chunks) {
            String text = chunk.getText();
            // 这里应该调用实际的嵌入模型
            // 简化实现：生成随机向量
            List<Float> vector = generateRandomVector(vectorDimension);

            TextEmbedding embedding = TextEmbedding.builder()
                    .vector(vector)
                    .text(text)
                    .build();

            embeddings.add(embedding);
        }

        return embeddings;
    }

    private TextEmbedding generateQueryEmbedding(String query) {
        // 简化实现：生成随机向量
        List<Float> vector = generateRandomVector(vectorDimension);
        return TextEmbedding.builder()
                .vector(vector)
                .text(query)
                .build();
    }

    private TextEmbedding generateImageEmbedding(byte[] imageData) {
        // 简化实现：生成随机向量
        List<Float> vector = generateRandomVector(vectorDimension);
        return TextEmbedding.builder()
                .vector(vector)
                .text("image_embedding")
                .build();
    }

    private List<Float> generateRandomVector(int dimension) {
        List<Float> vector = new ArrayList<>();
        Random random = new Random();

        for (int i = 0; i < dimension; i++) {
            vector.add(random.nextFloat());
        }

        // 归一化
        float sum = 0.0f;
        for (float value : vector) {
            sum += value * value;
        }
        float norm = (float) Math.sqrt(sum);

        for (int i = 0; i < dimension; i++) {
            vector.set(i, vector.get(i) / norm);
        }

        return vector;
    }

    private String getFileExtension(String fileName) {
        if (fileName == null) {
            return "unknown";
        }
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < fileName.length() - 1) {
            return fileName.substring(lastDot + 1).toLowerCase();
        }
        return "unknown";
    }

    private List<SearchResult> mergeSearchResults(
            List<SearchResult> textResults,
            List<SearchResult> imageResults,
            Float similarityThreshold) {

        // 简单的合并策略：合并去重
        Map<String, SearchResult> merged = new LinkedHashMap<>();

        for (SearchResult result : textResults) {
            if (result.getSimilarityScore() >= similarityThreshold) {
                merged.put(result.getChunkId(), result);
            }
        }

        for (SearchResult result : imageResults) {
            if (result.getSimilarityScore() >= similarityThreshold) {
                if (!merged.containsKey(result.getChunkId())) {
                    merged.put(result.getChunkId(), result);
                }
            }
        }

        return new ArrayList<>(merged.values());
    }
}