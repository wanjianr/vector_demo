package com.example.langchain.milvus.utils;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

@Component
@Slf4j
public class ManualDocumentChunker {

    // 章节标题的正则模式
    private static final List<Pattern> SECTION_PATTERNS = Arrays.asList(
            // 数字章节: 1. 简介, 1.1 概述, 1.1.1 详细介绍
            Pattern.compile("^(\\d+(\\.\\d+)*)\\s+[\\S\\s]+$"),
            // 带点的章节: • 安装步骤, • 配置选项
            Pattern.compile("^[•\\-\\*]\\s+[\\S\\s]+$"),
            // 括号章节: (一) 注意事项, (1) 操作步骤
            Pattern.compile("^[（(][一二三四五六七八九十0-9]+[）)]\\s+[\\S\\s]+$"),
            // 大写章节: 第一章 简介, 第一节 安装
            Pattern.compile("^第[一二三四五六七八九十]+[章节]\\s+[\\S\\s]+$"),
            // 项目符号: 1) 步骤, a) 配置
            Pattern.compile("^\\d+[）)]\\s+[\\S\\s]+$"),
            Pattern.compile("^[a-zA-Z][）)]\\s+[\\S\\s]+$")
    );

    // 可能的章节标题关键词
    private static final List<String> SECTION_KEYWORDS = Arrays.asList(
            "章", "节", "部分", "篇", "单元",
            "简介", "概述", "引言", "前言", "摘要",
            "安装", "配置", "设置", "部署", "搭建",
            "使用", "操作", "功能", "特性", "特点",
            "步骤", "流程", "过程", "方法", "方式",
            "注意", "警告", "提示", "说明", "备注",
            "故障", "问题", "错误", "异常", "解决",
            "维护", "保养", "检查", "测试", "验证",
            "附录", "参考", "术语", "词汇表", "索引"
    );

    // 表格和代码块的开始标记
    private static final List<String> BLOCK_STARTS = Arrays.asList(
            "|", "+", "-", "#", "```", "    ", "\t"
    );

    /**
     * 针对操作手册的智能分块策略
     */
    public List<String> chunkBySemantic(String text, Integer chunkSize, Integer overlapSize) {
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptyList();
        }

        // 如果提供了chunkSize，先按传统方式分块作为备用
        List<String> chunks = new ArrayList<>();

        if (chunkSize != null && chunkSize > 0) {
            chunks = chunkByLengthWithSemantic(text, chunkSize,
                    overlapSize != null ? overlapSize : chunkSize / 4);
        } else {
            // 智能分块
            chunks = intelligentChunking(text);
        }

        // 后处理：确保每个块都有意义
        return postProcessChunks(chunks);
    }

    /**
     * 智能分块策略
     */
    private List<String> intelligentChunking(String text) {
        List<String> chunks = new ArrayList<>();

        // 1. 按章节分割
        List<DocumentSection> sections = splitIntoSections(text);

        // 2. 处理每个章节
        for (DocumentSection section : sections) {
            if (section.getContent().length() < 50) {
                // 太短的章节，合并到上一个章节
                if (!chunks.isEmpty()) {
                    String lastChunk = chunks.get(chunks.size() - 1);
                    chunks.set(chunks.size() - 1,
                            lastChunk + "\n\n" + section.getTitle() + "\n" + section.getContent());
                } else {
                    // 第一个章节，单独处理
                    handleSmallSection(chunks, section);
                }
            } else if (section.getContent().length() > 2000) {
                // 过长的章节，需要进一步分割
                splitLargeSection(chunks, section);
            } else {
                // 正常长度的章节
                chunks.add(buildChunk(section));
            }
        }

        return chunks;
    }

    /**
     * 将文本分割成章节
     */
    private List<DocumentSection> splitIntoSections(String text) {
        List<DocumentSection> sections = new ArrayList<>();
        String[] lines = text.split("\n");

        StringBuilder currentSectionContent = new StringBuilder();
        String currentSectionTitle = "文档开始";
        int lineNumber = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            if (isSectionTitle(line, i, lines)) {
                // 保存当前章节
                if (currentSectionContent.length() > 0 ||
                        !currentSectionTitle.equals("文档开始")) {
                    sections.add(new DocumentSection(
                            currentSectionTitle,
                            currentSectionContent.toString(),
                            lineNumber
                    ));
                }

                // 开始新章节
                currentSectionTitle = line;
                currentSectionContent = new StringBuilder();
                lineNumber = i;
            } else {
                // 添加到当前章节
                if (currentSectionContent.length() > 0) {
                    currentSectionContent.append("\n");
                }
                currentSectionContent.append(lines[i]); // 保留原始格式
            }
        }

        // 添加最后一个章节
        if (currentSectionContent.length() > 0 ||
                !currentSectionTitle.equals("文档开始")) {
            sections.add(new DocumentSection(
                    currentSectionTitle,
                    currentSectionContent.toString(),
                    lineNumber
            ));
        }

        return sections;
    }

    /**
     * 判断是否为章节标题
     */
    private boolean isSectionTitle(String line, int lineIndex, String[] lines) {
        if (line.isEmpty() || line.length() > 200) {
            return false;
        }

        // 检查是否为表格行
        if (isTableRow(line)) {
            return false;
        }

        // 检查是否为代码块
        if (isCodeBlock(line, lineIndex, lines)) {
            return false;
        }

        // 检查章节模式
        for (Pattern pattern : SECTION_PATTERNS) {
            if (pattern.matcher(line).matches()) {
                return true;
            }
        }

        // 检查是否包含章节关键词
        for (String keyword : SECTION_KEYWORDS) {
            if (line.contains(keyword) && line.length() < 100) {
                // 确保不是普通文本
                String lowerLine = line.toLowerCase();
                if (!lowerLine.contains("的") && !lowerLine.contains("了") &&
                        !lowerLine.contains("在") && !lowerLine.contains("和")) {
                    return true;
                }
            }
        }

        // 检查是否为标题格式（居中、加粗等）
        if (isHeadingFormat(line, lineIndex, lines)) {
            return true;
        }

        return false;
    }

    /**
     * 判断是否为表格行
     */
    private boolean isTableRow(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("|") ||
                trimmed.startsWith("+") ||
                trimmed.startsWith("-") &&
                        trimmed.chars().filter(c -> c == '-').count() > 3;
    }

    /**
     * 判断是否为代码块
     */
    private boolean isCodeBlock(String line, int lineIndex, String[] lines) {
        if (line.startsWith("    ") || line.startsWith("\t")) {
            return true;
        }

        if (line.startsWith("```")) {
            return true;
        }

        // 检查是否为代码块的一部分
        if (lineIndex > 0 && isCodeBlock(lines[lineIndex - 1], lineIndex - 1, lines)) {
            return true;
        }

        return false;
    }

    /**
     * 判断是否为标题格式
     */
    private boolean isHeadingFormat(String line, int lineIndex, String[] lines) {
        // 检查是否单独一行且较短
        if (line.length() < 50) {
            // 检查前后是否有空行
            boolean hasSpaceBefore = lineIndex == 0 ||
                    lines[lineIndex - 1].trim().isEmpty();
            boolean hasSpaceAfter = lineIndex == lines.length - 1 ||
                    lines[lineIndex + 1].trim().isEmpty();

            if (hasSpaceBefore && hasSpaceAfter) {
                return true;
            }

            // 检查是否加粗或下划线
            if (line.startsWith("**") && line.endsWith("**")) {
                return true;
            }
            if (line.startsWith("==") && line.endsWith("==")) {
                return true;
            }
        }

        return false;
    }

    /**
     * 处理小章节
     */
    private void handleSmallSection(List<String> chunks, DocumentSection section) {
        String chunk = buildChunk(section);

        // 如果这是第一个块，或者上一个块也很小，就合并
        if (!chunks.isEmpty()) {
            String lastChunk = chunks.get(chunks.size() - 1);
            if (lastChunk.length() < 300) {
                chunks.set(chunks.size() - 1,
                        mergeChunks(lastChunk, chunk));
                return;
            }
        }

        chunks.add(chunk);
    }

    /**
     * 分割大章节
     */
    private void splitLargeSection(List<String> chunks, DocumentSection section) {
        String content = section.getContent();

        // 尝试按段落分割
        String[] paragraphs = content.split("\n\n");

        if (paragraphs.length <= 1) {
            // 没有明确段落，按句子分割
            splitBySentences(chunks, section);
            return;
        }

        StringBuilder currentPart = new StringBuilder();
        String currentTitle = section.getTitle();

        for (String paragraph : paragraphs) {
            if (paragraph.trim().isEmpty()) {
                continue;
            }

            if (currentPart.length() + paragraph.length() > 1500) {
                // 当前部分已足够大，保存
                if (currentPart.length() > 0) {
                    chunks.add(currentTitle + "\n\n" + currentPart.toString());
                    currentPart = new StringBuilder();
                    currentTitle = section.getTitle() + " (续)";
                }
            }

            if (currentPart.length() > 0) {
                currentPart.append("\n\n");
            }
            currentPart.append(paragraph);
        }

        // 添加最后一部分
        if (currentPart.length() > 0) {
            chunks.add(currentTitle + "\n\n" + currentPart.toString());
        }
    }

    /**
     * 按句子分割
     */
    private void splitBySentences(List<String> chunks, DocumentSection section) {
        String content = section.getContent();

        // 简单的句子分割
        String[] sentences = content.split("[。！？.!?]");

        StringBuilder currentPart = new StringBuilder();
        String currentTitle = section.getTitle();
        int sentenceCount = 0;

        for (String sentence : sentences) {
            String trimmed = sentence.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            if (currentPart.length() + trimmed.length() > 1500 || sentenceCount >= 10) {
                // 保存当前部分
                if (currentPart.length() > 0) {
                    chunks.add(currentTitle + "\n\n" + currentPart.toString());
                    currentPart = new StringBuilder();
                    currentTitle = section.getTitle() + " (续)";
                    sentenceCount = 0;
                }
            }

            if (currentPart.length() > 0) {
                currentPart.append("。");
            }
            currentPart.append(trimmed);
            sentenceCount++;
        }

        // 添加最后一部分
        if (currentPart.length() > 0) {
            chunks.add(currentTitle + "\n\n" + currentPart.toString());
        }
    }

    /**
     * 构建分块
     */
    private String buildChunk(DocumentSection section) {
        if (section.getTitle().isEmpty()) {
            return section.getContent();
        }
        return section.getTitle() + "\n\n" + section.getContent();
    }

    /**
     * 合并分块
     */
    private String mergeChunks(String chunk1, String chunk2) {
        if (chunk1.trim().endsWith("。") || chunk1.trim().endsWith(".") ||
                chunk1.trim().endsWith("!") || chunk1.trim().endsWith("?")) {
            return chunk1 + "\n\n" + chunk2;
        } else {
            return chunk1 + "。\n\n" + chunk2;
        }
    }

    /**
     * 后处理分块
     */
    private List<String> postProcessChunks(List<String> chunks) {
        List<String> processed = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i).trim();

            if (chunk.isEmpty()) {
                continue;
            }

            // 清理空白字符
            chunk = chunk.replaceAll("\\s+", " ")
                    .replaceAll("\n{3,}", "\n\n")
                    .trim();

            // 确保块有最小长度
            if (chunk.length() < 20) {
                if (i < chunks.size() - 1) {
                    // 合并到下一个块
                    chunks.set(i + 1, chunk + "\n\n" + chunks.get(i + 1));
                } else if (i > 0) {
                    // 合并到上一个块
                    processed.set(processed.size() - 1,
                            processed.get(processed.size() - 1) + "\n\n" + chunk);
                } else {
                    // 只有一个块，保留
                    processed.add(chunk);
                }
            } else {
                // 添加章节标记
                if (chunks.size() > 1) {
                    chunk = String.format("【第%d部分/%d】\n%s",
                            i + 1, chunks.size(), chunk);
                }
                processed.add(chunk);
            }
        }

        return processed;
    }

    /**
     * 备用：基于长度的分块（带语义考虑）
     */
    private List<String> chunkByLengthWithSemantic(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();

        if (text.length() <= chunkSize) {
            chunks.add(text);
            return chunks;
        }

        int start = 0;
        while (start < text.length()) {
            int end = start + chunkSize;

            if (end >= text.length()) {
                chunks.add(text.substring(start));
                break;
            }

            // 在句子边界处截断
            end = findGoodBreakPoint(text, end);

            String chunk = text.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }

            // 重叠
            start = Math.max(start + 1, end - overlap);
        }

        return chunks;
    }

    /**
     * 寻找好的断点
     */
    private int findGoodBreakPoint(String text, int position) {
        if (position >= text.length()) {
            return text.length();
        }

        // 优先在段落边界断开
        for (int i = position; i > position - 100 && i >= 0; i--) {
            if (i < text.length() - 2 &&
                    text.charAt(i) == '\n' && text.charAt(i + 1) == '\n') {
                return i;
            }
        }

        // 在句子边界断开
        for (int i = position; i > position - 50 && i >= 0; i--) {
            if (i < text.length() && isSentenceEnd(text.charAt(i))) {
                return i + 1;
            }
        }

        // 在词语边界断开
        for (int i = position; i > position - 30 && i >= 0; i--) {
            if (i < text.length() && Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }

        return position;
    }

    private boolean isSentenceEnd(char c) {
        return c == '。' || c == '.' || c == '!' || c == '?' ||
                c == '；' || c == ';' || c == '：' || c == ':';
    }

    /**
     * 章节信息类
     */
    @Getter
    @AllArgsConstructor
    private static class DocumentSection {
        private String title;
        private String content;
        private int startLine;
    }
}
