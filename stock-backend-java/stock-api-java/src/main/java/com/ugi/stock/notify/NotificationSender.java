package com.ugi.stock.notify;

/**
 * 寄出一則通知（目前為 Email；之後的 LINE 另做一個實作）。測試以假的實作取代，不會真的寄出。
 */
public interface NotificationSender {

    /** 管道代碼，與 notification_log.channel 相同 */
    String channel();

    void send(String to, String subject, String body) throws Exception;
}
