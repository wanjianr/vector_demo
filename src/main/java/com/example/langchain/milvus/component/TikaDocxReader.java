package com.example.langchain.milvus.component;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.microsoft.ooxml.OOXMLParser;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.SAXException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@Component
public class TikaDocxReader {

    private static final Logger log = LoggerFactory.getLogger(TikaDocxReader.class);
    private final Tika tika = new Tika();

    /**
     * 使用Tika读取docx
     */
    public String readDocxWithTika(InputStream is) throws IOException {
        try {
            // 方法1：简单的Tika解析
            String text = tika.parseToString(is);
            return text.trim();
        } catch (Exception e) {
            log.warn("简单Tika解析失败，尝试高级解析: {}", e.getMessage());

            // 方法2：高级Tika解析
            is.reset();
            return parseWithTikaAdvanced(is);
        }
    }

    /**
     * 高级Tika解析
     */
    private String parseWithTikaAdvanced(InputStream is) throws IOException {
        try {
            BodyContentHandler handler = new BodyContentHandler(-1); // -1表示无限制
            Metadata metadata = new Metadata();
            ParseContext context = new ParseContext();
            OOXMLParser parser = new OOXMLParser();

            parser.parse(is, handler, metadata, context);

            return handler.toString();
        } catch (SAXException | TikaException e) {
            throw new IOException("Tika解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 使用Tika提取文档元数据
     */
    public Map<String, String> extractMetadata(InputStream is) throws IOException {
        Map<String, String> metadata = new HashMap<>();

        try {
            Metadata tikaMetadata = new Metadata();
            BodyContentHandler handler = new BodyContentHandler(-1);
            ParseContext context = new ParseContext();
            OOXMLParser parser = new OOXMLParser();

            parser.parse(is, handler, tikaMetadata, context);

            // 提取元数据
            String[] names = tikaMetadata.names();
            for (String name : names) {
                metadata.put(name, tikaMetadata.get(name));
            }

        } catch (Exception e) {
            log.warn("提取元数据失败: {}", e.getMessage());
        }

        return metadata;
    }

    /**
     * 检测文档类型
     */
    public String detectFileType(InputStream is) throws IOException {
        try {
            String mimeType = tika.detect(is);
            is.reset();
            return mimeType;
        } catch (IOException e) {
            throw new IOException("检测文件类型失败: " + e.getMessage(), e);
        }
    }

    /**
     * 处理上传的文档文件
     */
    public DocumentContent processUploadedFile(MultipartFile file) throws IOException {
        String fileName = file.getOriginalFilename();
        String fileType = FilenameUtils.getExtension(fileName).toLowerCase();

        DocumentContent content = new DocumentContent();
        content.setFileName(fileName);
        content.setFileType(fileType);

        try (InputStream is = file.getInputStream()) {
            // 检测文件类型
            String mimeType = detectFileType(new BufferedInputStream(is));
            content.setMimeType(mimeType);

            is.reset();

            if (mimeType.contains("wordprocessingml") || "docx".equalsIgnoreCase(fileType)) {
                // 读取docx
                String text = readDocxWithTika(is);
                content.setContent(text);

                // 提取元数据
                is.reset();
                Map<String, String> metadata = extractMetadata(is);
                content.setMetadata(metadata);

            } else if (mimeType.contains("pdf")) {
                // 处理PDF
                String text = tika.parseToString(is);
                content.setContent(text);

            } else if (mimeType.contains("text")) {
                // 处理文本文件
                String text = IOUtils.toString(is, "UTF-8");
                content.setContent(text);

            } else {
                throw new IOException("不支持的文件类型: " + mimeType);
            }

        } catch (Exception e) {
            throw new IOException("处理文件失败: " + e.getMessage(), e);
        }

        return content;
    }

    /**
     * 文档内容类
     */
    public static class DocumentContent {
        private String fileName;
        private String fileType;
        private String mimeType;
        private String content;
        private Map<String, String> metadata = new HashMap<>();

        // getters and setters
        public String getFileName() { return fileName; }
        public void setFileName(String fileName) { this.fileName = fileName; }

        public String getFileType() { return fileType; }
        public void setFileType(String fileType) { this.fileType = fileType; }

        public String getMimeType() { return mimeType; }
        public void setMimeType(String mimeType) { this.mimeType = mimeType; }

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }

        public Map<String, String> getMetadata() { return metadata; }
        public void setMetadata(Map<String, String> metadata) {
            this.metadata = metadata;
        }
    }
}