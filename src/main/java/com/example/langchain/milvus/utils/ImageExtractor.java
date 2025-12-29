package com.example.langchain.milvus.utils;

import com.example.langchain.milvus.dto.model.ImageInfo;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPageTree;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.model.PicturesTable;
import org.apache.poi.hwpf.usermodel.CharacterRun;
import org.apache.poi.hwpf.usermodel.Picture;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;
import org.openxmlformats.schemas.drawingml.x2006.main.CTBlip;
import org.openxmlformats.schemas.drawingml.x2006.main.CTBlipFillProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTGraphicalObject;
import org.openxmlformats.schemas.drawingml.x2006.main.CTGraphicalObjectData;
import org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.CTAnchor;
import org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.CTInline;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTDecimalNumber;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTDrawing;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPicture;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTc;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTVMerge;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import javax.xml.namespace.QName;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@Slf4j
public class ImageExtractor {

    @Value("${app.document.min-image-size:1024}")
    private Long minImageSize;

    @Value("${app.document.max-image-size:10485760}")
    private Long maxImageSize;

    public List<ImageInfo> extractImages(MultipartFile file, String outputDir) throws Exception {
        String fileName = file.getOriginalFilename();
        String fileType = getFileExtension(fileName);
        List<ImageInfo> images = new ArrayList<>();

        try (InputStream is = file.getInputStream()) {
            switch (fileType.toLowerCase()) {
                case "pdf":
                    images = extractImagesFromPdf(is, outputDir);
                    break;
                case "png":
                case "jpg":
                case "jpeg":
                case "gif":
                case "bmp":
                    // 文件本身就是图片
                    images.add(saveImageFile(file, outputDir, 1));
                    break;
                case "docx":
                    images = extractImagesFromDocx(is, outputDir, fileName);
                    break;
                case "doc":
                    images = extractImagesFromDoc(is, outputDir, fileName);
                    break;
                default:
                    log.info("文件类型 {} 不支持图片提取", fileType);
            }
        }

        return images;
    }
    /**
     * 从 DOCX 中提取图片
     */
    private List<ImageInfo> extractImagesFromDocx(InputStream is, String outputDir, String fileName) throws Exception {
        List<ImageInfo> images = new ArrayList<>();

        try (XWPFDocument document = new XWPFDocument(is)) {
            // 获取所有图片
            List<XWPFPictureData> allPictures = document.getAllPictures();

            // 构建段落和表格的索引映射
            Map<String, Object> documentStructure = buildDocumentStructure(document);

            for (int i = 0; i < allPictures.size(); i++) {
                try {
                    XWPFPictureData pictureData = allPictures.get(i);

                    // 检查图片大小
                    if (!isValidImageSize(pictureData.getData().length)) {
                        continue;
                    }

                    // 获取图片信息
                    String pictureFileName = pictureData.getFileName();
                    String contentType = pictureData.getPackagePart().getContentType();
                    String fileExtension = getFileExtensionFromContentType(contentType);

                    if (fileExtension.isEmpty()) {
                        fileExtension = getFileExtension(pictureFileName);
                        if (fileExtension.isEmpty()) {
                            fileExtension = "png"; // 默认PNG格式
                        }
                    }

                    // 获取图片在文档中的详细位置信息
                    ImagePositionInfo positionInfo = getDetailedPositionInfo(document, pictureData, i, documentStructure);

                    // 保存图片
                    ImageInfo imageInfo = saveWordImage(
                            pictureData.getData(),
                            outputDir,
                            i + 1,
                            fileExtension,
                            fileName
                    );

                    if (imageInfo != null) {
                        // 构建包含位置信息的元数据
                        Map<String, Object> metadata = new HashMap<>();
                        metadata.put("source", "docx");
                        metadata.put("paragraph", positionInfo.getParagraphIndex());
                        metadata.put("paragraphText", positionInfo.getParagraphText());
                        metadata.put("position", positionInfo.getPositionIndex());  // 关键：位置信息
                        metadata.put("absolutePosition", positionInfo.getAbsolutePosition());
                        metadata.put("contentType", contentType);
                        metadata.put("originalFileName", pictureFileName);
                        metadata.put("imageIndexInDoc", i + 1);
                        metadata.put("pageEstimate", positionInfo.getPageEstimate());
                        metadata.put("totalImagesInDoc", allPictures.size());
                        metadata.put("positionType", positionInfo.getPositionType());

                        // 添加表格信息（如果在表格中）
                        if (positionInfo.getTableInfo() != null) {
                            metadata.putAll(positionInfo.getTableInfo());
                        }

                        // 添加关联信息
                        metadata.put("associationSource", "paragraph_position");
                        metadata.put("extractedAt", LocalDateTime.now().toString());

                        imageInfo.setPageNumber(positionInfo.getPageEstimate());
                        imageInfo.setMetadata(metadata);
                        imageInfo.setPosition(positionInfo.getPositionIndex());  // 设置位置属性

                        images.add(imageInfo);
                    }

                    // 检查是否达到最大数量限制
                    if (images.size() >= 100) {
                        break;
                    }

                } catch (Exception e) {
                    log.warn("提取DOCX图片失败: 第 {} 张图片", i + 1, e);
                }
            }

            // 如果没有提取到图片，尝试从绘图对象中提取
            if (images.isEmpty()) {
                images.addAll(extractImagesFromDrawingsWithPosition(document, outputDir, fileName));
            }

        } catch (Exception e) {
            log.error("提取DOCX图片失败", e);
        }

        return images;
    }

    /**
     * 图片位置信息类
     */
    @Getter
    @Builder
    private static class ImagePositionInfo {
        private int paragraphIndex;      // 段落索引
        private int positionIndex;      // 位置索引（用于关联的主要信息）
        private int absolutePosition;    // 绝对位置
        private int pageEstimate;        // 估计的页码
        private String paragraphText;    // 所在段落的文本
        private String positionType;     // 位置类型：paragraph, table, header, footer等
        private Map<String, Object> tableInfo;  // 表格信息（如果在表格中）
    }

    /**
     * 获取图片的详细位置信息
     */
    private ImagePositionInfo getDetailedPositionInfo(XWPFDocument document,
                                                      XWPFPictureData pictureData,
                                                      int imageIndex,
                                                      Map<String, Object> documentStructure) {

        ImagePositionInfo.ImagePositionInfoBuilder builder = ImagePositionInfo.builder();

        try {
            // 1. 查找图片在文档中的位置
            PositionSearchResult searchResult = findPictureExactPosition(document, pictureData);

            if (searchResult != null) {
                builder.paragraphIndex(searchResult.getParagraphIndex())
                        .paragraphText(searchResult.getParagraphText())
                        .positionType(searchResult.getPositionType())
                        .tableInfo(searchResult.getTableInfo());

                // 计算位置索引
                int positionIndex = calculatePositionIndex(
                        searchResult.getParagraphIndex(),
                        searchResult.getRunIndex(),
                        searchResult.getInlineIndex(),
                        imageIndex
                );
                builder.positionIndex(positionIndex);

                // 计算绝对位置
                int absolutePosition = calculateAbsolutePosition(
                        searchResult.getParagraphIndex(),
                        searchResult.getRunIndex(),
                        imageIndex
                );
                builder.absolutePosition(absolutePosition);

                // 估算页码
                int pageEstimate = estimatePageNumber(
                        searchResult.getParagraphIndex(),
                        document.getParagraphs().size()
                );
                builder.pageEstimate(pageEstimate);

            } else {
                // 如果找不到精确位置，使用默认值
                int paragraphIndex = findPictureParagraph(document, pictureData);
                String paragraphText = getParagraphText(document, paragraphIndex);

                builder.paragraphIndex(paragraphIndex)
                        .paragraphText(paragraphText)
                        .positionType("paragraph")
                        .positionIndex(paragraphIndex * 100 + imageIndex)
                        .absolutePosition(paragraphIndex * 1000 + imageIndex)
                        .pageEstimate(paragraphIndex / 20 + 1);
            }

        } catch (Exception e) {
            log.warn("获取图片位置信息失败，使用默认值", e);
            // 使用基于索引的默认位置
            builder.paragraphIndex(imageIndex)
                    .paragraphText("")
                    .positionType("unknown")
                    .positionIndex(imageIndex)
                    .absolutePosition(imageIndex)
                    .pageEstimate(imageIndex / 5 + 1);
        }

        return builder.build();
    }

    /**
     * 获取指定段落的文本内容
     */
    private String getParagraphText(XWPFDocument document, int paragraphIndex) {
        if (document == null || paragraphIndex < 0) {
            return "";
        }

        List<XWPFParagraph> paragraphs = document.getParagraphs();
        if (paragraphIndex >= paragraphs.size()) {
            return "";
        }

        try {
            XWPFParagraph paragraph = paragraphs.get(paragraphIndex);
            if (paragraph == null) {
                return "";
            }

            // 获取段落文本
            String paragraphText = paragraph.getText();
            if (paragraphText == null) {
                paragraphText = "";
            }

            // 清理文本
            return cleanParagraphText(paragraphText);

        } catch (Exception e) {
            log.warn("获取段落文本失败，索引: {}", paragraphIndex, e);
            return "";
        }
    }

    /**
     * 清理段落文本
     */
    private String cleanParagraphText(String text) {
        if (text == null) {
            return "";
        }

        // 去除多余的空格和换行
        text = text.trim();
        text = text.replaceAll("\\s+", " ");
        text = text.replaceAll("\\n+", " ");

        // 处理特殊字符
        text = text.replaceAll("[\\x00-\\x1F\\x7F]", ""); // 去除控制字符
        text = text.replaceAll("\\uFEFF", ""); // 去除BOM

        return text;
    }

    /**
     * 查找图片的精确位置
     */
    private PositionSearchResult findPictureExactPosition(XWPFDocument document,
                                                          XWPFPictureData pictureData) {
        List<XWPFParagraph> paragraphs = document.getParagraphs();

        for (int paraIndex = 0; paraIndex < paragraphs.size(); paraIndex++) {
            XWPFParagraph paragraph = paragraphs.get(paraIndex);

            // 1. 在段落中查找
            PositionSearchResult result = searchInParagraph(paragraph, paraIndex, pictureData);
            if (result != null) {
                return result;
            }

            // 2. 在相关表格中查找
            if (paragraph.getBody() instanceof XWPFTableCell) {
                XWPFTableCell cell = (XWPFTableCell) paragraph.getBody();
                PositionSearchResult tableResult = searchInTableCell(cell, paraIndex, pictureData);
                if (tableResult != null) {
                    return tableResult;
                }
            }
        }

        // 3. 在页眉页脚中查找
        PositionSearchResult headerResult = searchInHeaders(document.getHeaderList(), pictureData);
        if (headerResult != null) {
            return headerResult;
        }

        PositionSearchResult footerResult = searchInFooters(document.getFooterList(), pictureData);
        if (footerResult != null) {
            return footerResult;
        }

        return null;
    }

    /**
     * 在页眉中搜索图片
     */
    private PositionSearchResult searchInHeaders(List<XWPFHeader> headers, XWPFPictureData pictureData) {
        if (headers == null || headers.isEmpty() || pictureData == null) {
            return null;
        }

        for (int headerIndex = 0; headerIndex < headers.size(); headerIndex++) {
            XWPFHeader header = headers.get(headerIndex);
            if (header == null) {
                continue;
            }

            // 1. 在页眉段落中搜索
            List<XWPFParagraph> paragraphs = header.getParagraphs();
            for (int paraIndex = 0; paraIndex < paragraphs.size(); paraIndex++) {
                XWPFParagraph paragraph = paragraphs.get(paraIndex);
                if (paragraph == null) {
                    continue;
                }

                // 在段落中搜索图片
                PositionSearchResult result = searchInParagraph(paragraph, -1000 - headerIndex, pictureData);
                if (result != null) {
                    // 标记为页眉
                    result.setPositionType("header");

                    // 添加页眉信息
                    Map<String, Object> headerInfo = new HashMap<>();
                    headerInfo.put("headerIndex", headerIndex);
                    headerInfo.put("headerType", getHeaderType(header));
//                    headerInfo.put("headerId", header.getPart().getPartName().getName());
                    result.setTableInfo(headerInfo);

                    return result;
                }
            }

            // 2. 在页眉的表格中搜索
            List<XWPFTable> tables = header.getTables();
            for (int tableIndex = 0; tableIndex < tables.size(); tableIndex++) {
                XWPFTable table = tables.get(tableIndex);
                PositionSearchResult tableResult = searchInTable(table, -1000 - headerIndex, pictureData, "header_table");
                if (tableResult != null) {
                    Map<String, Object> headerInfo = new HashMap<>();
                    headerInfo.put("headerIndex", headerIndex);
                    headerInfo.put("tableIndex", tableIndex);
                    tableResult.setTableInfo(headerInfo);
                    return tableResult;
                }
            }
        }

        return null;
    }

    /**
     * 在页脚中搜索图片
     */
    private PositionSearchResult searchInFooters(List<XWPFFooter> footers, XWPFPictureData pictureData) {
        if (footers == null || footers.isEmpty() || pictureData == null) {
            return null;
        }

        for (int footerIndex = 0; footerIndex < footers.size(); footerIndex++) {
            XWPFFooter footer = footers.get(footerIndex);
            if (footer == null) {
                continue;
            }

            // 1. 在页脚段落中搜索
            List<XWPFParagraph> paragraphs = footer.getParagraphs();
            for (int paraIndex = 0; paraIndex < paragraphs.size(); paraIndex++) {
                XWPFParagraph paragraph = paragraphs.get(paraIndex);
                if (paragraph == null) {
                    continue;
                }

                // 在段落中搜索图片
                PositionSearchResult result = searchInParagraph(paragraph, -2000 - footerIndex, pictureData);
                if (result != null) {
                    // 标记为页脚
                    result.setPositionType("footer");

                    // 添加页脚信息
                    Map<String, Object> footerInfo = new HashMap<>();
                    footerInfo.put("footerIndex", footerIndex);
                    footerInfo.put("footerType", getFooterType(footer));
//                    footerInfo.put("footerId", footer.getPart().getPartName().getName());
                    result.setTableInfo(footerInfo);

                    return result;
                }
            }

            // 2. 在页脚的表格中搜索
            List<XWPFTable> tables = footer.getTables();
            for (int tableIndex = 0; tableIndex < tables.size(); tableIndex++) {
                XWPFTable table = tables.get(tableIndex);
                PositionSearchResult tableResult = searchInTable(table, -2000 - footerIndex, pictureData, "footer_table");
                if (tableResult != null) {
                    Map<String, Object> footerInfo = new HashMap<>();
                    footerInfo.put("footerIndex", footerIndex);
                    footerInfo.put("tableIndex", tableIndex);
                    tableResult.setTableInfo(footerInfo);
                    return tableResult;
                }
            }
        }

        return null;
    }

    /**
     * 在表格中搜索图片
     */
    private PositionSearchResult searchInTable(XWPFTable table, int baseIndex,
                                               XWPFPictureData pictureData, String positionType) {
        if (table == null || pictureData == null) {
            return null;
        }

        List<XWPFTableRow> rows = table.getRows();
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            XWPFTableRow row = rows.get(rowIndex);
            if (row == null) {
                continue;
            }

            List<XWPFTableCell> cells = row.getTableCells();
            for (int cellIndex = 0; cellIndex < cells.size(); cellIndex++) {
                XWPFTableCell cell = cells.get(cellIndex);
                if (cell == null) {
                    continue;
                }

                // 在单元格段落中搜索
                List<XWPFParagraph> cellParagraphs = cell.getParagraphs();
                for (int cellParaIndex = 0; cellParaIndex < cellParagraphs.size(); cellParaIndex++) {
                    XWPFParagraph paragraph = cellParagraphs.get(cellParaIndex);
                    if (paragraph == null) {
                        continue;
                    }

                    PositionSearchResult result = searchInParagraph(paragraph, baseIndex, pictureData);
                    if (result != null) {
                        // 添加表格信息
                        Map<String, Object> tableInfo = new HashMap<>();
                        tableInfo.put("inTable", true);
                        tableInfo.put("tableIndex", getTableIndex(table));
                        tableInfo.put("rowIndex", rowIndex);
                        tableInfo.put("cellIndex", cellIndex);
                        tableInfo.put("cellRowSpan", getCellRowSpan(cell));
                        tableInfo.put("cellColSpan", getCellColSpan(cell));

                        result.setPositionType(positionType);
                        result.setTableInfo(tableInfo);
                        return result;
                    }
                }
            }
        }

        return null;
    }

    /**
     * 获取单元格的行跨度
     */
    private int getCellRowSpan(XWPFTableCell cell) {
        try {
            CTTc ctTc = cell.getCTTc();
            if (ctTc != null && ctTc.isSetTcPr()) {
                CTTcPr tcPr = ctTc.getTcPr();
                if (tcPr != null && tcPr.isSetVMerge()) {
                    CTVMerge vMerge = tcPr.getVMerge();
                    if (vMerge != null && vMerge.getVal() != null) {
                        if (vMerge.getVal() == STMerge.RESTART) {
                            return 1; // 跨行开始
                        } else if (vMerge.getVal() == STMerge.CONTINUE) {
                            return 0; // 跨行继续
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("获取单元格行跨度失败", e);
        }
        return 1;
    }

    /**
     * 获取单元格的列跨度
     */
    private int getCellColSpan(XWPFTableCell cell) {
        try {
            CTTc ctTc = cell.getCTTc();
            if (ctTc != null && ctTc.isSetTcPr()) {
                CTTcPr tcPr = ctTc.getTcPr();
                if (tcPr != null && tcPr.isSetGridSpan()) {
                    CTDecimalNumber gridSpan = tcPr.getGridSpan();
                    if (gridSpan != null) {
                        return gridSpan.getVal().intValue();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("获取单元格列跨度失败", e);
        }
        return 1;
    }

    /**
     * 获取页眉类型
     */
    private String getHeaderType(XWPFHeader header) {
        if (header == null) {
            return "unknown";
        }

        try {
            // 通过页眉的ID判断类型
            String partName = header.getPart().getPackagePart().getPartName().getName();
            if (partName.contains("header")) {
                if (partName.contains("first")) {
                    return "first_header";
                } else if (partName.contains("even")) {
                    return "even_header";
                } else if (partName.contains("default")) {
                    return "default_header";
                } else {
                    return "normal_header";
                }
            }
        } catch (Exception e) {
            log.debug("获取页眉类型失败", e);
        }

        return "normal_header";
    }

    /**
     * 获取页脚类型
     */
    private String getFooterType(XWPFFooter footer) {
        if (footer == null) {
            return "unknown";
        }

        try {
            // 通过页脚的ID判断类型
            String partName = footer.getPart().getPackagePart().getPartName().getName();
            if (partName.contains("footer")) {
                if (partName.contains("first")) {
                    return "first_footer";
                } else if (partName.contains("even")) {
                    return "even_footer";
                } else if (partName.contains("default")) {
                    return "default_footer";
                } else {
                    return "normal_footer";
                }
            }
        } catch (Exception e) {
            log.debug("获取页脚类型失败", e);
        }

        return "normal_footer";
    }

    /**
     * 获取表格索引
     */
    private int getTableIndex(XWPFTable table) {
        if (table == null) {
            return -1;
        }

        // 在实际使用中，你可能需要记录文档中所有表格的索引
        // 这里简化处理，返回-1表示未知索引
        return -1;
    }

    /**
     * 在段落中搜索图片
     */
    private PositionSearchResult searchInParagraph(XWPFParagraph paragraph,
                                                   int paraIndex,
                                                   XWPFPictureData pictureData) {
        List<XWPFRun> runs = paragraph.getRuns();

        for (int runIndex = 0; runIndex < runs.size(); runIndex++) {
            XWPFRun run = runs.get(runIndex);
            List<XWPFPicture> pictures = run.getEmbeddedPictures();

            for (int picIndex = 0; picIndex < pictures.size(); picIndex++) {
                XWPFPicture picture = pictures.get(picIndex);
                if (picture.getPictureData() == pictureData ||
                        picture.getPictureData().equals(pictureData)) {

                    String paragraphText = paragraph.getText();
                    String runText = getRunText(run);
                    Map<String, Object> surroundingContext = getSurroundingContext(
                            paragraphText, runText
                    );

                    return PositionSearchResult.builder()
                            .paragraphIndex(paraIndex)
                            .paragraphText(paragraphText)
                            .runIndex(runIndex)
                            .inlineIndex(picIndex)
                            .positionType("paragraph_inline")
                            .surroundingContext(surroundingContext)
                            .build();
                }
            }

            // 2. 检查绘图对象
            List<CTDrawing> drawings = getDrawingsFromRun(run);
            for (int drawIndex = 0; drawIndex < drawings.size(); drawIndex++) {
                PositionSearchResult drawingResult = searchInDrawing(
                        drawings.get(drawIndex), paraIndex, runIndex, drawIndex, pictureData
                );
                if (drawingResult != null) {
                    return drawingResult;
                }
            }
        }

        return null;
    }

    /**
     * 获取运行中的文本
     */
    private String getRunText(XWPFRun run) {
        if (run == null) {
            return "";
        }

        try {
            // 方法1: 使用getText(0)
            String text = run.getText(0);
            if (text != null) {
                return text.trim();
            }

            // 方法2: 使用toString()获取文本
            text = run.toString();
            if (text != null && !text.isEmpty()) {
                return text.trim();
            }

            // 方法3: 直接从CTR获取
            CTR ctr = run.getCTR();
            if (ctr != null && ctr.sizeOfTArray() > 0) {
                text = ctr.getTArray(0).getStringValue();
                if (text != null) {
                    return text.trim();
                }
            }

        } catch (Exception e) {
            log.debug("获取运行文本失败", e);
        }

        return "";
    }

    /**
     * 在绘图对象中搜索图片
     */
    private PositionSearchResult searchInDrawing(CTDrawing drawing,
                                                 int paraIndex,
                                                 int runIndex,
                                                 int drawIndex,
                                                 XWPFPictureData pictureData) {
        if (drawing == null || pictureData == null) {
            return null;
        }

        try {
            // 方法1: 通过XML游标遍历绘图对象
            return searchInDrawingWithCursor(drawing, paraIndex, runIndex, drawIndex, pictureData);

        } catch (Exception e) {
            log.warn("在绘图对象中搜索图片失败", e);

            // 方法2: 尝试使用DOM方式
//            try {
//                return searchInDrawingWithDOM(drawing, paraIndex, runIndex, drawIndex, pictureData);
//            } catch (Exception ex) {
//                log.debug("DOM方式搜索图片也失败", ex);
//            }
        }

        return null;
    }

    /**
     * 使用XML游标在绘图对象中搜索图片
     */
    private PositionSearchResult searchInDrawingWithCursor(CTDrawing drawing,
                                                           int paraIndex,
                                                           int runIndex,
                                                           int drawIndex,
                                                           XWPFPictureData pictureData) {
        if (drawing == null) {
            return null;
        }

        try (XmlCursor cursor = drawing.newCursor()) {
            // 移动到绘图对象的开始
            cursor.toFirstChild();

            int pictureIndex = 0;

            do {
                // 检查当前位置是否包含图片
                if (cursor.getName().getLocalPart().equals("inline") ||
                        cursor.getName().getLocalPart().equals("anchor")) {

                    // 在当前元素中搜索图片引用
                    PositionSearchResult result = searchForPictureInDrawingElement(
                            cursor, paraIndex, runIndex, drawIndex, pictureIndex, pictureData
                    );

                    if (result != null) {
                        return result;
                    }

                    pictureIndex++;
                }
            } while (cursor.toNextSibling());

        } catch (Exception e) {
            log.debug("使用游标搜索绘图对象失败", e);
        }

        return null;
    }

    /**
     * 在绘图元素中搜索图片
     */
    private PositionSearchResult searchForPictureInDrawingElement(XmlCursor cursor,
                                                                  int paraIndex,
                                                                  int runIndex,
                                                                  int drawIndex,
                                                                  int pictureIndex,
                                                                  XWPFPictureData pictureData) {
        if (cursor == null || pictureData == null) {
            return null;
        }

        try {
            // 检查内联绘图
            XmlCursor picCursor = cursor.newCursor();
            picCursor.push();

            // 搜索blip元素
            if (picCursor.toChild(new QName("http://schemas.openxmlformats.org/drawingml/2006/main", "blip"))) {
                String blipId = picCursor.getAttributeText(new QName("", "embed"));
                if (blipId != null && isPictureReference(blipId, pictureData)) {
                    picCursor.pop();
                    return buildDrawingPositionResult(paraIndex, runIndex, drawIndex,
                            pictureIndex, "inline_drawing");
                }
            }

            picCursor.pop();

            // 搜索图形对象
            if (picCursor.toChild(new QName("http://schemas.openxmlformats.org/drawingml/2006/main", "graphic"))) {
                if (picCursor.toChild(new QName("http://schemas.openxmlformats.org/drawingml/2006/main", "graphicData"))) {
                    // 搜索图片
                    if (picCursor.toChild(new QName("http://schemas.openxmlformats.org/drawingml/2006/picture", "pic"))) {
                        if (picCursor.toChild(new QName("http://schemas.openxmlformats.org/drawingml/2006/picture", "blipFill"))) {
                            if (picCursor.toChild(new QName("http://schemas.openxmlformats.org/drawingml/2006/main", "blip"))) {
                                String blipId = picCursor.getAttributeText(new QName("", "embed"));
                                if (blipId != null && isPictureReference(blipId, pictureData)) {
                                    picCursor.pop();
                                    picCursor.pop();
                                    picCursor.pop();
                                    picCursor.pop();
                                    return buildDrawingPositionResult(paraIndex, runIndex, drawIndex,
                                            pictureIndex, "graphic_drawing");
                                }
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            log.debug("在绘图元素中搜索图片失败", e);
        }

        return null;
    }

    /**
     * 在图形数据中搜索图片
     */
    private PositionSearchResult searchInGraphicData(CTGraphicalObjectData graphicData,
                                                     int paraIndex,
                                                     int runIndex,
                                                     int drawIndex,
                                                     int graphicIndex,
                                                     XWPFPictureData pictureData) {
        if (graphicData == null || pictureData == null) {
            return null;
        }

//        try {
//            // 获取所有DOM节点
//            XmlObject[] xmlObjects = graphicData.selectChildren(
//                    new QName("http://schemas.openxmlformats.org/drawingml/2006/picture", "pic")
//            );
//
//            for (int picIndex = 0; picIndex < xmlObjects.length; picIndex++) {
//                XmlObject xmlObj = xmlObjects[picIndex];
//                if (xmlObj instanceof CTPicture) {
//                    CTPicture ctPicture = (CTPicture) xmlObj;
//
//                    // 检查图片填充属性
//                    if (ctPicture.isSetBlipFill()) {
//                        CTBlipFillProperties blipFill = ctPicture();
//                        if (blipFill != null && blipFill.isSetBlip()) {
//                            CTBlip blip = blipFill.getBlip();
//                            if (blip != null) {
//                                String blipId = blip.getEmbed();
//                                if (blipId != null && isPictureReference(blipId, pictureData)) {
//                                    return buildDrawingPositionResult(paraIndex, runIndex,
//                                            drawIndex, graphicIndex,
//                                            picIndex, "drawing_picture");
//                                }
//                            }
//                        }
//                    }
//                }
//            }
//
//        } catch (Exception e) {
//            log.warn("在图形数据中搜索图片失败", e);
//        }

        return null;
    }

    /**
     * 检查图片引用是否匹配
     */
    private boolean isPictureReference(String blipId, XWPFPictureData pictureData) {
        if (blipId == null || pictureData == null) {
            return false;
        }

        try {
            // 获取图片数据的ID
            String pictureId = pictureData.getPackagePart().getPartName().getName();
            if (pictureId != null && pictureId.contains(blipId)) {
                return true;
            }

            // 尝试从关系中获取
//            String relationshipId = getRelationshipIdFromBlipId(blipId);
//            if (relationshipId != null && relationshipId.equals(pictureData.getPackagePart().getRelationshipId())) {
//                return true;
//            }

        } catch (Exception e) {
            log.debug("检查图片引用失败", e);
        }

        return false;
    }

    /**
     * 从blipId获取关系ID
     */
    private String getRelationshipIdFromBlipId(String blipId) {
        if (blipId == null || blipId.isEmpty()) {
            return null;
        }

        // 移除前缀
        if (blipId.startsWith("rId")) {
            return blipId;
        }

        // 尝试从字符串中提取rId
        int rIdIndex = blipId.indexOf("rId");
        if (rIdIndex >= 0) {
            String subStr = blipId.substring(rIdIndex);
            if (subStr.length() > 3) {
                String idStr = subStr.substring(3);
                if (idStr.matches("\\d+")) {
                    return "rId" + idStr;
                }
            }
        }

        return null;
    }

    /**
     * 构建绘图对象的位置结果
     */
    private PositionSearchResult buildDrawingPositionResult(int paraIndex,
                                                            int runIndex,
                                                            int drawIndex,
                                                            int pictureIndex,
                                                            String positionType) {
        Map<String, Object> context = new HashMap<>();
        context.put("drawingIndex", drawIndex);
        context.put("pictureIndexInDrawing", pictureIndex);
        context.put("searchMethod", "drawing_search");

        return PositionSearchResult.builder()
                .paragraphIndex(paraIndex)
                .paragraphText("")
                .runIndex(runIndex)
                .inlineIndex(pictureIndex)
                .positionType(positionType)
                .surroundingContext(context)
                .build();
    }

    /**
     * 比较图片数据是否相同
     */
    private boolean isSamePictureData(XWPFPictureData pic1, XWPFPictureData pic2) {
        if (pic1 == pic2) {
            return true;
        }
        if (pic1 == null || pic2 == null) {
            return false;
        }

        try {
            // 1. 比较文件名
            String fileName1 = pic1.getFileName();
            String fileName2 = pic2.getFileName();
            if (fileName1 != null && fileName2 != null && fileName1.equals(fileName2)) {
                return true;
            }

            // 2. 比较内容类型
            String contentType1 = pic1.getPackagePart().getContentType();
            String contentType2 = pic2.getPackagePart().getContentType();
            if (contentType1 != null && contentType2 != null && contentType1.equals(contentType2)) {
                // 3. 比较数据
                byte[] data1 = pic1.getData();
                byte[] data2 = pic2.getData();

                if (data1 != null && data2 != null && data1.length == data2.length) {
                    // 比较前256个字节，通常足够了
                    int compareLength = Math.min(256, Math.min(data1.length, data2.length));
                    for (int i = 0; i < compareLength; i++) {
                        if (data1[i] != data2[i]) {
                            return false;
                        }
                    }
                    return true;
                }
            }

        } catch (Exception e) {
            log.debug("比较图片数据失败", e);
        }

        return false;
    }

    /**
     * 在表格单元格中搜索图片
     */
    private PositionSearchResult searchInTableCell(XWPFTableCell cell,
                                                   int paraIndex,
                                                   XWPFPictureData pictureData) {
        List<XWPFParagraph> cellParagraphs = cell.getParagraphs();

        for (int cellParaIndex = 0; cellParaIndex < cellParagraphs.size(); cellParaIndex++) {
            PositionSearchResult result = searchInParagraph(
                    cellParagraphs.get(cellParaIndex),
                    paraIndex,
                    pictureData
            );
            if (result != null) {
                // 添加表格信息
                Map<String, Object> tableInfo = new HashMap<>();
                tableInfo.put("inTable", true);
                tableInfo.put("tableIndex", getTableIndex(cell));
                tableInfo.put("rowIndex", getRowIndex(cell));
                tableInfo.put("cellIndex", getCellIndex(cell));

                result.setPositionType("table_cell");
                result.setTableInfo(tableInfo);
                return result;
            }
        }

        return null;
    }

    /**
     * 获取表格索引
     */
    private int getTableIndex(XWPFTableCell cell) {
        // 实现获取表格索引的逻辑
        return 0;
    }

    /**
     * 获取行索引
     */
    private int getRowIndex(XWPFTableCell cell) {
        // 实现获取行索引的逻辑
        return 0;
    }

    /**
     * 获取单元格索引
     */
    private int getCellIndex(XWPFTableCell cell) {
        // 实现获取单元格索引的逻辑
        return 0;
    }

    /**
     * 计算位置索引
     */
    private int calculatePositionIndex(int paragraphIndex, int runIndex, int inlineIndex, int imageIndex) {
        // 计算一个唯一的位置索引
        // 格式：段落索引 * 10000 + 运行索引 * 100 + 内联索引
        return paragraphIndex * 10000 + runIndex * 100 + inlineIndex;
    }

    /**
     * 计算绝对位置
     */
    private int calculateAbsolutePosition(int paragraphIndex, int runIndex, int imageIndex) {
        // 计算绝对位置，用于排序和比较
        return paragraphIndex * 1000 + runIndex * 10 + imageIndex;
    }

    /**
     * 估算页码
     */
    private int estimatePageNumber(int paragraphIndex, int totalParagraphs) {
        // 简单估算：假设每页大约20个段落
        int paragraphsPerPage = 20;
        return Math.max(1, paragraphIndex / paragraphsPerPage + 1);
    }

    /**
     * 从绘图对象中提取图片（带位置信息） - 最小改动版
     */
    private List<ImageInfo> extractImagesFromDrawingsWithPosition(XWPFDocument document,
                                                                  String outputDir,
                                                                  String fileName) throws Exception {
        List<ImageInfo> images = new ArrayList<>();
        int drawingIndex = 0;

        List<XWPFParagraph> paragraphs = document.getParagraphs();

        for (int paraIndex = 0; paraIndex < paragraphs.size(); paraIndex++) {
            XWPFParagraph paragraph = paragraphs.get(paraIndex);
            List<XWPFRun> runs = paragraph.getRuns();

            for (int runIndex = 0; runIndex < runs.size(); runIndex++) {
                XWPFRun run = runs.get(runIndex);

                // 获取绘图对象列表
                List<CTDrawing> drawings = getDrawingsFromRun(run);

                for (int drawIdx = 0; drawIdx < drawings.size(); drawIdx++) {
                    CTDrawing drawing = drawings.get(drawIdx);

                    // 从绘图对象中提取图片
                    List<XWPFPictureData> pictureDataList = extractPicturesFromDrawing(drawing, document);

                    for (int picIdx = 0; picIdx < pictureDataList.size(); picIdx++) {
                        XWPFPictureData pictureData = pictureDataList.get(picIdx);

                        if (pictureData != null && isValidImageSize(pictureData.getData().length)) {
                            try {
                                // 获取图片信息
                                String pictureFileName = pictureData.getFileName();
                                String contentType = pictureData.getPackagePart().getContentType();
                                String fileExtension = getFileExtensionFromContentType(contentType);

                                if (fileExtension.isEmpty()) {
                                    fileExtension = getFileExtension(pictureFileName);
                                    if (fileExtension.isEmpty()) {
                                        fileExtension = "png";
                                    }
                                }

                                // 保存图片
                                ImageInfo imageInfo = saveWordImage(
                                        pictureData.getData(),
                                        outputDir,
                                        drawingIndex + 1,
                                        fileExtension,
                                        fileName
                                );

                                if (imageInfo != null) {
                                    // 构建位置信息
                                    int positionIndex = calculatePositionIndex(paraIndex, runIndex, drawIdx, drawingIndex);
                                    int absolutePosition = calculateAbsolutePosition(paraIndex, runIndex, drawIdx);
                                    int pageEstimate = estimatePageNumber(paraIndex, paragraphs.size());

                                    Map<String, Object> metadata = new HashMap<>();
                                    metadata.put("source", "docx_drawing");
                                    metadata.put("paragraph", paraIndex + 1);
                                    metadata.put("paragraphText", paragraph.getText());
                                    metadata.put("position", positionIndex);
                                    metadata.put("absolutePosition", absolutePosition);
                                    metadata.put("contentType", contentType);
                                    metadata.put("originalFileName", pictureFileName);
                                    metadata.put("imageIndexInDoc", drawingIndex + 1);
                                    metadata.put("pageEstimate", pageEstimate);
                                    metadata.put("positionType", "drawing");
                                    metadata.put("drawingIndex", drawingIndex);
                                    metadata.put("drawingInRunIndex", drawIdx);
                                    metadata.put("pictureInDrawingIndex", picIdx);
                                    metadata.put("associationSource", "drawing_position");
                                    metadata.put("extractedAt", LocalDateTime.now().toString());

                                    imageInfo.setPageNumber(pageEstimate);
                                    imageInfo.setMetadata(metadata);
                                    imageInfo.setPosition(positionIndex);

                                    images.add(imageInfo);
                                    drawingIndex++;
                                }

                            } catch (Exception e) {
                                log.warn("提取绘图对象图片失败", e);
                            }
                        }
                    }
                }
            }
        }

        return images;
    }

    /**
     * 从运行中获取绘图对象
     */
    private List<CTDrawing> getDrawingsFromRun(XWPFRun run) {
        List<CTDrawing> drawings = new ArrayList<>();

        if (run == null) {
            return drawings;
        }

        try {
            // 通过getCTR()获取底层CTR对象
            CTR ctr = run.getCTR();
            if (ctr != null) {
                // 获取绘图对象数组
                for (CTDrawing drawing : ctr.getDrawingArray()) {
                    if (drawing != null) {
                        drawings.add(drawing);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("从运行中获取绘图对象失败", e);
        }

        return drawings;
    }

    /**
     * 从绘图对象中提取图片数据
     */
    private List<XWPFPictureData> extractPicturesFromDrawing(CTDrawing drawing, XWPFDocument document) {
        List<XWPFPictureData> pictureDataList = new ArrayList<>();

        if (drawing == null || document == null) {
            return pictureDataList;
        }

        try {
            // 方法1: 尝试从内联绘图中提取
            pictureDataList.addAll(extractPicturesFromInlineDrawings(drawing, document));

            // 方法2: 尝试从锚点绘图中提取
            pictureDataList.addAll(extractPicturesFromAnchorDrawings(drawing, document));

            // 方法3: 通过XML遍历
            pictureDataList.addAll(extractPicturesFromDrawingXML(drawing, document));

        } catch (Exception e) {
            log.debug("从绘图对象提取图片失败", e);
        }

        return pictureDataList;
    }

    /**
     * 从内联绘图中提取图片
     */
    private List<XWPFPictureData> extractPicturesFromInlineDrawings(CTDrawing drawing, XWPFDocument document) {
        List<XWPFPictureData> pictureDataList = new ArrayList<>();

        try {
            // 检查内联绘图数组
            CTInline[] inlines = drawing.getInlineArray();
            if (inlines != null) {
                for (CTInline inline : inlines) {
                    if (inline != null && inline.getGraphic() != null) {
                        // 从图形对象中提取图片
                        pictureDataList.addAll(extractPicturesFromGraphic(inline.getGraphic(), document));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("从内联绘图中提取图片失败", e);
        }

        return pictureDataList;
    }

    /**
     * 从锚点绘图中提取图片
     */
    private List<XWPFPictureData> extractPicturesFromAnchorDrawings(CTDrawing drawing, XWPFDocument document) {
        List<XWPFPictureData> pictureDataList = new ArrayList<>();

        try {
            // 检查锚点绘图数组
            CTAnchor[] anchors = drawing.getAnchorArray();
            if (anchors != null) {
                for (CTAnchor anchor : anchors) {
                    if (anchor != null && anchor.getGraphic() != null) {
                        // 从图形对象中提取图片
                        pictureDataList.addAll(extractPicturesFromGraphic(anchor.getGraphic(), document));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("从锚点绘图中提取图片失败", e);
        }

        return pictureDataList;
    }

    /**
     * 从图形对象中提取图片
     */
    private List<XWPFPictureData> extractPicturesFromGraphic(CTGraphicalObject graphic, XWPFDocument document) {
        List<XWPFPictureData> pictureDataList = new ArrayList<>();

        if (graphic == null || document == null) {
            return pictureDataList;
        }

        try {
            if (graphic.getGraphicData()!=null) {
                CTGraphicalObjectData graphicData = graphic.getGraphicData();

                // 通过XPath搜索blip元素
                XmlObject[] blips = graphicData.selectPath(
                        "declare namespace a='http://schemas.openxmlformats.org/drawingml/2006/main' " +
                                ".//a:blip"
                );

                for (XmlObject blipObj : blips) {
                    if (blipObj instanceof CTBlip) {
                        CTBlip blip = (CTBlip) blipObj;
                        String embedId = blip.getEmbed();
                        if (embedId != null && !embedId.isEmpty()) {
                            // 通过ID获取图片数据
                            XWPFPictureData pictureData = getPictureDataById(embedId, document);
                            if (pictureData != null) {
                                pictureDataList.add(pictureData);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("从图形对象中提取图片失败", e);
        }

        return pictureDataList;
    }

    /**
     * 通过XML遍历提取图片
     */
    private List<XWPFPictureData> extractPicturesFromDrawingXML(CTDrawing drawing, XWPFDocument document) {
        List<XWPFPictureData> pictureDataList = new ArrayList<>();

        if (drawing == null || document == null) {
            return pictureDataList;
        }

        try (XmlCursor cursor = drawing.newCursor()) {
            // 搜索所有的embed属性
            cursor.selectPath("declare namespace r='http://schemas.openxmlformats.org/officeDocument/2006/relationships' " +
                    ".//@r:embed");

            while (cursor.toNextSelection()) {
                XmlObject obj = cursor.getObject();
                String embedId = obj.newCursor().getTextValue();
                if (embedId != null && !embedId.isEmpty()) {
                    XWPFPictureData pictureData = getPictureDataById(embedId, document);
                    if (pictureData != null) {
                        pictureDataList.add(pictureData);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("通过XML遍历提取图片失败", e);
        }

        return pictureDataList;
    }

    /**
     * 通过ID获取图片数据
     */
    private XWPFPictureData getPictureDataById(String embedId, XWPFDocument document) {
        if (embedId == null || embedId.isEmpty() || document == null) {
            return null;
        }

        try {
            // 获取所有图片
            List<XWPFPictureData> allPictures = document.getAllPictures();

            for (XWPFPictureData pictureData : allPictures) {
                if (pictureData == null) {
                    continue;
                }

                // 比较关系ID
                String pictureFileName = pictureData.getFileName();
                if (pictureFileName != null) {
                    // 从embedId中提取数字
                    String numberId = embedId.replace("rId", "");
                    if (pictureFileName.contains(numberId)) {
                        return pictureData;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("通过ID获取图片数据失败", e);
        }

        return null;
    }

    /**
     * 构建文档结构
     */
    private Map<String, Object> buildDocumentStructure(XWPFDocument document) {
        Map<String, Object> structure = new HashMap<>();

        // 统计段落信息
        List<Map<String, Object>> paragraphsInfo = new ArrayList<>();
        List<XWPFParagraph> paragraphs = document.getParagraphs();

        for (int i = 0; i < paragraphs.size(); i++) {
            XWPFParagraph para = paragraphs.get(i);
            Map<String, Object> paraInfo = new HashMap<>();
            paraInfo.put("index", i);
            paraInfo.put("text", para.getText());
            paraInfo.put("textLength", para.getText().length());
            paraInfo.put("runCount", para.getRuns().size());
            paraInfo.put("style", para.getStyle());

            paragraphsInfo.add(paraInfo);
        }

        structure.put("paragraphs", paragraphsInfo);
        structure.put("totalParagraphs", paragraphs.size());

        // 统计表格信息
        List<Map<String, Object>> tablesInfo = new ArrayList<>();
        List<XWPFTable> tables = document.getTables();

        for (int i = 0; i < tables.size(); i++) {
            XWPFTable table = tables.get(i);
            Map<String, Object> tableInfo = new HashMap<>();
            tableInfo.put("index", i);
            tableInfo.put("rowCount", table.getNumberOfRows());
            tableInfo.put("totalCells", table.getRows().stream()
                    .mapToInt(row -> row.getTableCells().size())
                    .sum());

            tablesInfo.add(tableInfo);
        }

        structure.put("tables", tablesInfo);
        structure.put("totalTables", tables.size());

        return structure;
    }

    /**
     * 位置搜索结果类
     */
    @Getter
    @Setter
    @Builder
    private static class PositionSearchResult {
        private int paragraphIndex;
        private String paragraphText;
        private int runIndex;
        private int inlineIndex;
        private String positionType;  // paragraph_inline, table_cell, header, footer, drawing
        private Map<String, Object> tableInfo;
        private Map<String, Object> surroundingContext;
    }

    /**
     * 获取上下文信息
     */
    private Map<String, Object> getSurroundingContext(String paragraphText, String runText) {
        Map<String, Object> context = new HashMap<>();

        if (paragraphText != null && !paragraphText.isEmpty()) {
            context.put("fullParagraph", paragraphText);
            context.put("paragraphLength", paragraphText.length());

            // 提取关键词
            List<String> keywords = extractKeywordsFromText(paragraphText);
            context.put("keywords", keywords);

            // 截取片段
            int start = Math.max(0, paragraphText.length() - 100);
            String snippet = paragraphText.substring(start);
            context.put("snippet", snippet);
        }

        if (runText != null && !runText.isEmpty()) {
            context.put("runText", runText);
        }

        return context;
    }

    /**
     * 从文本中提取关键词
     */
    private List<String> extractKeywordsFromText(String text) {
        // 简单的关键词提取逻辑
        List<String> keywords = new ArrayList<>();

        if (text == null || text.length() < 10) {
            return keywords;
        }

        // 这里可以添加更复杂的关键词提取逻辑
        String[] words = text.split("[\\s,\\.，。!！?？;；:：\"'()（）\\[\\]{}]+");
        for (String word : words) {
            if (word.length() > 1 && word.length() < 20) {
                if (word.matches("[\\u4e00-\\u9fa5]+")) {
                    keywords.add(word);
                } else if (word.matches("[a-zA-Z]{3,}")) {
                    keywords.add(word.toLowerCase());
                }
            }
        }

        return keywords.stream()
                .distinct()
                .limit(10)
                .collect(Collectors.toList());
    }








    /**
     * 从绘图对象中提取图片
     */
    private List<ImageInfo> extractImagesFromDrawings(XWPFDocument document, String outputDir, String fileName) {
        List<ImageInfo> images = new ArrayList<>();
        int imageIndex = 1;

        try {
            // 遍历所有段落
            for (int paraIndex = 0; paraIndex < document.getParagraphs().size(); paraIndex++) {
                XWPFParagraph paragraph = document.getParagraphs().get(paraIndex);

                // 查找段落中的绘图对象
                for (XWPFRun run : paragraph.getRuns()) {
                    List<XWPFPicture> pictures = run.getEmbeddedPictures();

                    for (XWPFPicture picture : pictures) {
                        try {
                            XWPFPictureData pictureData = picture.getPictureData();

                            if (!isValidImageSize(pictureData.getData().length)) {
                                continue;
                            }

                            String contentType = pictureData.getPackagePart().getContentType();
                            String fileExtension = getFileExtensionFromContentType(contentType);

                            if (fileExtension.isEmpty()) {
                                fileExtension = "png";
                            }

                            ImageInfo imageInfo = saveWordImage(
                                    pictureData.getData(),
                                    outputDir,
                                    imageIndex,
                                    fileExtension,
                                    fileName
                            );

                            if (imageInfo != null) {
                                imageInfo.setPageNumber(paraIndex + 1);
                                imageInfo.setMetadata(Map.of(
                                        "source", "docx_drawing",
                                        "paragraph", paraIndex + 1,
                                        "contentType", contentType
                                ));

                                images.add(imageInfo);
                                imageIndex++;

                                if (images.size() >= 100) {
                                    return images;
                                }
                            }

                        } catch (Exception e) {
                            log.warn("提取绘图对象图片失败", e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("从绘图对象提取图片失败", e);
        }

        return images;
    }

    /**
     * 查找图片所在的段落
     */
    private int findPictureParagraph(XWPFDocument document, XWPFPictureData pictureData) {
        try {
            // 遍历所有段落查找包含此图片的段落
            for (int i = 0; i < document.getParagraphs().size(); i++) {
                XWPFParagraph paragraph = document.getParagraphs().get(i);

                for (XWPFRun run : paragraph.getRuns()) {
                    for (XWPFPicture picture : run.getEmbeddedPictures()) {
                        if (picture.getPictureData() != null &&
                                Arrays.equals(picture.getPictureData().getData(), pictureData.getData())) {
                            return i;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("查找图片所在段落失败", e);
        }

        return 0;
    }

    /**
     * 从 DOC 中提取图片
     */
    private List<ImageInfo> extractImagesFromDoc(InputStream is, String outputDir, String fileName) throws Exception {
        List<ImageInfo> images = new ArrayList<>();

        try (POIFSFileSystem fs = new POIFSFileSystem(is);
             HWPFDocument document = new HWPFDocument(fs)) {

            // 获取图片表
            PicturesTable picturesTable = document.getPicturesTable();
            Range range = document.getRange();

            int imageIndex = 1;

            // 遍历文档中的所有字符运行
            for (int i = 0; i < range.numCharacterRuns(); i++) {
                CharacterRun run = range.getCharacterRun(i);

                if (picturesTable.hasPicture(run)) {
                    Picture picture = picturesTable.extractPicture(run, true);

                    if (picture != null && picture.getSize() > 0) {
                        try {
                            // 检查图片大小
                            if (!isValidImageSize(picture.getSize())) {
                                continue;
                            }

                            // 获取图片格式
                            String format = picture.suggestFileExtension();
                            if (format == null || format.isEmpty()) {
                                format = "png";
                            }

                            // 保存图片
                            ImageInfo imageInfo = saveWordImage(
                                    picture.getContent(),
                                    outputDir,
                                    imageIndex,
                                    format,
                                    fileName
                            );

                            if (imageInfo != null) {
                                // 尝试确定图片位置
                                int paragraphIndex = findPictureParagraphIndex(range, i);
                                imageInfo.setPageNumber(paragraphIndex + 1);
                                imageInfo.setMetadata(Map.of(
                                        "source", "doc",
                                        "paragraph", paragraphIndex + 1,
                                        "format", format,
                                        "width", picture.getDxaGoal(),
                                        "height", picture.getDyaGoal()
                                ));

                                images.add(imageInfo);
                                imageIndex++;

                                if (images.size() >= 100) {
                                    break;
                                }
                            }

                        } catch (Exception e) {
                            log.warn("提取DOC图片失败: 第 {} 张", imageIndex, e);
                        }
                    }
                }

                if (images.size() >= 100) {
                    break;
                }
            }

        } catch (Exception e) {
            log.error("提取DOC图片失败", e);
        }

        return images;
    }

    /**
     * 保存 Word 图片
     */
    private ImageInfo saveWordImage(byte[] imageData, String outputDir, int imageIndex,
                                    String fileExtension, String fileName) {
        try {
            String imageName = String.format("%s_img%d.%s",
                    getBaseFileName(fileName), imageIndex, fileExtension);

            File outputFile = new File(outputDir, imageName);

            // 写入文件
            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                fos.write(imageData);
            }

            // 尝试读取图片尺寸
            int width = 0, height = 0;
            try (ByteArrayInputStream bis = new ByteArrayInputStream(imageData)) {
                BufferedImage bufferedImage = ImageIO.read(bis);
                if (bufferedImage != null) {
                    width = bufferedImage.getWidth();
                    height = bufferedImage.getHeight();
                }
            } catch (Exception e) {
                log.warn("无法读取图片尺寸: {}", imageName, e);
            }

            return ImageInfo.builder()
                    .originalName(String.format("image_%d", imageIndex))
                    .storedName(imageName)
                    .filePath(outputFile.getAbsolutePath())
                    .fileType(fileExtension)
                    .width(width)
                    .height(height)
                    .size(outputFile.length())
                    .pageNumber(1) // 默认值，后续可能会更新
                    .metadata(Map.of(
                            "source", "word",
                            "index", imageIndex,
                            "fileName", fileName
                    ))
                    .build();

        } catch (Exception e) {
            log.error("保存Word图片失败", e);
            return null;
        }
    }

    /**
     * 检查图片大小是否有效
     */
    private boolean isValidImageSize(long size) {
        return size >= minImageSize && size <= maxImageSize;
    }

    /**
     * 从内容类型获取文件扩展名
     */
    private String getFileExtensionFromContentType(String contentType) {
        if (contentType == null) {
            return "";
        }

        // 从MIME类型映射到文件扩展名
//        for (Map.Entry<String, String> entry : IMAGE_MIME_TYPES.entrySet()) {
//            if (entry.getValue().equals(contentType)) {
//                return entry.getKey();
//            }
//        }

        // 尝试从内容类型字符串中提取
        if (contentType.contains("png")) return "png";
        if (contentType.contains("jpeg") || contentType.contains("jpg")) return "jpg";
        if (contentType.contains("gif")) return "gif";
        if (contentType.contains("bmp")) return "bmp";
        if (contentType.contains("tiff")) return "tiff";
        if (contentType.contains("webp")) return "webp";
        if (contentType.contains("wmf")) return "wmf";
        if (contentType.contains("emf")) return "emf";

        return "";
    }

    /**
     * 获取文件名（不含扩展名）
     */
    private String getBaseFileName(String fileName) {
        if (fileName == null) {
            return "file";
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            return fileName.substring(0, dotIndex);
        }
        return fileName;
    }

    /**
     * 保存到临时文件
     */
    private File saveToTempFile(InputStream is, String extension) throws IOException {
        File tempFile = File.createTempFile("doc_", "." + extension);
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
            }
        }
        return tempFile;
    }

    /**
     * 查找图片所在段落索引（DOC格式）
     */
    private int findPictureParagraphIndex(Range range, int characterRunIndex) {
        try {
            // 简单实现：查找包含此字符运行的段落
            int currentPos = 0;
            for (int i = 0; i < range.numParagraphs(); i++) {
                org.apache.poi.hwpf.usermodel.Paragraph paragraph = range.getParagraph(i);
                currentPos += paragraph.text().length() + 1; // +1 用于段落结束符

                if (currentPos > characterRunIndex) {
                    return i;
                }
            }
        } catch (Exception e) {
            log.warn("查找段落索引失败", e);
        }

        return 0;
    }

    private List<ImageInfo> extractImagesFromPdf(InputStream is, String outputDir) throws Exception {
        List<ImageInfo> images = new ArrayList<>();

        try (PDDocument document = PDDocument.load(is.readAllBytes())) {
            PDFStreamEngine engine = new PDFStreamEngine() {
                @Override
                protected void processOperator(Operator operator, List<COSBase> operands) {
                    // PDF图片提取逻辑
                }
            };

            PDPageTree pages = document.getPages();
            int pageNumber = 1;

            for (PDPage page : pages) {
                PDResources resources = page.getResources();
                if (resources == null) {
                    pageNumber++;
                    continue;
                }

                for (COSName name : resources.getXObjectNames()) {
                    PDXObject xObject = resources.getXObject(name);
                    if (xObject instanceof PDImageXObject) {
                        PDImageXObject image = (PDImageXObject) xObject;

                        // 检查图片大小
                        byte[] imageData = image.getStream().toByteArray();
                        if (imageData.length < minImageSize || imageData.length > maxImageSize) {
                            continue;
                        }

                        // 保存图片
                        ImageInfo imageInfo = saveImage(image, outputDir, pageNumber);
                        if (imageInfo != null) {
                            images.add(imageInfo);
                        }
                    }
                }
                pageNumber++;
            }
        }

        return images;
    }

    private ImageInfo saveImage(PDImageXObject image, String outputDir, int pageNumber) {
        try {
            String imageName = "image_" + UUID.randomUUID().toString().substring(0, 8) + ".png";
            File outputFile = new File(outputDir, imageName);

            BufferedImage bufferedImage = image.getImage();
            ImageIO.write(bufferedImage, "png", outputFile);

            return ImageInfo.builder()
                    .originalName("pdf_image_page_" + pageNumber)
                    .storedName(imageName)
                    .filePath(outputFile.getAbsolutePath())
                    .fileType("png")
                    .width(bufferedImage.getWidth())
                    .height(bufferedImage.getHeight())
                    .size(outputFile.length())
                    .pageNumber(pageNumber)
                    .metadata(Map.of("source", "pdf", "page", pageNumber))
                    .build();

        } catch (Exception e) {
            log.error("保存PDF图片失败", e);
            return null;
        }
    }

    private ImageInfo saveImageFile(MultipartFile file, String outputDir, int pageNumber) {
        try {
            String originalName = file.getOriginalFilename();
            String fileType = getFileExtension(originalName);
            String storedName = UUID.randomUUID().toString().substring(0, 8) + "." + fileType;

            File outputFile = new File(outputDir, storedName);
            Files.copy(file.getInputStream(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

            // 读取图片尺寸
            BufferedImage bufferedImage = ImageIO.read(outputFile);
            int width = bufferedImage != null ? bufferedImage.getWidth() : 0;
            int height = bufferedImage != null ? bufferedImage.getHeight() : 0;

            return ImageInfo.builder()
                    .originalName(originalName)
                    .storedName(storedName)
                    .filePath(outputFile.getAbsolutePath())
                    .fileType(fileType)
                    .width(width)
                    .height(height)
                    .size(outputFile.length())
                    .pageNumber(pageNumber)
                    .metadata(Map.of("source", "image_file"))
                    .build();

        } catch (Exception e) {
            log.error("保存图片文件失败", e);
            return null;
        }
    }

    private String getFileExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < fileName.length() - 1) {
            return fileName.substring(lastDot + 1).toLowerCase();
        }
        return "";
    }
}