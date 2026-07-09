package com.gadang.common.storage;

import com.gadang.common.exception.GadangException;
import java.util.Map;

/**
 * 업로드 이미지 검증 — 클라이언트가 보낸 값(파일명·Content-Type)은 신뢰하지 않는다.
 *
 * 확장자·MIME 타입은 파일 앞부분 매직 바이트로 직접 판별한다.
 * (Content-Type: image/png 으로 속인 x.html 업로드 → /files/uuid.html 저장·서빙
 *  → stored XSS 가 되는 구멍을 막는다. /files/** 는 permitAll GET 이다.)
 */
public final class UploadValidator {

    public static final long MAX_SIZE = 10 * 1024 * 1024L; // 10 MB

    private static final Map<String, String> EXTENSION_BY_TYPE = Map.of(
            "image/jpeg", ".jpg",
            "image/png", ".png",
            "image/gif", ".gif",
            "image/webp", ".webp"
    );

    private UploadValidator() {}

    /**
     * 크기·매직 바이트를 검증하고 판별된 실제 MIME 타입을 반환한다.
     *
     * @throws GadangException(400) 빈 파일, 크기 초과, 이미지가 아닌 내용
     */
    public static String detectImageType(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw GadangException.badRequest("파일이 비어 있습니다.");
        }
        if (bytes.length > MAX_SIZE) {
            throw GadangException.badRequest("파일 크기는 10MB를 초과할 수 없습니다.");
        }
        String type = sniff(bytes);
        if (type == null) {
            throw GadangException.badRequest("이미지 파일(JPEG·PNG·GIF·WebP)만 업로드 가능합니다.");
        }
        return type;
    }

    /** 판별된 MIME 타입에 대응하는 확장자 — 클라이언트 파일명은 사용하지 않는다. */
    public static String extensionFor(String contentType) {
        String ext = EXTENSION_BY_TYPE.get(contentType);
        if (ext == null) {
            throw GadangException.badRequest("지원하지 않는 이미지 타입: " + contentType);
        }
        return ext;
    }

    private static String sniff(byte[] b) {
        if (b.length >= 3
                && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length >= 8
                && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            return "image/png";
        }
        if (b.length >= 6
                && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8'
                && (b[4] == '7' || b[4] == '9') && b[5] == 'a') {
            return "image/gif";
        }
        if (b.length >= 12
                && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return null;
    }
}
