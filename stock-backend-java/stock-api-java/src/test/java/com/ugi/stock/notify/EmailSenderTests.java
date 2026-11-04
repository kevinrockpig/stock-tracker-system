package com.ugi.stock.notify;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.assertThat;

class EmailSenderTests {

    @Test
    void chineseNameSubjectAndBodyEncodedAsUtf8() throws Exception {
        // 空白的顯示名稱使用預設值（application.properties 以 ISO-8859-1 讀取，中文預設值寫在程式中）
        EmailSender sender = new EmailSender(new JavaMailSenderImpl(), "notify@example.test", "");
        MimeMessage message = sender.message("me@example.test", "股票提醒 11/4：今日無提醒", "今天沒有需要留意的項目。");
        message.saveChanges();

        InternetAddress from = (InternetAddress) message.getFrom()[0];
        assertThat(from.getAddress()).isEqualTo("notify@example.test");
        assertThat(from.getPersonal()).isEqualTo(EmailSender.DEFAULT_FROM_NAME);
        assertThat(message.getSubject()).isEqualTo("股票提醒 11/4：今日無提醒");
        assertThat(message.getContentType()).contains("text/plain").containsIgnoringCase("UTF-8");
        assertThat(message.getContent()).isEqualTo("今天沒有需要留意的項目。");
        assertThat(new EmailSender(new JavaMailSenderImpl(), "", "").configured()).isFalse();
    }
}
