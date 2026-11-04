package com.ugi.stock.notify;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 通知模式（--app.notify.run-once=true）：啟動後寄出當天的通知，接著由 main 結束程式；
 * 有任何一封寄送失敗時 exit code 為 1（GitHub Actions 會標示失敗並寄信通知）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.notify.run-once", havingValue = "true")
public class NotifyOnceRunner implements ApplicationRunner, ExitCodeGenerator {

    private final NotificationJob job;
    private final EmailSender emailSender;
    private int exitCode;

    public NotifyOnceRunner(NotificationJob job, EmailSender emailSender) {
        this.job = job;
        this.emailSender = emailSender;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!emailSender.configured()) {
            log.warn("未設定 MAIL_USERNAME，不寄出通知");
            return;
        }
        NotificationJob.Result result = job.run();
        exitCode = result.failed() > 0 ? 1 : 0;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
