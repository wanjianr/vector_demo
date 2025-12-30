package com.example.langchain.milvus.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.FileOutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Component
@Slf4j
public class ImageExtractorWithPosition {

    public List<DocumentParserWithStructure.ImageInfo> extractImages(MultipartFile file, String outputDir) throws Exception {
        String fileName = file.getOriginalFilename().toLowerCase();

        if (fileName.endsWith(".docx")) {
            return extractImagesFromDocx(file, outputDir);
        }
        return Collections.emptyList();
    }

    private List<DocumentParserWithStructure.ImageInfo> extractImagesFromDocx(MultipartFile file, String outputDir) throws Exception {
        List<DocumentParserWithStructure.ImageInfo> images = new ArrayList<>();

        try (XWPFDocument doc = new XWPFDocument(file.getInputStream())) {
            int imageIndex = 0;

            // 1. 提取文档中的图片
            List<XWPFPictureData> pictureDataList = doc.getAllPictures();
            for (XWPFPictureData pictureData : pictureDataList) {
                String imageName = "image_" + imageIndex + "." + pictureData.suggestFileExtension();
                Path imagePath = Paths.get(outputDir, imageName);

                // 保存图片
                try (FileOutputStream fos = new FileOutputStream(imagePath.toFile())) {
                    fos.write(pictureData.getData());
                }

                DocumentParserWithStructure.ImageInfo imageInfo = new DocumentParserWithStructure.ImageInfo();
                imageInfo.setIndex(imageIndex);
                imageInfo.setFileName(imageName);
                imageInfo.setFilePath(imagePath.toString());
                imageInfo.setFormat(pictureData.suggestFileExtension());
                imageInfo.setData(pictureData.getData());

                images.add(imageInfo);
                imageIndex++;
            }

            // 2. 记录图片在文档中的位置
            int paraIndex = 0;
            for (XWPFParagraph para : doc.getParagraphs()) {
                for (XWPFRun run : para.getRuns()) {
                    for (XWPFPicture picture : run.getEmbeddedPictures()) {
                        // 找到对应的ImageInfo
                        DocumentParserWithStructure.ImageInfo imageInfo = findImageInfoByData(picture.getPictureData().getData(), images);
                        if (imageInfo != null) {
                            DocumentParserWithStructure.ImagePosition position = new DocumentParserWithStructure.ImagePosition();
                            position.setParagraphIndex(paraIndex);
//                            position.setParagraphId(para..getId());
                            position.setRunIndex(para.getRuns().indexOf(run));
                            position.setParagraphText(para.getText());
                            imageInfo.setPosition(position);
                        }
                    }
                }
                paraIndex++;
            }
        }

        return images;
    }

    private DocumentParserWithStructure.ImageInfo findImageInfoByData(byte[] data, List<DocumentParserWithStructure.ImageInfo> images) {
        for (DocumentParserWithStructure.ImageInfo image : images) {
            if (Arrays.equals(data, image.getData())) {
                return image;
            }
        }
        return null;
    }
}
