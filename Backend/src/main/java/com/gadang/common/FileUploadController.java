package com.gadang.common;

import com.gadang.common.exception.GadangException;
import com.gadang.common.response.ApiResponse;
import com.gadang.common.storage.ImageStorage;
import com.gadang.common.storage.UploadValidator;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 게시글 첨부 이미지 업로드 — POST /api/files/upload
 * 반환: { url: "..." }  (local: /files/uuid.jpg 정적 경로, s3: 스토리지 절대 URL)
 *
 * 파일명·Content-Type 등 클라이언트 신고값은 신뢰하지 않는다 —
 * 타입은 매직 바이트로 판별하고 확장자도 거기서 유도한다 (UploadValidator).
 */
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileUploadController {

    private final ImageStorage imageStorage;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<UploadResult> upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.getSize() > UploadValidator.MAX_SIZE) {
            throw GadangException.badRequest("파일 크기는 10MB를 초과할 수 없습니다.");
        }
        byte[] bytes = file.getBytes();
        String contentType = UploadValidator.detectImageType(bytes);
        String filename = UUID.randomUUID().toString().replace("-", "")
                + UploadValidator.extensionFor(contentType);
        return ApiResponse.ok(new UploadResult(imageStorage.store(filename, contentType, bytes)));
    }

    public record UploadResult(String url) {}
}
