package com.example.syncgamesave.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@NoArgsConstructor
public class QiniuConfig {
    private String bucket = "";
    @ToString.Exclude
    private String accessKey = "";
    @ToString.Exclude
    private String secretKey = "";
    private String uploadEndpoint = "https://upload.qiniup.com";
    private String resourceEndpoint = "https://rs.qiniuapi.com";
    private String listEndpoint = "https://rsf.qiniuapi.com";
    private String downloadDomain = "";
    private boolean privateBucket = true;
}

