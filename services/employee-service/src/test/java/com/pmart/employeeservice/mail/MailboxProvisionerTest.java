package com.pmart.employeeservice.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 메일함 생성 (D22): 메일 서버 계정 파일에 한 줄 추가, 멱등, 비밀번호는 아무도 모르는 값의 해시 */
class MailboxProvisionerTest {

    @TempDir
    Path dir;

    @Test
    void 계정_파일에_메일_주소와_SHA512_CRYPT_해시를_추가한다() throws Exception {
        Path file = dir.resolve("postfix-accounts.cf");
        var provisioner = new MailboxProvisioner(file.toString());

        assertThat(provisioner.ensureMailbox("gildong.hong@pmart.test")).isTrue();

        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(1);
        // Docker Mailserver 형식: 주소|{SCHEME}해시
        assertThat(lines.get(0)).matches("gildong\\.hong@pmart\\.test\\|\\{SHA512-CRYPT}\\$6\\$[A-Za-z0-9./]{16}\\$[A-Za-z0-9./]{86}");
    }

    @Test
    void 이미_있으면_다시_추가하지_않는다() throws Exception {
        Path file = dir.resolve("postfix-accounts.cf");
        var provisioner = new MailboxProvisioner(file.toString());

        provisioner.ensureMailbox("gildong.hong@pmart.test");
        assertThat(provisioner.ensureMailbox("gildong.hong@pmart.test")).isFalse();
        assertThat(provisioner.ensureMailbox("GILDONG.HONG@pmart.test")).as("대소문자만 다른 주소").isFalse();

        assertThat(Files.readAllLines(file)).hasSize(1);
    }

    @Test
    void 접두어가_같은_다른_주소는_별도_메일함() throws Exception {
        Path file = dir.resolve("postfix-accounts.cf");
        var provisioner = new MailboxProvisioner(file.toString());

        provisioner.ensureMailbox("gildong@pmart.test.extra");
        assertThat(provisioner.ensureMailbox("gildong@pmart.test")).isTrue();

        assertThat(Files.readAllLines(file)).hasSize(2);
    }

    @Test
    void 같은_주소라도_매번_다른_무작위_비밀번호_해시() throws Exception {
        Path a = dir.resolve("a.cf");
        Path b = dir.resolve("b.cf");
        new MailboxProvisioner(a.toString()).ensureMailbox("x@pmart.test");
        new MailboxProvisioner(b.toString()).ensureMailbox("x@pmart.test");

        assertThat(Files.readString(a)).isNotEqualTo(Files.readString(b));
    }

    @Test
    void 계정_파일_경로가_비어_있으면_아무것도_하지_않는다() {
        assertThat(new MailboxProvisioner("").ensureMailbox("x@pmart.test")).isFalse();
    }
}
