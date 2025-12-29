package com.example.langchain.milvus.utils;

import com.example.langchain.milvus.component.TikaDocxReader;
import com.example.langchain.milvus.dto.model.ImageInfo;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xwpf.usermodel.BodyElementType;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.UnderlinePatterns;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.tika.Tika;
import org.openxmlformats.schemas.officeDocument.x2006.extendedProperties.CTProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class DocumentParser {

    @Autowired
    private TikaDocxReader tikaDocxReader;

    // 辅助类
    @Data
    public static class DocumentContent {
        private String text;
        private List<Paragraph> paragraphs;
        private DocumentStructure structure;
    }

    @Data
    public static class DocumentStructure {
        private List<Paragraph> paragraphs = new ArrayList<>();
        private Map<Integer, List<ImageInfo>> paragraphImages = new HashMap<>();
        private List<BookmarkInfo> bookmarks = new ArrayList<>();
        private Map<String, Object> properties = new HashMap<>();
        private Integer totalPages;
        private Integer totalSheets;
    }

    @Data
    public static class Paragraph {
        private int id;
        private String text;
        private int startPos;
        private int endPos;
        private int elementIndex;
        private String type = "normal";
        private int level = 0;
        private Integer listLevel;
        private Integer pageNumber;
        private Map<String, Object> style = new HashMap<>();
        private List<RunInfo> runs = new ArrayList<>();
    }

    @Data
    public class TextChunkWithContext {
        private int chunkIndex;
        private String text;
        private int startParagraphId;
        private int endParagraphId;
        private int startCharIndex;
        private int endCharIndex;
        private List<Paragraph> paragraphs = new ArrayList<>();
        private int wordCount;
        private String type;
        private int totalChunks;
    }

    @Data
    public static class RunInfo {
        private int index;
        private String text;
        private String fontFamily;
        private Integer fontSize;
        private boolean bold;
        private boolean italic;
        private boolean underlined;
    }

    @Data
    public static class BookmarkInfo {
        private String title;
        private int pageNumber;
        private int level;
        private List<BookmarkInfo> children = new ArrayList<>();
    }

    /**
     * 解析文档并提取结构信息
     */
    public DocumentContent parseDocumentWithStructure(MultipartFile file) throws Exception {
        String fileName = file.getOriginalFilename().toLowerCase();

        try {
            if (fileName.endsWith(".docx")) {
                return parseDocxWithStructure(file);
            }
//            else if (fileName.endsWith(".doc")) {
//                return parseDocWithStructure(file);
//            } else if (fileName.endsWith(".pdf")) {
//                return parsePdfWithStructure(file);
//            } else if (fileName.endsWith(".txt") || fileName.endsWith(".md")) {
//                return parseTextWithStructure(file);
//            } else if (fileName.endsWith(".html") || fileName.endsWith(".htm")) {
//                return parseHtmlWithStructure(file);
//            } else if (fileName.endsWith(".ppt") || fileName.endsWith(".pptx")) {
//                return parsePresentationWithStructure(file);
//            } else if (fileName.endsWith(".xls") || fileName.endsWith(".xlsx")) {
//                return parseExcelWithStructure(file);
//            } else {
//                return parseGenericWithStructure(file);
//            }
        } catch (Exception e) {
            log.error("解析文档结构失败: {}", fileName, e);
            // 回退到基本解析
//            return parseFallback(file);
        }
        return null;
    }

    /**
     * 解析DOCX文档结构
     */
    private DocumentContent parseDocxWithStructure(MultipartFile file) throws Exception {
        DocumentContent content = new DocumentContent();
        DocumentStructure structure = new DocumentStructure();

        try (XWPFDocument document = new XWPFDocument(file.getInputStream())) {
            StringBuilder fullText = new StringBuilder();
            List<Paragraph> paragraphs = new ArrayList<>();
            int charPosition = 0;

            // 处理文档主体
            List<IBodyElement> bodyElements = document.getBodyElements();

            for (int i = 0; i < bodyElements.size(); i++) {
                IBodyElement element = bodyElements.get(i);

                if (element.getElementType() == BodyElementType.PARAGRAPH) {
                    XWPFParagraph xwpfPara = (XWPFParagraph) element;
                    Paragraph paragraph = parseXWPFParagraph(xwpfPara, i, charPosition, paragraphs.size());

                    if (!paragraph.getText().trim().isEmpty()) {
                        paragraphs.add(paragraph);
                        fullText.append(paragraph.getText()).append("\n\n");
                        charPosition = fullText.length();
                    }

                } else if (element.getElementType() == BodyElementType.TABLE) {
                    Paragraph tableParagraph = parseXWPFTable((XWPFTable) element, i, charPosition, paragraphs.size());

                    if (!tableParagraph.getText().trim().isEmpty()) {
                        paragraphs.add(tableParagraph);
                        fullText.append(tableParagraph.getText()).append("\n\n");
                        charPosition = fullText.length();
                    }
                } else if (element.getElementType() == BodyElementType.CONTENTCONTROL) {
                    // 处理内容控件
                    IBodyElement control = element;
                    String controlText = extractControlText(control);
                    if (controlText != null && !controlText.trim().isEmpty()) {
                        Paragraph para = new Paragraph();
                        para.setId(paragraphs.size());
                        para.setText(controlText);
                        para.setStartPos(charPosition);
                        para.setEndPos(charPosition + controlText.length());
                        para.setType("content_control");
                        paragraphs.add(para);
                        fullText.append(controlText).append("\n\n");
                        charPosition = fullText.length();
                    }
                }
            }

            // 处理页眉页脚
            List<XWPFHeader> headers = document.getHeaderList();
            for (XWPFHeader header : headers) {
                for (XWPFParagraph headerPara : header.getParagraphs()) {
                    Paragraph paragraph = parseXWPFParagraph(headerPara, paragraphs.size(), charPosition, paragraphs.size());
                    if (!paragraph.getText().trim().isEmpty()) {
                        paragraph.setType("header");
                        paragraphs.add(paragraph);
                        fullText.append("[页眉] ").append(paragraph.getText()).append("\n\n");
                        charPosition = fullText.length();
                    }
                }
            }

            List<XWPFFooter> footers = document.getFooterList();
            for (XWPFFooter footer : footers) {
                for (XWPFParagraph footerPara : footer.getParagraphs()) {
                    Paragraph paragraph = parseXWPFParagraph(footerPara, paragraphs.size(), charPosition, paragraphs.size());
                    if (!paragraph.getText().trim().isEmpty()) {
                        paragraph.setType("footer");
                        paragraphs.add(paragraph);
                        fullText.append("[页脚] ").append(paragraph.getText()).append("\n\n");
                        charPosition = fullText.length();
                    }
                }
            }

            // 提取文档属性
            Map<String, Object> docProperties = extractDocxProperties(document);

            content.setText(fullText.toString().trim());
            content.setParagraphs(paragraphs);
            structure.setParagraphs(paragraphs);
            structure.setProperties(docProperties);
            content.setStructure(structure);

        } catch (Exception e) {
            log.error("解析DOCX文档结构失败", e);
            throw e;
        }

        return content;
    }

    private Map<String, Object> extractDocxProperties(XWPFDocument doc) {
        Map<String, Object> properties = new HashMap<>();

        // 核心属性
        if (doc.getProperties() != null) {
            POIXMLProperties.CoreProperties ctProps = doc.getProperties().getCoreProperties();
            if (ctProps != null) {
                if (ctProps.getTitle() != null) {
                    properties.put("title", ctProps.getTitle());
                }
                if (ctProps.getSubject() != null) {
                    properties.put("subject", ctProps.getSubject());
                }
                if (ctProps.getCreator() != null) {
                    properties.put("creator", ctProps.getCreator());
                }
                if (ctProps.getDescription() != null) {
                    properties.put("description", ctProps.getDescription());
                }
            }
        }

        // 扩展属性
        if (doc.getProperties() != null && doc.getProperties().getExtendedProperties() != null) {
            try {
                // 通过反射获取扩展属性
                POIXMLProperties.ExtendedProperties extProps = doc.getProperties().getExtendedProperties();
                if (extProps != null) {
                    // 获取底层属性对象
                    org.apache.poi.ooxml.POIXMLProperties.ExtendedProperties underlying =
                            (org.apache.poi.ooxml.POIXMLProperties.ExtendedProperties)
                                    getFieldValue(extProps, "underlying");

                    if (underlying != null) {
                        // 通过反射获取属性
                        try {
                            Method getApplicationMethod = underlying.getClass().getMethod("getApplication");
                            String application = (String) getApplicationMethod.invoke(underlying);
                            putIfNotNull(properties, "application", application);
                        } catch (Exception e) {
                            // 忽略
                        }

                        try {
                            Method getCompanyMethod = underlying.getClass().getMethod("getCompany");
                            String company = (String) getCompanyMethod.invoke(underlying);
                            putIfNotNull(properties, "company", company);
                        } catch (Exception e) {
                            // 忽略
                        }

                        try {
                            Method getPagesMethod = underlying.getClass().getMethod("getPages");
                            Long pages = (Long) getPagesMethod.invoke(underlying);
                            if (pages != null) {
                                properties.put("pages", pages.intValue());
                            }
                        } catch (Exception e) {
                            // 忽略
                        }

                        try {
                            Method getWordsMethod = underlying.getClass().getMethod("getWords");
                            Long words = (Long) getWordsMethod.invoke(underlying);
                            if (words != null) {
                                properties.put("words", words.intValue());
                            }
                        } catch (Exception e) {
                            // 忽略
                        }

                        try {
                            Method getCharactersMethod = underlying.getClass().getMethod("getCharacters");
                            Long characters = (Long) getCharactersMethod.invoke(underlying);
                            if (characters != null) {
                                properties.put("characters", characters.intValue());
                            }
                        } catch (Exception e) {
                            // 忽略
                        }
                    }

                    // 使用公开的方法
                    try {
                        putIfNotNull(properties, "app_version", extProps.getApplication());
                    } catch (Exception e) {
                        // 忽略
                    }

                    try {
                        putIfNotNull(properties, "total_time_editing", extProps.getTotalTime());
                    } catch (Exception e) {
                        // 忽略
                    }

                    try {
                        putIfNotNull(properties, "pages", extProps.getPages());
                    } catch (Exception e) {
                        // 忽略
                    }

                    try {
                        putIfNotNull(properties, "words", extProps.getWords());
                    } catch (Exception e) {
                        // 忽略
                    }

                    try {
                        putIfNotNull(properties, "characters", extProps.getCharacters());
                    } catch (Exception e) {
                        // 忽略
                    }

                    try {
                        putIfNotNull(properties, "characters_with_spaces", extProps.getCharactersWithSpaces());
                    } catch (Exception e) {
                        // 忽略
                    }

                    try {
                        putIfNotNull(properties, "company", extProps.getCompany());
                    } catch (Exception e) {
                        // 忽略
                    }

                    try {
                        putIfNotNull(properties, "manager", extProps.getManager());
                    } catch (Exception e) {
                        // 忽略
                    }
                }
            } catch (Exception e) {
                log.warn("提取扩展属性失败: {}", e.getMessage());
            }
        }

        return properties;
    }

    /**
     * 辅助方法
     */
    private void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null && !value.toString().trim().isEmpty()) {
            map.put(key, value.toString().trim());
        }
    }

    private Object getFieldValue(Object obj, String fieldName) {
        try {
            Field field = obj.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(obj);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractControlText(IBodyElement control) {
        // 简化实现，实际需要根据文档类型处理
        return null;
    }

    /**
     * 解析DOCX段落
     */
    private Paragraph parseXWPFParagraph(XWPFParagraph xwpfPara, int elementIndex, int charPosition, int paraIndex) {
        String text = xwpfPara.getText();
        if (text == null) {
            text = "";
        }

        Paragraph paragraph = new Paragraph();
        paragraph.setId(paraIndex);
        paragraph.setText(text);
        paragraph.setStartPos(charPosition);
        paragraph.setEndPos(charPosition + text.length());
        paragraph.setElementIndex(elementIndex);

        // 检测段落类型
        String paraType = detectParagraphType(xwpfPara, text);
        paragraph.setType(paraType);

        // 提取样式信息
        Map<String, Object> style = extractParagraphStyle(xwpfPara);
        paragraph.setStyle(style);

        // 检测标题级别
        int level = detectHeadingLevel(xwpfPara, text);
        paragraph.setLevel(level);

        // 检测列表项
        if (xwpfPara.getNumIlvl() != null) {
            paragraph.setListLevel(Integer.parseInt(xwpfPara.getNumIlvl().toString()));
            paragraph.setType("list_item");
        }

        // 提取运行信息
        List<RunInfo> runs = extractRunInfo(xwpfPara);
        paragraph.setRuns(runs);

        return paragraph;
    }

    private String detectParagraphType(XWPFParagraph para, String text) {
        if (text == null || text.trim().isEmpty()) {
            return "empty";
        }

        // 检查样式
        String style = para.getStyle();
        if (style != null) {
            if (style.toLowerCase().contains("title") ||
                    style.toLowerCase().contains("heading")) {
                return "heading";
            }
        }

        // 检查文本特征
        String trimmed = text.trim();
        if (trimmed.length() < 150 && trimmed.endsWith(":")) {
            return "heading";
        }

        if (trimmed.matches("^[0-9]+[、.]\\s.*") ||
                trimmed.matches("^[一二三四五六七八九十]+[、.]\\s.*")) {
            return "list_item";
        }

        if (trimmed.matches("^[•●○■□◆◇▶▷◀◁▪▫]\\s.*")) {
            return "list_item";
        }

        if (trimmed.matches("^Table\\s+\\d+:.*") ||
                trimmed.matches("^表\\s*\\d+[:：].*")) {
            return "table_caption";
        }

        if (trimmed.matches("^Figure\\s+\\d+:.*") ||
                trimmed.matches("^图\\s*\\d+[:：].*")) {
            return "figure_caption";
        }

        return "normal";
    }

    private int detectHeadingLevel(XWPFParagraph para, String text) {
        String style = para.getStyle();
        if (style != null) {
            if (style.toLowerCase().contains("heading1") ||
                    style.toLowerCase().contains("title")) {
                return 1;
            } else if (style.toLowerCase().contains("heading2")) {
                return 2;
            } else if (style.toLowerCase().contains("heading3")) {
                return 3;
            } else if (style.toLowerCase().contains("heading4")) {
                return 4;
            } else if (style.toLowerCase().contains("heading5")) {
                return 5;
            } else if (style.toLowerCase().contains("heading6")) {
                return 6;
            }
        }

        // 根据文本特征判断
        String trimmed = text.trim();
        if (trimmed.length() < 100) {
            if (trimmed.matches("^第[一二三四五六七八九十]+章.*") ||
                    trimmed.matches("^[0-9]+\\.\\s+.*") && !trimmed.contains(".")) {
                return 1;
            } else if (trimmed.matches("^[0-9]+\\.[0-9]+\\s+.*")) {
                return 2;
            } else if (trimmed.matches("^[0-9]+\\.[0-9]+\\.[0-9]+\\s+.*")) {
                return 3;
            }
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

    private String detectPdfParagraphType(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "empty";
        }

        String trimmed = text.trim();

        // 检测标题
        if (trimmed.matches("^[0-9]+\\.[0-9]*\\s+[A-Z].*") ||
                trimmed.matches("^[A-Z][A-Z\\s]{0,50}$") ||
                trimmed.matches("^第[一二三四五六七八九十]+章\\s+.*") ||
                trimmed.matches("^[一二三四五六七八九十]+、.*") ||
                (trimmed.length() < 150 && trimmed.endsWith(":"))) {
            return "heading";
        }

        // 检测列表项
        if (trimmed.matches("^[•●○■□◆◇▶▷◀◁▪▫]\\s.*") ||
                trimmed.matches("^[0-9]+[、.]\\s.*") ||
                trimmed.matches("^[a-zA-Z][、.]\\s.*")) {
            return "list_item";
        }

        // 检测图表标题
        if (trimmed.matches("^(图|表|Figure|Table|Figure|Fig\\.|表)\\s*[0-9]+.*")) {
            return "caption";
        }

        return "normal";
    }

    /**
     * 解析DOCX表格
     */
    private Paragraph parseXWPFTable(XWPFTable table, int elementIndex, int charPosition, int paraIndex) {
        StringBuilder tableText = new StringBuilder();
        List<List<String>> tableData = new ArrayList<>();

        for (XWPFTableRow row : table.getRows()) {
            List<String> rowData = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                String cellText = cell.getText();
                rowData.add(cellText);
                tableText.append(cellText).append("\t");
            }
            tableData.add(rowData);
            tableText.append("\n");
        }

        Paragraph paragraph = new Paragraph();
        paragraph.setId(paraIndex);
        paragraph.setText(tableText.toString().trim());
        paragraph.setStartPos(charPosition);
        paragraph.setEndPos(charPosition + tableText.length());
        paragraph.setType("table");
        paragraph.setElementIndex(elementIndex);

        // 表格元数据
        Map<String, Object> style = new HashMap<>();
        style.put("table", true);
        style.put("rows", table.getRows().size());
        style.put("columns", table.getRows().isEmpty() ? 0 : table.getRow(0).getTableCells().size());
        style.put("table_data", tableData);
        paragraph.setStyle(style);

        return paragraph;
    }






    /**
     * 基础的文本内容解析方法
     * @param file
     * @return
     * @throws Exception
     */
    public String parseDocument(MultipartFile file) throws Exception {
        String fileName = file.getOriginalFilename();
        String fileType = getFileExtension(fileName);

        try (InputStream is = file.getInputStream()) {
            switch (fileType.toLowerCase()) {
                case "pdf":
                    return parsePdf(is);
                case "docx":
                    return parseDocx(is);
                case "doc":
                    return parseDoc(is);
                case "xlsx":
                case "xls":
                    return parseExcel(is);
                case "txt":
                    return parseText(is);
                default:
                    throw new Exception("不支持的文档格式: " + fileType);
            }
        }
    }

    private String parsePdf(InputStream is) throws Exception {
        try {
            // 方法1：使用 Loader.loadPDF() （推荐）
            try (PDDocument document = PDDocument.load(is.readAllBytes())) {
                PDFTextStripper stripper = new PDFTextStripper();
                return stripper.getText(document);
            }

        } catch (InvalidPasswordException e) {
            log.warn("PDF文件有密码保护，尝试无密码解析");

        } catch (Exception e) {
            log.error("PDF解析失败，尝试备用方法", e);
        }
        return "";
    }

    private String parseDocx(InputStream is) throws Exception {
        return tikaDocxReader.readDocxWithTika(is);
//        try (XWPFDocument document = new XWPFDocument(is)) {
//            XWPFWordExtractor extractor = new XWPFWordExtractor(document);
//            return extractor.getText();
//        }
    }

    private String parseDoc(InputStream is) throws Exception {
        try (HWPFDocument document = new HWPFDocument(is)) {
            WordExtractor extractor = new WordExtractor(document);
            return extractor.getText();
        }
    }

    private String parseExcel(InputStream is) throws Exception {
        StringBuilder text = new StringBuilder();
        DataFormatter formatter = new DataFormatter();

        try (Workbook workbook = new XSSFWorkbook(is)) {
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheetAt(i);
                text.append("工作表: ").append(sheet.getSheetName()).append("\n");

                for (org.apache.poi.ss.usermodel.Row row : sheet) {
                    for (org.apache.poi.ss.usermodel.Cell cell : row) {
                        String cellValue = formatter.formatCellValue(cell);
                        text.append(cellValue).append("\t");
                    }
                    text.append("\n");
                }
                text.append("\n");
            }
        }

        return text.toString();
    }

    private String parseText(InputStream is) throws Exception {
        return new String(is.readAllBytes());
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
