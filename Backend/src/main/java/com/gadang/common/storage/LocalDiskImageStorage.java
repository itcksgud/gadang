package com.gadang.common.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 로컬 디스크 저장 (기본 모드) — WebMvcConfig가 {uploadDir}를 /files/** 로 정적 서빙.
 *
 * 한계(문서화): 인스턴스 로컬이라 수평 확장 시 파일이 갈라진다.
 * 운영은 gadang.storage.mode=s3 로 전환 — compose는 uploads 볼륨으로 유실만 방지.
 */
@Component
@ConditionalOnProperty(name = "gadang.storage.mode", havingValue = "local", matchIfMissing = true)
public class LocalDiskImageStorage implements ImageStorage {

    @Value("${gadang.upload.dir:uploads}")
    private String uploadDir;

    @Override
    public String store(String filename, String contentType, byte[] bytes) {
        try {
            Path dir = Paths.get(uploadDir);
            Files.createDirectories(dir);
            Files.write(dir.resolve(filename), bytes);
            return "/files/" + filename;
        } catch (IOException e) {
            throw new UncheckedIOException("이미지 저장 실패: " + filename, e);
        }
    }
}
