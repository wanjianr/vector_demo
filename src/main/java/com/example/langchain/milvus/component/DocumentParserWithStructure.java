package com.example.langchain.milvus.component;

import com.example.langchain.milvus.enums.ChunkStrategy;
import com.example.langchain.milvus.utils.DocumentParser;
import lombok.AllArgsConstructor;
import lombok.Builder;
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
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;
import org.openxmlformats.schemas.drawingml.x2006.main.CTGraphicalObject;
import org.openxmlformats.schemas.drawingml.x2006.main.CTGraphicalObjectData;
import org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.CTInline;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTDrawing;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPicture;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.xml.namespace.QName;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@Slf4j
public class DocumentParserWithStructure {

    public DocumentContent parseDocumentWithStructure(MultipartFile file) throws Exception {
        String fileName = file.getOriginalFilename().toLowerCase();

        if (fileName.endsWith(".docx")) {
            return parseDocxWithStructure(file);
        }
//        else if (fileName.endsWith(".pdf")) {
//            return parsePdfWithStructure(file);
//        } else if (fileName.endsWith(".txt") || fileName.endsWith(".md")) {
//            return parseTextWithStructure(file);
//        }
        else {
            throw new UnsupportedOperationException("不支持的文件格式: " + fileName);
        }
    }
    // 增强的提取图片方法
    private Map<String, XWPFPictureData> extractAllPicturesEnhanced(XWPFDocument doc) {
        Map<String, XWPFPictureData> pictures = new HashMap<>();

        try {
            // 方法1：通过文档关系
            for (POIXMLDocumentPart part : doc.getRelations()) {
                if (part instanceof XWPFPictureData) {
                    XWPFPictureData picData = (XWPFPictureData) part;
                    String fileName = picData.getFileName();
                    pictures.put(fileName, picData);
                }
            }

            // 方法2：通过getAllPictures
            List<XWPFPictureData> allPics = doc.getAllPictures();
            for (int i = 0; i < allPics.size(); i++) {
                XWPFPictureData picData = allPics.get(i);
                String fileName = picData.getFileName();
                if (fileName == null || fileName.isEmpty()) {
                    fileName = "image" + (i + 1);
                }

                // 尝试提取关系ID
                try {
                    POIXMLDocumentPart part = (POIXMLDocumentPart) picData;
                    PackagePart packagePart = part.getPackagePart();
                    for (PackageRelationship rel : packagePart.getRelationships()) {
                        pictures.put(rel.getId(), picData);
                    }
                } catch (Exception e) {
                    // 如果提取关系失败，使用文件名
                    pictures.put(fileName, picData);
                }
            }

        } catch (Exception e) {
            log.warn("提取图片关系失败: {}", e.getMessage());
        }

        return pictures;
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

//                if (text.isEmpty()) {
//                    continue;
//                }

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

                // 方法3: 简单策略 - 按图片顺序分配
//                if (paraImages.isEmpty() && allImages.size() < allPictureData.size()) {
//                    int nextPicIndex = allImages.size();
//                    if (nextPicIndex < allPictureData.size()) {
//                        XWPFPictureData picData = allPictureData.get(nextPicIndex);
//                        ImageInfo imageInfo = new ImageInfo();
//                        imageInfo.setIndex(nextPicIndex);
//                        imageInfo.setData(picData.getData());
//                        imageInfo.setFormat(picData.suggestFileExtension());
//
//                        String fileName = picData.getFileName();
//                        if (fileName == null || fileName.isEmpty()) {
//                            fileName = "image_" + (nextPicIndex + 1) + "." + picData.suggestFileExtension();
//                        }
//                        imageInfo.setFileName(fileName);
//
//                        ImagePosition position = new ImagePosition();
//                        position.setParagraphIndex(i);
//                        position.setParagraphId(i);
//                        position.setCharPosition(charPosition);
//
//                        imageInfo.setPosition(position);
//                        paraImages.add(imageInfo);
//
//                        log.debug("为段落 {} 分配图片: {}", i, fileName);
//                    }
//                }
//
//                // 方法3: 简单策略 - 按图片顺序分配
//                if (paraImages.isEmpty() && allImages.size() < allPictureData.size()) {
//                    int nextPicIndex = allImages.size();
//                    if (nextPicIndex < allPictureData.size()) {
//                        XWPFPictureData picData = allPictureData.get(nextPicIndex);
//                        ImageInfo imageInfo = new ImageInfo();
//                        imageInfo.setIndex(nextPicIndex);
//                        imageInfo.setData(picData.getData());
//                        imageInfo.setFormat(picData.suggestFileExtension());
//
//                        String fileName = picData.getFileName();
//                        if (fileName == null || fileName.isEmpty()) {
//                            fileName = "image_" + (nextPicIndex + 1) + "." + picData.suggestFileExtension();
//                        }
//                        imageInfo.setFileName(fileName);
//
//                        ImagePosition position = new ImagePosition();
//                        position.setParagraphIndex(i);
//                        position.setParagraphId(i);
//                        position.setCharPosition(charPosition);
//
//                        imageInfo.setPosition(position);
//                        paraImages.add(imageInfo);
//
//                        log.debug("为段落 {} 分配图片: {}", i, fileName);
//                    }
//                }

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

    /**
     * 提取文档中的所有图片
     */
    private Map<String, XWPFPictureData> extractAllPictures(XWPFDocument doc) {
        Map<String, XWPFPictureData> pictures = new HashMap<>();

        try {
            // 方法1：通过文档的关系获取所有图片
            List<XWPFPictureData> allPictureData = doc.getAllPictures();

            for (XWPFPictureData pictureData : allPictureData) {
                try {
                    // 获取关系ID
                    POIXMLDocumentPart part = (POIXMLDocumentPart) pictureData;
                    PackagePart packagePart = part.getPackagePart();
                    String relationshipId = null;

                    // 获取关系
                    for (PackageRelationship rel : packagePart.getRelationships()) {
                        if (rel.getRelationshipType().contains("image")) {
                            relationshipId = rel.getId();
                            break;
                        }
                    }

                    if (relationshipId != null && !relationshipId.isEmpty()) {
                        pictures.put(relationshipId, pictureData);
                        log.debug("找到图片关系: id={}, fileName={}", relationshipId, pictureData.getFileName());
                    } else {
                        // 如果没有关系ID，尝试从文件名提取
                        String fileName = pictureData.getFileName();
                        if (fileName != null && !fileName.isEmpty()) {
                            // 提取可能的ID
                            String extractedId = extractRelationshipIdFromFileName(fileName);
                            if (extractedId != null) {
                                pictures.put(extractedId, pictureData);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("处理图片关系失败: {}", e.getMessage());
                }
            }

            log.info("提取到 {} 张图片，映射到 {} 个关系", allPictureData.size(), pictures.size());

        } catch (Exception e) {
            log.warn("提取图片失败: {}", e.getMessage());
        }

        return pictures;
    }

    // 修改2：从文件名提取关系ID
    private String extractRelationshipIdFromFileName(String fileName) {
        try {
            // 匹配格式：rId1.png, image1.jpeg 等
            Pattern pattern = Pattern.compile("(rId\\d+|image\\d+).*", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(fileName);
            if (matcher.find()) {
                String idPart = matcher.group(1);
                if (idPart.startsWith("image")) {
                    // 转换 image1 -> rId1
                    return "rId" + idPart.replace("image", "");
                }
                return idPart;
            }

            // 从路径中提取
            if (fileName.contains("/media/")) {
                String name = fileName.substring(fileName.lastIndexOf("/") + 1);
                int dotIndex = name.lastIndexOf(".");
                if (dotIndex > 0) {
                    name = name.substring(0, dotIndex);
                }
                if (name.startsWith("image")) {
                    return "rId" + name.replace("image", "");
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    private List<ImageInfo> extractImagesFromParagraphSimple(XWPFParagraph paragraph,
                                                             Map<String, XWPFPictureData> pictureIdMap,
                                                             int paraIndex, int charPosition) {
        List<ImageInfo> images = new ArrayList<>();

        try {
            // 方法A：直接遍历runs获取嵌入式图片
            List<XWPFRun> runs = paragraph.getRuns();
            if (runs != null && !runs.isEmpty()) {
                for (int i = 0; i < runs.size(); i++) {
                    XWPFRun run = runs.get(i);
                    if (run != null) {
                        // 获取嵌入式图片
                        List<XWPFPicture> embeddedPics = run.getEmbeddedPictures();
                        if (embeddedPics != null && !embeddedPics.isEmpty()) {
                            for (XWPFPicture picture : embeddedPics) {
                                try {
                                    XWPFPictureData picData = picture.getPictureData();
                                    if (picData != null) {
                                        ImageInfo imageInfo = new ImageInfo();
                                        imageInfo.setIndex(images.size());
                                        imageInfo.setData(picData.getData());
                                        imageInfo.setFormat(picData.suggestFileExtension());
                                        imageInfo.setFileName(picData.getFileName());

                                        ImagePosition position = new ImagePosition();
                                        position.setParagraphIndex(paraIndex);
                                        position.setRunIndex(i);
                                        position.setCharPosition(charPosition);
                                        imageInfo.setPosition(position);

                                        images.add(imageInfo);
                                        log.debug("从run直接获取图片: index={}, size={} bytes",
                                                images.size()-1, picData.getData().length);
                                    }
                                } catch (Exception e) {
                                    log.warn("处理run图片失败", e);
                                }
                            }
                        }
                    }
                }
            }

            // 方法B：如果没找到，尝试XML解析
            if (images.isEmpty()) {
                try {
                    CTP ctP = paragraph.getCTP();
                    String xml = ctP.toString();
                    images.addAll(extractImagesFromXmlV5(xml, pictureIdMap, paraIndex, charPosition, images.size()));
                } catch (Exception e) {
                    log.debug("XML解析失败: {}", e.getMessage());
                }
            }

        } catch (Exception e) {
            log.warn("提取段落图片失败: paraIndex={}", paraIndex, e);
        }

        return images;
    }

    /**
     * 提取段落中的图片
     */
    private List<ImageInfo> extractImagesFromParagraph(XWPFParagraph paragraph,
                                                       Map<String, XWPFPictureData> pictureIdMap,
                                                       int paraIndex, int charPosition) {
        List<ImageInfo> images = new ArrayList<>();

        try {
            // 方法1: 通过XML解析
            CTP ctP = paragraph.getCTP();
            String xml = ctP.toString();
            images.addAll(extractImagesFromXmlV5(xml, pictureIdMap, paraIndex, charPosition, images.size()));

            // 方法2: 通过drawing元素
            if (images.isEmpty()) {
                images.addAll(extractImagesFromCTPV5(ctP, pictureIdMap, paraIndex, charPosition, images.size()));
            }

            // 方法3: 通过runs
            if (images.isEmpty()) {
                images.addAll(extractImagesFromRunsV5(paragraph, paraIndex, charPosition));
            }

        } catch (Exception e) {
            log.warn("提取段落图片失败: paraIndex={}", paraIndex, e);
        }

        return images;
    }

    /**
     * 从XML提取图片
     */
    private List<ImageInfo> extractImagesFromXmlV5(String xml,
                                                   Map<String, XWPFPictureData> pictureIdMap,
                                                   int paraIndex, int charPosition,
                                                   int startIndex) {
        List<ImageInfo> images = new ArrayList<>();
        int currentIndex = startIndex;

        try {
            // 先打印XML片段用于调试
            if (log.isDebugEnabled()) {
                log.debug("解析XML段落: \n{}", xml);
            }

            // 查找embed属性
            Pattern pattern = Pattern.compile("r:embed=\"([^\"]+)\"");
            Matcher matcher = pattern.matcher(xml);

            int foundCount = 0;
            while (matcher.find()) {
                String embedId = matcher.group(1);
                foundCount++;
                XWPFPictureData picData = findPictureById(embedId, pictureIdMap);

                if (picData != null) {
                    ImageInfo imageInfo = createImageInfoV5(picData, paraIndex, charPosition, currentIndex++);
                    images.add(imageInfo);
                    log.debug("从XML找到图片: embedId={}, fileName={}", embedId, picData.getFileName());
                } else {
                    log.debug("未找到图片: embedId={}, 映射表: {}", embedId, pictureIdMap.keySet());

                    // 如果没找到，尝试从所有图片中按顺序获取
                    if (!pictureIdMap.isEmpty()) {
                        List<XWPFPictureData> picList = new ArrayList<>(pictureIdMap.values());
                        int picIndex = extractNumberFromId(embedId);
                        if (picIndex > 0 && picIndex <= picList.size()) {
                            picData = picList.get(picIndex - 1);
                            ImageInfo imageInfo = createImageInfoV5(picData, paraIndex, charPosition, currentIndex++);
                            images.add(imageInfo);
                            log.debug("通过序号找到图片: embedId={}, index={}", embedId, picIndex);
                        }
                    }
                }
            }

            log.debug("在XML中找到 {} 个embed属性，成功匹配 {} 张图片", foundCount, images.size());

            // 查找blip元素
            if (images.isEmpty()) {
                Pattern blipPattern = Pattern.compile("<a:blip[^>]*r:embed=\"([^\"]+)\"[^>]*/?>", Pattern.DOTALL);
                Matcher blipMatcher = blipPattern.matcher(xml);

                while (blipMatcher.find()) {
                    String embedId = blipMatcher.group(1);
                    XWPFPictureData picData = findPictureById(embedId, pictureIdMap);

                    if (picData != null) {
                        ImageInfo imageInfo = createImageInfoV5(picData, paraIndex, charPosition, currentIndex++);
                        images.add(imageInfo);
                    }
                }
            }

        } catch (Exception e) {
            log.warn("从XML提取图片失败", e);
        }

        return images;
    }

    // 提取ID中的数字
    private int extractNumberFromId(String id) {
        try {
            Pattern pattern = Pattern.compile("\\d+");
            Matcher matcher = pattern.matcher(id);
            if (matcher.find()) {
                return Integer.parseInt(matcher.group());
            }
        } catch (Exception e) {
            // ignore
        }
        return 0;
    }

    /**
     * 从CTP提取图片
     */
    private List<ImageInfo> extractImagesFromCTPV5(CTP ctP,
                                                   Map<String, XWPFPictureData> pictureIdMap,
                                                   int paraIndex, int charPosition,
                                                   int startIndex) {
        List<ImageInfo> images = new ArrayList<>();

        try {
            XmlCursor cursor = ctP.newCursor();
            try {
                // 查找drawing元素
                if (cursor.toChild("w:drawing")) {
                    // 遍历子元素查找图片
                    do {
                        XmlObject obj = cursor.getObject();
                        images.addAll(extractFromDrawingObject(obj, pictureIdMap, paraIndex, charPosition, images.size()));
                    } while (cursor.toNextSibling("w:drawing"));
                }

                // 查找pict元素（旧格式）
                cursor.toStartDoc();
                if (cursor.toChild("w:pict")) {
                    do {
                        XmlObject obj = cursor.getObject();
                        images.addAll(extractFromPictObject(obj, pictureIdMap, paraIndex, charPosition, images.size()));
                    } while (cursor.toNextSibling("w:pict"));
                }

            } finally {
                cursor.dispose();
            }

        } catch (Exception e) {
            log.warn("通过Cursor解析CTP失败", e);
        }

        return images;
    }

    /**
     * 从pict对象提取图片（旧格式）
     */
    private List<ImageInfo> extractFromPictObject(XmlObject pictObj,
                                                  Map<String, XWPFPictureData> pictureIdMap,
                                                  int paraIndex, int charPosition,
                                                  int startIndex) {
        List<ImageInfo> images = new ArrayList<>();
        int currentIndex = startIndex;

        try {
            XmlCursor cursor = pictObj.newCursor();
            try {
                // 查找shape元素
                if (cursor.toChild("v:shape")) {
                    do {
                        // 查找imagedata元素
                        if (cursor.toChild("v:imagedata")) {
                            String relationId = cursor.getAttributeText(new QName("r:id"));
                            if (relationId != null) {
                                XWPFPictureData picData = findPictureInMap(relationId, pictureIdMap);
                                if (picData != null) {
                                    ImageInfo imageInfo = createImageInfoPOI523(picData, paraIndex, charPosition, currentIndex++);
                                    images.add(imageInfo);
                                }
                            }
                        }
                        cursor.toParent();
                    } while (cursor.toNextSibling("v:shape"));
                }
            } finally {
                cursor.dispose();
            }
        } catch (Exception e) {
            log.warn("从pict对象提取图片失败", e);
        }

        return images;
    }

    /**
     * 从drawing对象提取图片
     */
    private List<ImageInfo> extractFromDrawingObject(XmlObject drawingObj,
                                                     Map<String, XWPFPictureData> pictureIdMap,
                                                     int paraIndex, int charPosition,
                                                     int startIndex) {
        List<ImageInfo> images = new ArrayList<>();
        int currentIndex = startIndex;

        try {
            XmlCursor cursor = drawingObj.newCursor();
            try {
                // 查找inline元素
                if (cursor.toChild("wp:inline")) {
                    do {
                        // 查找graphicData
                        if (cursor.toChild("a:graphic")) {
                            cursor.push();
                            if (cursor.toChild("a:graphicData")) {
                                // 查找pic元素
                                if (cursor.toChild("pic:pic")) {
                                    String embedId = findEmbedIdInPic(cursor);
                                    if (embedId != null) {
                                        XWPFPictureData picData = findPictureInMap(embedId, pictureIdMap);
                                        if (picData != null) {
                                            ImageInfo imageInfo = createImageInfoPOI523(picData, paraIndex, charPosition, currentIndex++);
                                            images.add(imageInfo);
                                        }
                                    }
                                }
                            }
                            cursor.pop();
                        }
                        cursor.toParent();
                    } while (cursor.toNextSibling("wp:inline"));
                }

            } finally {
                cursor.dispose();
            }

        } catch (Exception e) {
            log.warn("从drawing对象提取图片失败", e);
        }

        return images;
    }

    /**
     * 在pic元素中查找embed ID
     */
    private String findEmbedIdInPic(XmlCursor cursor) {
        try {
            // 查找blipFill
            if (cursor.toChild("pic:blipFill")) {
                if (cursor.toChild("a:blip")) {
                    String embedId = cursor.getAttributeText(new QName(
                            "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
                            "embed"
                    ));
                    cursor.toParent();
                    return embedId;
                }
                cursor.toParent();
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    /**
     * 在映射中查找图片
     */
    private XWPFPictureData findPictureInMap(String id, Map<String, XWPFPictureData> pictureIdMap) {
        // 直接匹配
        if (pictureIdMap.containsKey(id)) {
            return pictureIdMap.get(id);
        }

        // 尝试匹配rId格式
        if (id.startsWith("rId")) {
            // 移除rId前缀，匹配数字
            String number = id.substring(3);
            for (Map.Entry<String, XWPFPictureData> entry : pictureIdMap.entrySet()) {
                String key = entry.getKey();
                if (key.contains(number)) {
                    return entry.getValue();
                }
            }
        }

        return null;
    }

    /**
     * 创建图片信息
     */
    private ImageInfo createImageInfoPOI523(XWPFPictureData picData,
                                            int paraIndex, int charPosition,
                                            int imageIndex) {
        ImageInfo imageInfo = new ImageInfo();
        imageInfo.setIndex(imageIndex);
        imageInfo.setData(picData.getData());

        // 格式
        String format = picData.suggestFileExtension();
        if (format == null || format.isEmpty()) {
            format = "png";
        }
        imageInfo.setFormat(format);

        // 文件名
        try {
            String fileName = picData.getFileName();
            if (fileName == null || fileName.trim().isEmpty()) {
                fileName = "img_" + paraIndex + "_" + imageIndex + "." + format;
            }
            imageInfo.setFileName(fileName);
        } catch (Exception e) {
            imageInfo.setFileName("img_" + paraIndex + "_" + imageIndex + "." + format);
        }

        // 位置信息
        ImagePosition position = new ImagePosition();
        position.setParagraphIndex(paraIndex);
        position.setCharPosition(charPosition);
        imageInfo.setPosition(position);

        return imageInfo;
    }

    /**
     * 从runs提取图片
     */
    private List<ImageInfo> extractImagesFromRunsV5(XWPFParagraph paragraph,
                                                    int paraIndex, int charPosition) {
        List<ImageInfo> images = new ArrayList<>();

        try {
            List<XWPFRun> runs = paragraph.getRuns();
            if (runs == null) {
                return images;
            }

            for (int runIndex = 0; runIndex < runs.size(); runIndex++) {
                XWPFRun run = runs.get(runIndex);

                // 检查是否有嵌入式图片
                List<XWPFPicture> embeddedPictures = run.getEmbeddedPictures();
                if (embeddedPictures != null && !embeddedPictures.isEmpty()) {
                    for (XWPFPicture picture : embeddedPictures) {
                        try {
                            XWPFPictureData pictureData = picture.getPictureData();
                            if (pictureData != null) {
                                ImageInfo imageInfo = new ImageInfo();
                                imageInfo.setIndex(images.size());
                                imageInfo.setData(pictureData.getData());
                                imageInfo.setFormat(pictureData.suggestFileExtension());

                                // 设置文件名
                                try {
                                    String fileName = pictureData.getFileName();
                                    if (fileName == null || fileName.isEmpty()) {
                                        fileName = "img_" + paraIndex + "_" + runIndex + "_" + images.size() +
                                                "." + pictureData.suggestFileExtension();
                                    }
                                    imageInfo.setFileName(fileName);
                                } catch (Exception e) {
                                    imageInfo.setFileName("img_" + paraIndex + "_" + runIndex + "_" + images.size() + ".png");
                                }

                                // 位置信息
                                ImagePosition position = new ImagePosition();
                                position.setParagraphIndex(paraIndex);
                                position.setRunIndex(runIndex);
                                position.setCharPosition(charPosition);
                                position.setRunText(run.getText(0));

                                imageInfo.setPosition(position);
                                images.add(imageInfo);
                            }
                        } catch (Exception e) {
                            log.warn("处理嵌入式图片失败", e);
                        }
                    }
                }
            }

        } catch (Exception e) {
            log.warn("从runs提取图片失败", e);
        }

        return images;
    }

    /**
     * 通过ID查找图片
     */
    private XWPFPictureData findPictureById(String id, Map<String, XWPFPictureData> pictureIdMap) {
        // 直接匹配
        if (pictureIdMap.containsKey(id)) {
            return pictureIdMap.get(id);
        }

        // 尝试部分匹配
        for (Map.Entry<String, XWPFPictureData> entry : pictureIdMap.entrySet()) {
            String key = entry.getKey();
            if (key.contains(id) || id.contains(key)) {
                return entry.getValue();
            }
        }

        return null;
    }

    /**
     * 创建图片信息
     */
    private ImageInfo createImageInfoV5(XWPFPictureData pictureData,
                                        int paraIndex, int charPosition,
                                        int imageIndex) {
        ImageInfo imageInfo = new ImageInfo();
        imageInfo.setIndex(imageIndex);
        imageInfo.setData(pictureData.getData());

        // 设置格式
        String format = pictureData.suggestFileExtension();
        if (format == null || format.isEmpty()) {
            format = "png";
        }
        imageInfo.setFormat(format);

        // 设置文件名
        try {
            String fileName = pictureData.getFileName();
            if (fileName == null || fileName.trim().isEmpty()) {
                fileName = "img_" + paraIndex + "_" + imageIndex + "." + format;
            }
            imageInfo.setFileName(fileName);
        } catch (Exception e) {
            imageInfo.setFileName("img_" + paraIndex + "_" + imageIndex + "." + format);
        }

        // 位置信息
        ImagePosition position = new ImagePosition();
        position.setParagraphIndex(paraIndex);
        position.setCharPosition(charPosition);
        imageInfo.setPosition(position);

        return imageInfo;
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
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DocumentImportResult {
        private boolean success;
        private String documentId;
        private String documentName;
        private String collectionName;
        private String error;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private Integer chunkCount;
        private Integer imageCount;
        private Integer vectorCount;
        private List<Long> vectorIds;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DocumentImportRequest {
        private String collectionName;
        private Boolean extractImages = true;
        private Integer chunkSize = 1000;
        private ChunkStrategy chunkStrategy = ChunkStrategy.SEMANTIC;
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
        private String fileName;
        private String filePath;
        private String format;
        private byte[] data;
        private ImagePosition position;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImagePosition {
        private Integer paragraphIndex;
        private Integer paragraphId;
        private Integer runIndex;
        private Integer charPosition;
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
