package com.example.langchain.milvus.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class DocumentChunker {

    @Value("${app.document.chunk-size:1000}")
    private Integer defaultChunkSize = 1000;

    @Value("${app.document.overlap-size:200}")
    private Integer defaultOverlapSize = 200;

    public List<String> chunkBySemantic(String text, Integer chunkSize, Integer overlapSize) {
        if (!StringUtils.hasText(text)) {
            return new ArrayList<>();
        }

        if (chunkSize == null) chunkSize = defaultChunkSize;
        if (overlapSize == null) overlapSize = defaultOverlapSize;

        List<String> chunks = new ArrayList<>();

        // 按段落分割
        String[] paragraphs = text.split("\\n\\s*\\n");
        StringBuilder currentChunk = new StringBuilder();
        int currentSize = 0;

        for (String paragraph : paragraphs) {
            String trimmedPara = paragraph.trim();
            if (trimmedPara.isEmpty()) {
                continue;
            }

            int paraLength = trimmedPara.length();

            // 如果段落太长，进一步分割
            if (paraLength > chunkSize) {
                // 先保存当前块
                if (currentSize > 0) {
                    chunks.add(currentChunk.toString().trim());
                }

                // 分割长段落
                List<String> subChunks = splitLongParagraph(trimmedPara, chunkSize, overlapSize);
                chunks.addAll(subChunks);

                // 重新开始
                currentChunk = new StringBuilder();
                currentSize = 0;

            } else if (currentSize + paraLength <= chunkSize) {
                // 添加到当前块
                if (currentSize > 0) {
                    currentChunk.append("\n\n");
                }
                currentChunk.append(trimmedPara);
                currentSize += paraLength;

            } else {
                // 保存当前块，开始新块
                if (currentSize > 0) {
                    chunks.add(currentChunk.toString().trim());

                    // 添加重叠
                    if (overlapSize > 0 && chunks.size() > 1) {
                        String lastChunk = chunks.get(chunks.size() - 2);
                        int overlapStart = Math.max(0, lastChunk.length() - overlapSize);
                        String overlapText = lastChunk.substring(overlapStart);
                        currentChunk = new StringBuilder(overlapText);
                        currentSize = overlapText.length();
                    } else {
                        currentChunk = new StringBuilder();
                        currentSize = 0;
                    }

                    // 添加当前段落
                    if (currentSize > 0) {
                        currentChunk.append("\n\n");
                    }
                    currentChunk.append(trimmedPara);
                    currentSize += paraLength;
                }
            }
        }

        // 添加最后一个块
        if (currentSize > 0) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }

    private List<String> splitLongParagraph(String paragraph, int chunkSize, int overlapSize) {
        List<String> chunks = new ArrayList<>();

        if (paragraph.length() <= chunkSize) {
            chunks.add(paragraph);
            return chunks;
        }

        int start = 0;
        while (start < paragraph.length()) {
            int end = Math.min(start + chunkSize, paragraph.length());

            // 尝试在句子边界分割
            if (end < paragraph.length()) {
                int sentenceEnd = findSentenceBoundary(paragraph, end);
                if (sentenceEnd > start) {
                    end = sentenceEnd;
                }
            }

            String chunk = paragraph.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }

            start = end - overlapSize;
            if (start < 0) {
                start = 0;
            }

            // 避免死循环
            if (start >= paragraph.length()) {
                break;
            }
        }

        return chunks;
    }

    private int findSentenceBoundary(String text, int targetPos) {
        if (targetPos >= text.length()) {
            return text.length();
        }

        // 查找标点符号
        int searchStart = Math.max(0, targetPos - 100);
        int searchEnd = Math.min(text.length(), targetPos + 100);

        String searchText = text.substring(searchStart, searchEnd);

        Pattern pattern = Pattern.compile("[。！？.!?]");
        Matcher matcher = pattern.matcher(searchText);

        int lastMatch = -1;
        while (matcher.find()) {
            int matchPos = matcher.start() + searchStart;
            if (matchPos <= targetPos) {
                lastMatch = matchPos;
            } else {
                break;
            }
        }

        if (lastMatch >= 0 && lastMatch > searchStart) {
            return lastMatch + 1; // 包含标点符号
        }

        // 如果没有找到标点，在空格处分隔
        int spacePos = text.lastIndexOf(' ', targetPos);
        if (spacePos > targetPos - 50) { // 不要太远
            return spacePos;
        }

        return targetPos;
    }
}