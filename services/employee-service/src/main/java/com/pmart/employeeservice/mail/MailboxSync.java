package com.pmart.employeeservice.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.pmart.employeeservice.employee.EmployeeRepository;

/**
 * 부팅 시 IAM 계정이 있는 재직 직원의 메일함을 모두 확인해 없으면 만듭니다.
 * (메일 기능 추가 전에 등록된 직원, 등록 중 메일함 생성에 실패한 직원도 이 동기화로 메일함을 갖게 됨)
 */
@Component
public class MailboxSync implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MailboxSync.class);

    private final EmployeeRepository repository;
    private final MailboxProvisioner mailboxes;

    public MailboxSync(EmployeeRepository repository, MailboxProvisioner mailboxes) {
        this.repository = repository;
        this.mailboxes = mailboxes;
    }

    @Override
    public void run(ApplicationArguments args) {
        long created = repository.findAll().stream()
                .filter(e -> e.iamId() != null && "ACTIVE".equals(e.status()) && "COMPLETED".equals(e.provisioningStatus()))
                .filter(e -> mailboxes.ensureMailbox(e.iamId()))
                .count();
        log.info("직원 메일함 동기화 완료: 새로 만든 메일함 {}개", created);
    }
}
