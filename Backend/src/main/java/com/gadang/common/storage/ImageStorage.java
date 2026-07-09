package com.gadang.common.storage;

/**
 * 게시글 첨부 이미지 저장소 추상화.
 *
 * 구현체는 gadang.storage.mode 프로퍼티로 선택된다:
 *   - local (기본): 앱 서버 디스크 — 개발용. 수평 확장 시 인스턴스 간 파일이 갈라지는 한계.
 *   - s3: S3 호환 오브젝트 스토리지 — 로컬은 MinIO, 운영은 엔드포인트만 AWS S3로 교체.
 */
public interface ImageStorage {

    /**
     * 검증이 끝난 이미지를 저장하고 브라우저가 접근할 수 있는 URL을 반환한다.
     *
     * @param filename    저장 파일명 (서버가 생성한 UUID + 확장자 — 클라이언트 파일명 아님)
     * @param contentType 매직 바이트로 판별된 실제 MIME 타입
     * @return 상대 경로(/files/..) 또는 절대 URL(S3)
     */
    String store(String filename, String contentType, byte[] bytes);
}
