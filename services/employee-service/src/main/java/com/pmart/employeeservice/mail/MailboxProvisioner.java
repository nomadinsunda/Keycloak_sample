package com.pmart.employeeservice.mail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Locale;

import org.apache.commons.codec.digest.Crypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 직원 메일함 생성 (D22).
 * Docker Mailserver는 메일을 받으려면 계정이 미리 있어야 하므로, IAM 계정 ID(= 메일 주소)로 메일 서버 계정을 만듭니다.
 * 메일 서버의 계정 파일(postfix-accounts.cf)에 한 줄을 추가하면 메일 서버가 변경을 감지해 메일함을 만듭니다.
 *
 * 메일 로그인은 Keycloak 토큰(OAuth2)으로만 하므로 비밀번호는 아무도 모르는 무작위 값의 해시를 넣습니다.
 * (테스트 프로젝트용 단순 방식: 공유 볼륨의 파일을 직접 수정. 운영이라면 SCIM·LDAP 등 정식 프로비저닝 필요)
 */
@Component
public class MailboxProvisioner {

    private static final Logger log = LoggerFactory.getLogger(MailboxProvisioner.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String SALT_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789./";

    private final Path accountsFile;
    private final boolean enabled;

    public MailboxProvisioner(@Value("${pmart.mail.accounts-file:}") String accountsFile) {
        this.enabled = !accountsFile.isBlank();
        this.accountsFile = enabled ? Path.of(accountsFile) : null;
    }

    /** 메일함이 없으면 만듭니다. 이미 있으면 아무것도 하지 않습니다(멱등). */
    public synchronized boolean ensureMailbox(String address) {
        if (!enabled) {
            return false;
        }
        String mail = address.toLowerCase(Locale.ROOT);
        try {
            if (Files.exists(accountsFile)
                    && Files.readAllLines(accountsFile).stream().anyMatch(l -> l.startsWith(mail + "|"))) {
                return false;
            }
            byte[] unusablePassword = new byte[32];
            RANDOM.nextBytes(unusablePassword);
            String hash = Crypt.crypt(unusablePassword, "$6$" + salt());
            Files.writeString(accountsFile, mail + "|{SHA512-CRYPT}" + hash + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.info("메일함 생성 요청: {}", mail);
            return true;
        } catch (IOException e) {
            throw new IllegalStateException("메일함 생성 실패: " + mail, e);
        }
    }

    private static String salt() {
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(SALT_CHARS.charAt(RANDOM.nextInt(SALT_CHARS.length())));
        }
        return sb.toString();
    }
}
