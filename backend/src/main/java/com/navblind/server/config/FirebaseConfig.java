package com.navblind.server.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
@EnableConfigurationProperties(FirebaseProperties.class)
public class FirebaseConfig {

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);

    private final FirebaseProperties props;

    public FirebaseConfig(FirebaseProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void initialize() throws IOException {
        if (props.disabled()) {
            log.info("[Firebase] 비활성화 모드 — X-User-Id 헤더로 인증 대체");
            return;
        }

        if (FirebaseApp.getApps().isEmpty()) {
            InputStream credentials = resolveCredentials(props.credentialsPath());
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(credentials))
                    .build();
            FirebaseApp.initializeApp(options);
            log.info("[Firebase] Admin SDK 초기화 완료");
        }
    }

    private InputStream resolveCredentials(String path) throws IOException {
        // 절대경로 또는 상대경로(backend/ 기준) 파일 우선 시도
        Path filePath = Path.of(path);
        if (Files.exists(filePath)) {
            return new FileInputStream(filePath.toFile());
        }
        // classpath 폴백
        InputStream cp = getClass().getClassLoader().getResourceAsStream(path);
        if (cp != null) return cp;

        throw new IOException("Firebase 키 파일을 찾을 수 없습니다: " + path);
    }
}
