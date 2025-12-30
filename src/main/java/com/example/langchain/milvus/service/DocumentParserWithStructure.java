package com.example.langchain.milvus.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ooxml.POIXMLDocumentPart;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackageRelationship;
import org.apache.poi.xwpf.usermodel.UnderlinePatterns;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class DocumentParserWithStructure {

    public DocumentContent parseDocumentWithStructure(MultipartFile file) throws Exception {
        String fileName = file.getOriginalFilename().toLowerCase();

        if (fileName.endsWith(".docx")) {
            return parseDocxWithStructure(file);
        }
        else {
            throw new UnsupportedOperationException("不支持的文件格式: " + fileName);
        }
    }

    /**
     * 检查图片是否在段落中
     */
    private boolean isPictureInParagraph(XWPFPictureData picData, List<XWPFParagraph> paragraphs) {
        try {
            // 获取图片的关系ID
            POIXMLDocumentPart part = (POIXMLDocumentPart) picData;
            PackagePart packagePart = part.getPackagePart();

            // 获取关系ID
            String picRelationshipId = null;
            for (PackageRelationship rel : packagePart.getRelationships()) {
                picRelationshipId = rel.getId();
                break;
            }

            if (picRelationshipId == null) {
                return false;
            }

            // 在段落中查找此关系ID
            for (XWPFParagraph paragraph : paragraphs) {
                try {
                    CTP ctP = paragraph.getCTP();
                    String xml = ctP.toString();

                    // 在XML中查找关系ID
                    if (xml.contains("r:embed=\"" + picRelationshipId + "\"") ||
                            xml.contains("r:link=\"" + picRelationshipId + "\"") ||
                            xml.contains("r:id=\"" + picRelationshipId + "\"")) {
                        return true;
                    }
                } catch (Exception e) {
                    // 忽略此段落的错误
                }
            }
        } catch (Exception e) {
            log.debug("检查图片位置失败: {}", e.getMessage());
        }

        return false;
    }

    /**
     * 从run中提取图片
     */
    private List<ImageInfo> extractImagesFromRuns(XWPFParagraph paragraph, int paraIndex, int charPosition) {
        List<ImageInfo> images = new ArrayList<>();

        try {
            List<XWPFRun> runs = paragraph.getRuns();
            if (runs == null) {
                return images;
            }

            for (int runIndex = 0; runIndex < runs.size(); runIndex++) {
                XWPFRun run = runs.get(runIndex);

                // 获取嵌入式图片列表
                try {
                    // 使用getEmbeddedPictures()方法
                    List<XWPFPicture> embeddedPictures = run.getEmbeddedPictures();
                    if (embeddedPictures != null && !embeddedPictures.isEmpty()) {
                        for (XWPFPicture picture : embeddedPictures) {
                            XWPFPictureData pictureData = picture.getPictureData();
                            if (pictureData != null && pictureData.getData() != null) {
                                ImageInfo imageInfo = new ImageInfo();
                                imageInfo.setIndex(images.size());
                                imageInfo.setData(pictureData.getData());
                                imageInfo.setFormat(pictureData.suggestFileExtension());

                                // 获取文件名
                                String fileName = pictureData.getFileName();
                                if (fileName == null || fileName.isEmpty()) {
                                    fileName = "para_" + paraIndex + "_run_" + runIndex +
                                            "_img_" + images.size() + "." + pictureData.suggestFileExtension();
                                }
                                imageInfo.setFileName(fileName);

                                ImagePosition position = new ImagePosition();
                                position.setParagraphIndex(paraIndex);
                                position.setParagraphId(paraIndex);
                                position.setRunIndex(runIndex);
                                position.setCharPosition(charPosition);

                                String runText = run.getText(0);
                                if (runText != null) {
                                    position.setRunText(runText);
                                }

                                imageInfo.setPosition(position);
                                images.add(imageInfo);

                                log.info("从run获取到图片: 段落={}, run={}, 图片={}, 大小={}字节",
                                        paraIndex, runIndex, fileName, pictureData.getData().length);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("获取run图片失败: {}", e.getMessage());
                }
            }

        } catch (Exception e) {
            log.warn("从run提取图片失败", e);
        }

        return images;
    }

    private DocumentContent parseDocxWithStructure(MultipartFile file) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(file.getInputStream())) {
            DocumentContent content = new DocumentContent();
            List<Paragraph> paragraphs = new ArrayList<>();
            List<ImageInfo> allImages = new ArrayList<>();

            StringBuilder fullText = new StringBuilder();
            int charPosition = 0;
            int paraIndex = 0;

            // 1. 首先获取文档中的所有图片
            List<XWPFPictureData> allPictureData = doc.getAllPictures();
            log.info("文档包含 {} 张图片", allPictureData.size());

            // 2. 按顺序编号图片
            Map<Integer, XWPFPictureData> pictureMap = new HashMap<>();
            for (int i = 0; i < allPictureData.size(); i++) {
                pictureMap.put(i, allPictureData.get(i));
            }

            // 3. 解析段落
            List<XWPFParagraph> allParagraphs = doc.getParagraphs();

            for (int i = 0; i < allParagraphs.size(); i++) {
                XWPFParagraph xwpfPara = allParagraphs.get(i);
                String text = xwpfPara.getText();
                if (text == null) {
                    text = "";
                }
                text = text.trim();

                Paragraph para = new Paragraph();
                para.setId(paraIndex++);
                para.setText(text);
                para.setStartPos(charPosition);
                para.setEndPos(charPosition + text.length());

                // 检测段落类型
                para.setType(detectParagraphType(xwpfPara, text));
                para.setLevel(detectHeadingLevel(xwpfPara, text));
                para.setStyle(extractParagraphStyle(xwpfPara));
                para.setRuns(extractRunInfo(xwpfPara));

                // 提取当前段落中的图片
                List<ImageInfo> paraImages = new ArrayList<>();

                // 方法1: 使用正确的POI API提取段落中的图片
                try {
                    List<XWPFParagraph> pictureParagraphs = new ArrayList<>();
                    pictureParagraphs.add(xwpfPara);

                    // 遍历所有图片，检查是否在此段落中
                    for (Map.Entry<Integer, XWPFPictureData> entry : pictureMap.entrySet()) {
                        Integer picIndex = entry.getKey();
                        XWPFPictureData picData = entry.getValue();

                        // 检查图片是否在此段落中
                        boolean pictureInParagraph = isPictureInParagraph(picData, pictureParagraphs);
                        if (pictureInParagraph) {
                            ImageInfo imageInfo = new ImageInfo();
                            imageInfo.setIndex(picIndex);
                            imageInfo.setData(picData.getData());
                            imageInfo.setFormat(picData.suggestFileExtension());

                            // 获取文件名
                            String fileName = picData.getFileName();
                            if (fileName == null || fileName.isEmpty()) {
                                fileName = "image_" + (picIndex + 1) + "." + picData.suggestFileExtension();
                            }
                            imageInfo.setFileName(fileName);

                            ImagePosition position = new ImagePosition();
                            position.setParagraphIndex(i);
                            position.setParagraphId(i);
                            position.setCharPosition(charPosition);
                            position.setRunIndex(-1);

                            imageInfo.setPosition(position);
                            paraImages.add(imageInfo);

                            log.debug("段落 {} 找到图片: {}", i, fileName);
                        }
                    }
                } catch (Exception e) {
                    log.warn("检查图片位置失败: {}", e.getMessage());
                }

                // 方法2: 遍历run获取嵌入式图片
                if (paraImages.isEmpty()) {
                    paraImages = extractImagesFromRuns(xwpfPara, i, charPosition);
                }

                para.setImages(paraImages);
                allImages.addAll(paraImages);

                paragraphs.add(para);
                fullText.append(text).append("\n");
                charPosition = fullText.length();
            }

            // 4. 处理剩余的未分配图片
            if (allImages.size() < allPictureData.size()) {
                for (int i = allImages.size(); i < allPictureData.size(); i++) {
                    XWPFPictureData picData = allPictureData.get(i);
                    ImageInfo imageInfo = new ImageInfo();
                    imageInfo.setIndex(i);
                    imageInfo.setData(picData.getData());
                    imageInfo.setFormat(picData.suggestFileExtension());

                    String fileName = picData.getFileName();
                    if (fileName == null || fileName.isEmpty()) {
                        fileName = "image_" + (i + 1) + "." + picData.suggestFileExtension();
                    }
                    imageInfo.setFileName(fileName);

                    ImagePosition position = new ImagePosition();
                    position.setParagraphIndex(-1); // 未分配
                    position.setCharPosition(fullText.length());

                    imageInfo.setPosition(position);
                    allImages.add(imageInfo);
                }
            }

            content.setText(fullText.toString());
            content.setParagraphs(paragraphs);
            content.setImages(allImages);

            log.info("解析完成: 段落数={}, 图片数={}", paragraphs.size(), allImages.size());
            return content;
        }
    }

    private String detectParagraphType(XWPFParagraph para, String text) {
        if (text.trim().length() < 150 && (text.trim().endsWith(":") || text.trim().endsWith("："))) {
            return "heading";
        }

        if (text.trim().matches("^[0-9]+[、.]\\s.*") ||
                text.trim().matches("^[一二三四五六七八九十]+[、.]\\s.*")) {
            return "list_item";
        }

        String style = para.getStyle();
        if (style != null) {
            return "heading";
        }

        return "normal";
    }

    private int detectHeadingLevel(XWPFParagraph para, String text) {
        String style = para.getStyle();
        if (style != null) {
            return Integer.parseInt(style) - 1;
        }

        String trimmed = text.trim();
        if (trimmed.matches("^第[一二三四五六七八九十]+章.*")) {
            return 1;
        } else if (trimmed.matches("^[0-9]+\\.[0-9]+\\s.*")) {
            return 2;
        } else if (trimmed.matches("^[0-9]+\\.[0-9]+\\.[0-9]+\\s.*")) {
            return 3;
        }

        return 0;
    }

    private Map<String, Object> extractParagraphStyle(XWPFParagraph para) {
        Map<String, Object> style = new HashMap<>();

        if (para.getStyle() != null) {
            style.put("style_name", para.getStyle());
        }

        if (para.getAlignment() != null) {
            style.put("alignment", para.getAlignment().name());
        }

        if (para.getIndentationFirstLine() > 0) {
            style.put("first_line_indent", para.getIndentationFirstLine());
        }

        if (para.getIndentationLeft() > 0) {
            style.put("left_indent", para.getIndentationLeft());
        }

        if (para.getSpacingBefore() > 0) {
            style.put("spacing_before", para.getSpacingBefore());
        }

        if (para.getSpacingAfter() > 0) {
            style.put("spacing_after", para.getSpacingAfter());
        }

        if (para.getSpacingBetween() > 0) {
            style.put("line_spacing", para.getSpacingBetween());
        }

        return style;
    }

    private List<RunInfo> extractRunInfo(XWPFParagraph para) {
        List<RunInfo> runs = new ArrayList<>();
        List<XWPFRun> xwpfRuns = para.getRuns();

        for (int i = 0; i < xwpfRuns.size(); i++) {
            XWPFRun run = xwpfRuns.get(i);
            RunInfo runInfo = new RunInfo();
            runInfo.setIndex(i);
            runInfo.setText(run.getText(0));
            runInfo.setFontFamily(run.getFontFamily());
            runInfo.setFontSize(run.getFontSize());
            runInfo.setBold(run.isBold());
            runInfo.setItalic(run.isItalic());
            runInfo.setUnderlined(run.getUnderline() != UnderlinePatterns.NONE);
            runs.add(runInfo);
        }

        return runs;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DocumentContent {
        private String text;
        private List<Paragraph> paragraphs;
        private List<ImageInfo> images;
        private DocumentStructure structure;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DocumentStructure {
        private List<Paragraph> paragraphs = new ArrayList<>();
        private Map<String, Object> properties = new HashMap<>();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Paragraph {
        private Integer id;
        private String text;
        private Integer startPos;
        private Integer endPos;
        private String type = "normal";
        private Integer level = 0;
        private Map<String, Object> style = new HashMap<>();
        private List<RunInfo> runs = new ArrayList<>();
        private List<ImageInfo> images = new ArrayList<>();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RunInfo {
        private Integer index;
        private String text;
        private String fontFamily;
        private Integer fontSize;
        private Boolean bold = false;
        private Boolean italic = false;
        private Boolean underlined = false;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImageInfo {
        private Integer index;
        @JsonProperty("file_name")
        private String fileName;
        @JsonProperty("file_path")
        private String filePath;
        private String format;
        private byte[] data;
        private ImagePosition position;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImagePosition {
        @JsonProperty("paragraph_index")
        private Integer paragraphIndex;
        private Integer paragraphId;
        private Integer runIndex;
        @JsonProperty("char_position")
        private Integer charPosition;
        @JsonProperty("paragraph_text")
        private String paragraphText;
        private String context;
        private String runText;
        private Integer runLength;
        private Integer offsetInRun;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DocumentChunk {
        private Integer chunkId;
        private String text;
        private List<ImageInfo> images = new ArrayList<>();
        private Integer startParagraphIndex;
        private Integer endParagraphIndex;
        private Integer wordCount;
        private Integer charCount;
        private Map<String, Object> metadata = new HashMap<>();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TextEmbedding {
        private String text;
        private List<Float> vector;
        private Integer chunkId;
    }
}
