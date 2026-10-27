package com.example.syncgamesave.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@NoArgsConstructor
public class S3Config {
    private String bucket = "";
    @ToString.Exclude
    private String accessKey = "";
    @ToString.Exclude
    private String secretKey = "";
    // Intentionally empty: native Qiniu endpoints cannot be reused as S3 endpoints.
    private String endpoint = "";
    private String region = "";
    private boolean pathStyle = true;
}
