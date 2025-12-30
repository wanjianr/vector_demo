package com.example.langchain.milvus.service;

import com.example.langchain.milvus.dto.DocumentImportRequest;
import com.example.langchain.milvus.dto.DocumentImportResult;
import com.example.langchain.milvus.utils.JsonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.MutationResult;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.CollectionSchemaParam;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.index.CreateIndexParam;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * MilvusServiceImpl - 增加插入前字段深度清理，避免 null 导致 Milvus 报错
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MilvusServiceImplV2 {

    private final MilvusServiceClient milvusClient;
    private final DocumentParserWithStructure documentParserWithStructure;
    private final ImageExtractorWithPosition imageExtractorWithPosition;

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
            DocumentParserWithStructure.DocumentContent docContent = documentParserWithStructure.parseDocumentWithStructure(file);

            // 3. 提取图片（并保存到 imageOutputDir）
            List<DocumentParserWithStructure.ImageInfo> images = new ArrayList<>();
            if (Boolean.TRUE.equals(request.getExtractImages())) {
                images = imageExtractorWithPosition.extractImages(file, imageOutputDir);
            }

            // 4. 文档分块
            Map<Integer, List<DocumentParserWithStructure.ImagePosition>> imagePositions = associateImagesWithDocument(
                    docContent.getStructure(), images, docContent.getParagraphs());

            // 6. 智能分块（保持文档结构）
            List<DocumentParserWithStructure.DocumentChunk> chunks = chunkDocumentIntelligently(
                    docContent, imagePositions, request.getChunkSize());

            List<DocumentParserWithStructure.TextEmbedding> embeddings = generateEmbeddingsForChunks(chunks);

            // 8. 准备Milvus数据
            List<InsertParam.Field> fields = prepareMilvusInsertFields(
                    chunks, embeddings, documentId, file.getOriginalFilename());

            // 逐个字段检查（可保留调试输出）
            for (InsertParam.Field field : fields) {
                System.out.println("\n--- 检查字段: " + field.getName() + " ---");

                List<?> values = field.getValues();
                if (values == null) {
                    System.out.println("⚠️ 字段值列表为 null");
                    continue;
                }

                for (int i = 0; i < values.size(); i++) {
                    Object value = values.get(i);

                    if (value == null) {
                        System.err.println("❌ 索引 " + i + ": NULL");
                        continue;
                    }

                    if (value instanceof String) {
                        String str = (String) value;
                        if (str == null) {
                            System.err.println("❌ 索引 " + i + ": NULL 字符串");
                        } else if (str.isEmpty()) {
                            System.out.println("⚠️ 索引 " + i + ": 空字符串");
                        } else {
                            // images 现在是逗号分隔的路径字符串（不要当作 JSON 解析）
                            if (FIELD_IMAGES.equals(field.getName())) {
                                // 安全打印前 200 字符以便调试（避免过长输出）
                                int len = str.length();
                                String sample = len > 200 ? str.substring(0, 200) + "..." : str;
                                System.out.println("ℹ️ 索引 " + i + ": images 字符串, 长度=" + len + ", 内容示例=" + sample);
                            }
                            // metadata 仍然当作 JSON 字符串检查
                            else if (FIELD_METADATA.equals(field.getName())) {
                                try {
                                    new ObjectMapper().readTree(str);
                                    System.out.println("✅ 索引 " + i + ": metadata 是有效 JSON, 长度: " + str.length());
                                } catch (Exception e) {
                                    System.out.println("⚠️ 索引 " + i + ": metadata 非法 JSON (" + e.getMessage() + ")");
                                }
                            } else {
                                // 其他普通字符串字段
                                System.out.println("ℹ️ 索引 " + i + ": 字符串, 长度=" + str.length());
                            }
                        }
                    } else if (value instanceof List) {
                        System.out.println("📊 索引 " + i + ": List, 大小: " + ((List<?>) value).size());
                    } else {
                        System.out.println("📄 索引 " + i + ": " + value.getClass().getSimpleName() + " = " + value);
                    }
                }
            }

            // 修复所有字段（浅层）
            List<InsertParam.Field> fixedFields = validateAndFixAllFields(fields);

            // 深度清理（特别是向量字段），避免 Milvus 客户端报错
            sanitizeFieldsBeforeInsert(fixedFields);

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


            // 9. 构建结果
            result.setSuccess(true);
            result.setChunkCount(chunks.size());
            result.setImageCount(images.size());
            result.setVectorCount(embeddings.size());


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
     * 深度清理字段：保证没有 null 的字符串/向量/嵌套 null，修复向量长度
     */
    @SuppressWarnings("unchecked")
    private void sanitizeFieldsBeforeInsert(List<InsertParam.Field> fields) {
        List<Float> zeroVector = Collections.nCopies(vectorDimension, 0f);

        for (int fi = 0; fi < fields.size(); fi++) {
            InsertParam.Field field = fields.get(fi);
            String name = field.getName();
            List<Object> values = new ArrayList<>();
            if (field.getValues() != null) {
                for (Object v : field.getValues()) {
                    values.add(v);
                }
            }

            // 处理向量字段（List<List<Float>>）
            if (FIELD_VECTOR.equals(name)) {
                List<List<Float>> newVectors = new ArrayList<>();
                for (Object v : values) {
                    if (v == null) {
                        newVectors.add(new ArrayList<>(zeroVector));
                        continue;
                    }
                    if (v instanceof List) {
                        List<?> inner = (List<?>) v;
                        boolean hasNull = inner.stream().anyMatch(Objects::isNull);
                        // 尝试把元素转为 Float
                        List<Float> vec = new ArrayList<>();
                        for (Object o : inner) {
                            if (o == null) {
                                vec.add(0f);
                            } else if (o instanceof Float) {
                                vec.add((Float) o);
                            } else if (o instanceof Double) {
                                vec.add(((Double) o).floatValue());
                            } else if (o instanceof Number) {
                                vec.add(((Number) o).floatValue());
                            } else {
                                // 无法识别的元素，视为 0
                                vec.add(0f);
                            }
                        }

                        // 如果长度不对，替换为 zeroVector
                        if (vec.size() != vectorDimension) {
                            newVectors.add(new ArrayList<>(zeroVector));
                        } else {
                            newVectors.add(vec);
                        }
                    } else {
                        // 非 List 类型 -> 使用 zero vector
                        newVectors.add(new ArrayList<>(zeroVector));
                    }
                }
                // 替换字段
                fields.set(fi, new InsertParam.Field(name, (List) newVectors));
                continue;
            }

            // 处理文本/JSON/路径字段，确保不为 null
            List<Object> newVals = new ArrayList<>();
            for (Object v : values) {
                if (v == null) {
                    newVals.add(getDefaultForField(name));
                    continue;
                }
                if (v instanceof String) {
                    String s = (String) v;
                    if (s == null || s.trim().isEmpty() || "null".equalsIgnoreCase(s.trim())) {
                        newVals.add(getDefaultForField(name));
                    } else {
                        newVals.add(s);
                    }
                    continue;
                }
                if (v instanceof List) {
                    // 清理 list 中的 null
                    List<?> list = (List<?>) v;
                    List<Object> cleaned = new ArrayList<>();
                    for (Object o : list) {
                        if (o == null) continue;
                        cleaned.add(o);
                    }
                    if (cleaned.isEmpty()) {
                        newVals.add(getDefaultForField(name));
                    } else {
                        newVals.add(cleaned);
                    }
                    continue;
                }
                // 其他类型直接加入
                newVals.add(v);
            }

            // 最后替换（保证没有 null）
            List<Object> safeVals = new ArrayList<>();
            for (Object o : newVals) {
                if (o == null) safeVals.add(getDefaultForField(name));
                else safeVals.add(o);
            }

            fields.set(fi, new InsertParam.Field(name, safeVals));
        }
    }

    /**
     * 修复所有字段（浅层）
     */
    private List<InsertParam.Field> validateAndFixAllFields(List<InsertParam.Field> fields) {
        List<InsertParam.Field> fixedFields = new ArrayList<>();

        for (InsertParam.Field field : fields) {
            String fieldName = field.getName();
            List<?> originalValues = field.getValues();
            List<Object> fixedValues = new ArrayList<>();

            if (originalValues == null) {
                // 替换为默认单值，避免 Milvus 接收 null 列表
                fixedValues.add(getDefaultForField(fieldName));
                fixedFields.add(new InsertParam.Field(fieldName, fixedValues));
                continue;
            }

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
            if (str == null || str.isEmpty() || "null".equalsIgnoreCase(str)) {
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
            if (list.isEmpty()) {
                return getDefaultForField(fieldName);
            }
            if (list.contains(null)) {
                // 清理list中的null
                List<Object> cleaned = new ArrayList<>();
                for (Object item : list) {
                    if (item != null) {
                        cleaned.add(item);
                    }
                }
                if (cleaned.isEmpty()) {
                    return getDefaultForField(fieldName);
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
        if (FIELD_METADATA.equals(fieldName)) {
            return "{}";
        } else if (FIELD_TEXT.equals(fieldName) ||
                FIELD_DOCUMENT_ID.equals(fieldName) ||
                FIELD_CHUNK_ID.equals(fieldName)) {
            return "";
        } else if (FIELD_CREATE_TIME.equals(fieldName)) {
            return System.currentTimeMillis();
        } else if (FIELD_VECTOR.equals(fieldName)) {
            return new ArrayList<Float>();
        } else if (FIELD_IMAGES.equals(fieldName)) {
            return ""; // images 现在为字符串（逗号分隔路径），默认空字符串更合适
        }
        return "";
    }

    private boolean isJsonField(String fieldName) {
        return FIELD_METADATA.equals(fieldName); // 仅 metadata 保持 JSON 语义

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
        fields.add(new InsertParam.Field(FIELD_VECTOR, vectors));

        // 文本字段
        List<String> texts = chunks.stream()
                .map(DocumentParserWithStructure.DocumentChunk::getText)
                .map(s -> s == null ? "" : s)
                .collect(Collectors.toList());
        fields.add(new InsertParam.Field(FIELD_TEXT, texts));

        // 元数据字段
        List<String> metadataList = chunks.stream()
                .map(chunk -> convertMetadataToString(chunk, documentId, fileName))
                .collect(Collectors.toList());
        fields.add(new InsertParam.Field(FIELD_METADATA, metadataList));

        // 4. images字段 - 现在保存为逗号分隔的文件路径字符串（兼容旧的 JSON 格式）
        List<String> imageInfoList = new ArrayList<>();
        for (DocumentParserWithStructure.DocumentChunk chunk : chunks) {
            String imagePathStr = convertImagesToJson(chunk);
            imageInfoList.add(imagePathStr);
        }
        fields.add(new InsertParam.Field(FIELD_IMAGES, imageInfoList));

        // 文档ID字段
        List<String> docIds = Collections.nCopies(chunks.size(), documentId == null ? "" : documentId);
        fields.add(new InsertParam.Field(FIELD_DOCUMENT_ID, docIds));

        // 分块ID字段（安全转换，避免 null.toString）
        List<String> chunkIdStr = chunks.stream()
                .map(chunk -> Objects.toString(chunk.getChunkId(), "0"))
                .collect(Collectors.toList());
        fields.add(new InsertParam.Field(FIELD_CHUNK_ID, chunkIdStr));

        // 元数据字段（已添加 above）
        // 创建时间字段
        List<Long> createTimes = chunks.stream()
                .map(chunk -> System.currentTimeMillis())
                .collect(Collectors.toList());
        fields.add(new InsertParam.Field(FIELD_CREATE_TIME, createTimes));

        return fields;
    }

    private String convertImagesToJson(DocumentParserWithStructure.DocumentChunk chunk) {
        if (chunk == null || chunk.getImages() == null || chunk.getImages().isEmpty()) {
            return "";  // 直接返回空对象
        }

        List<Map<String, Object>> imageInfoList = new ArrayList<>();

        for (DocumentParserWithStructure.ImageInfo image : chunk.getImages()) {
            if (image == null) continue;

            Map<String, Object> imageInfo = new HashMap<>();

            // 确保所有字符串字段有值
            imageInfo.put("file_name",
                    image.getFileName() != null ? image.getFileName() : "");
            imageInfo.put("file_path",
                    image.getFilePath() != null ? image.getFilePath() :
                            (image.getFileName() != null ? Paths.get(imageOutputDir, image.getFileName()).toString() : ""));

            imageInfo.put("format",
                    image.getFormat() != null ? image.getFormat() : "");

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
            return "";
        }

        try {
            return JsonUtils.toJson(imageInfoList);
        } catch (Exception e) {
            return "";
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
        metadata.put("has_images", chunk.getImages() != null && !chunk.getImages().isEmpty());

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

    private List<DocumentParserWithStructure.TextEmbedding> generateEmbeddingsForChunks(List<DocumentParserWithStructure.DocumentChunk> chunks) {
        List<DocumentParserWithStructure.TextEmbedding> embeddings = new ArrayList<>();

        for (DocumentParserWithStructure.DocumentChunk chunk : chunks) {
            try {
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

            fields.add(FieldType.newBuilder()
                    .withName(FIELD_IMAGES)
                    .withDataType(DataType.VarChar)
                    .withMaxLength(65535)
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
            return IndexType.IVF_FLAT;  // 默认
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
                log.warn("未知的度量类型: {}, 使用默认值 HNSW", indexTypeStr);
                return IndexType.HNSW;
        }
    }

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

    // ========== 私有方法 ==========

    private void ensureCollectionExists(String collectionName) throws Exception {
        if (!hasCollection(collectionName)) {
            createCollection(collectionName);
        }
    }

    private String generateDocumentId(MultipartFile file) {
        return file.getOriginalFilename() + "_" + UUID.randomUUID().toString().substring(0, 8);
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
}
