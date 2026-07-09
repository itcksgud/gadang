package com.gadang.common.storage;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

/**
 * S3 호환 오브젝트 스토리지 저장 (gadang.storage.mode=s3).
 *
 * 로컬/데모는 compose의 MinIO를 가리키고, 운영 전환 시 S3_ENDPOINT만 비우면
 * 같은 코드가 AWS S3로 붙는다 (SDK가 리전 기본 엔드포인트 사용).
 *
 * 이미지 조회는 앱 서버를 거치지 않고 브라우저 → 스토리지 직행:
 * 업로드 시 public-read 버킷의 절대 URL을 반환한다 (운영에선 이 앞에 CDN).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "gadang.storage.mode", havingValue = "s3")
public class S3ImageStorage implements ImageStorage {

    @Value("${gadang.s3.endpoint:}")
    private String endpoint;           // MinIO 등 호환 스토리지 주소. 비우면 AWS S3.

    @Value("${gadang.s3.region:ap-northeast-2}")
    private String region;

    @Value("${gadang.s3.bucket:gadang-images}")
    private String bucket;

    @Value("${gadang.s3.access-key:}")
    private String accessKey;

    @Value("${gadang.s3.secret-key:}")
    private String secretKey;

    /** 브라우저가 접근하는 공개 URL 프리픽스 — 컨테이너 내부 주소(endpoint)와 다를 수 있다 */
    @Value("${gadang.s3.public-base-url:}")
    private String publicBaseUrl;

    private S3Client s3;

    @PostConstruct
    void init() {
        var builder = S3Client.builder().region(Region.of(region));
        if (!endpoint.isBlank()) {
            // MinIO는 가상 호스트 방식(bucket.host) DNS가 없으므로 path-style 필수
            builder.endpointOverride(URI.create(endpoint))
                    .forcePathStyle(true);
        }
        if (!accessKey.isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey)));
        }
        s3 = builder.build();
        ensureBucket();
    }

    /** 버킷이 없으면 생성하고 이미지 GET만 공개하는 정책을 건다 (게시글 이미지는 공개 콘텐츠) */
    private void ensureBucket() {
        try {
            s3.headBucket(b -> b.bucket(bucket));
        } catch (NoSuchBucketException e) {
            try {
                s3.createBucket(b -> b.bucket(bucket));
            } catch (BucketAlreadyOwnedByYouException ignored) {
                // 동시 기동 경합 — 이미 있으면 그대로 사용
            }
            s3.putBucketPolicy(b -> b.bucket(bucket).policy("""
                    {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":"*",
                    "Action":["s3:GetObject"],"Resource":["arn:aws:s3:::%s/*"]}]}
                    """.formatted(bucket)));
            log.info("[S3] bucket '{}' 생성 + public-read GetObject 정책 적용", bucket);
        }
    }

    @Override
    public String store(String filename, String contentType, byte[] bytes) {
        s3.putObject(b -> b.bucket(bucket).key(filename).contentType(contentType),
                RequestBody.fromBytes(bytes));
        String base = publicBaseUrl.isBlank()
                ? defaultPublicBase()
                : publicBaseUrl;
        return base + "/" + filename;
    }

    private String defaultPublicBase() {
        if (!endpoint.isBlank()) {
            return endpoint + "/" + bucket;                        // MinIO path-style
        }
        return "https://" + bucket + ".s3." + region + ".amazonaws.com"; // AWS virtual-host
    }

    @PreDestroy
    void close() {
        if (s3 != null) s3.close();
    }
}
