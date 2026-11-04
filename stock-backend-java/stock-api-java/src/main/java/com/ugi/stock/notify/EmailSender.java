package com.ugi.stock.notify;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 以 SMTP 寄出純文字 Email（預設 Gmail，見 application.properties 的 spring.mail.*），寄件者為 MAIL_USERNAME。
 */
@Component
public class EmailSender implements NotificationSender {

    static final String DEFAULT_FROM_NAME = "股票追蹤系統";

    private final JavaMailSender mailSender;
    private final String from;
    private final String fromName;

    public EmailSender(JavaMailSender mailSender,
                       @Value("${spring.mail.username}") String from,
                       @Value("${app.notify.from-name}") String fromName) {
        this.mailSender = mailSender;
        this.from = from;
        this.fromName = fromName.isBlank() ? DEFAULT_FROM_NAME : fromName;
    }

    /** 已設定寄件帳號（未設定時通知模式不寄信） */
    public boolean configured() {
        return !from.isBlank();
    }

    @Override
    public String channel() {
        return "email";
    }

    @Override
    public void send(String to, String subject, String body) throws Exception {
        mailSender.send(message(to, subject, body));
    }

    MimeMessage message(String to, String subject, String body) throws Exception {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, StandardCharsets.UTF_8.name());
        helper.setFrom(new InternetAddress(from, fromName, StandardCharsets.UTF_8.name()));
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(body, false);
        return message;
    }
}
