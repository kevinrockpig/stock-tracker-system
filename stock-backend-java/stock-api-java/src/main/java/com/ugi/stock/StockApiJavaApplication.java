package com.ugi.stock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class StockApiJavaApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(StockApiJavaApplication.class, args);
        // 通知模式（--app.notify.run-once=true）：NotifyOnceRunner 寄出當天的通知後結束，不繼續提供網站
        if (context.getEnvironment().getProperty("app.notify.run-once", Boolean.class, false)) {
            System.exit(SpringApplication.exit(context));
        }
    }

}
