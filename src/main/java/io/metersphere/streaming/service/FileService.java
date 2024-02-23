package io.metersphere.streaming.service;

import io.metersphere.streaming.base.domain.FileMetadata;
import io.metersphere.streaming.base.domain.FileMetadataWithBLOBs;
import io.metersphere.streaming.base.mapper.FileMetadataMapper;
import io.metersphere.streaming.commons.utils.LogUtil;
import io.metersphere.streaming.config.MinioProperties;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.apache.commons.io.FileUtils;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;

@Service
public class FileService {
    @Resource
    private FileMetadataMapper fileMetadataMapper;
    @Resource
    private MinioClient minioClient;
    @Resource
    private MinioProperties minioProperties;

    public FileMetadata saveFile(File file, String projectId, String reportId) {
        String bucket = minioProperties.getBucket();
        String fileName = projectId + "/" + file.getName();
        try (
                InputStream inputStream = FileUtils.openInputStream(file)
        ) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket) // 存储桶
                    .object(fileName) // 文件名
                    .stream(inputStream, file.length(), -1) // 文件内容
//                .contentType(file.getContentType()) // 文件类型
                    .build());
        } catch (Exception e) {
            LogUtil.error("上传JTL失败: ", e);
        }


        final FileMetadataWithBLOBs fileMetadata = new FileMetadataWithBLOBs();
        fileMetadata.setId(reportId);
        fileMetadata.setName(file.getName());
        fileMetadata.setSize(FileUtils.sizeOf(file));
        fileMetadata.setCreateTime(System.currentTimeMillis());
        fileMetadata.setUpdateTime(System.currentTimeMillis());
        fileMetadata.setType("ZIP");
        fileMetadata.setStorage("MINIO");
        fileMetadata.setProjectId(projectId);
        if (fileMetadataMapper.updateByPrimaryKeySelective(fileMetadata) == 0) {
            fileMetadataMapper.insert(fileMetadata);
        }

        return fileMetadata;
    }

}