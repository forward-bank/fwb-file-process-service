package com.forward.s3;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * Downloads files from S3 (or LocalStack) given a bucket-relative file path.
 * Identical in behaviour to the same class in fwb-syntax-validation-service.
 */
@Component
public class S3FileDownloader {

    private final S3Client s3Client;
    private final String   bucket;

    public S3FileDownloader(S3Client s3Client,
                            @Value("${aws.s3.bucket:fwb-payments-dev}") String bucket) {
        this.s3Client = s3Client;
        this.bucket   = bucket;
    }

    /**
     * Downloads the object at the given bucket-relative path and returns its bytes.
     *
     * @param fileS3Path  S3 object key, e.g.
     *     {@code FWB_DIRECT_DEBIT/PAYMENT_FILES/2026/02/04/INCOMING/file.xml}
     */
    public byte[] download(String fileS3Path) {
        if (fileS3Path == null || fileS3Path.isBlank()) {
            throw new S3DownloadException("fileS3Path must not be null or blank");
        }
        String key = fileS3Path.startsWith("/") ? fileS3Path.substring(1) : fileS3Path;

        System.out.println("  [S3FileDownloader] downloading s3://" + bucket + "/" + key);
        try {
            ResponseBytes<GetObjectResponse> response = s3Client.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(key).build());
            byte[] bytes = response.asByteArray();
            System.out.println("  [S3FileDownloader] ✓ downloaded " + bytes.length + " bytes");
            return bytes;
        } catch (NoSuchKeyException e) {
            throw new S3DownloadException("S3 object not found: s3://" + bucket + "/" + key, e);
        } catch (Exception e) {
            throw new S3DownloadException(
                    "Failed to download s3://" + bucket + "/" + key + ": " + e.getMessage(), e);
        }
    }

    public static class S3DownloadException extends RuntimeException {
        public S3DownloadException(String message) { super(message); }
        public S3DownloadException(String message, Throwable cause) { super(message, cause); }
    }
}
