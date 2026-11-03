package com.ugi.stock.service;

import com.ugi.stock.entity.AppUser;
import com.ugi.stock.entity.UserIdentity;
import com.ugi.stock.repository.AppUserRepository;
import com.ugi.stock.repository.UserIdentityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AppUserService {

    private final AppUserRepository userRepository;
    private final UserIdentityRepository identityRepository;

    /**
     * 登入成功時呼叫：找出（或建立）對應的使用者並記錄登入時間，回傳使用者 ID。
     * <p>
     * 同一個登入方式（provider + subject）已登入過 → 同一位使用者。第一次以這個方式登入時，若已有相同 email 的使用者，
     * 新增登入方式並歸到該使用者；呼叫端必須確認 email 已由登入方式驗證（Google 的 email_verified），
     * 否則別人可以用你的 email 註冊而看到你的資料。
     */
    @Transactional
    public long recordLogin(String provider, String subject, String verifiedEmail, String displayName) {
        Instant now = Instant.now();
        UserIdentity identity = identityRepository.findByProviderAndSubject(provider, subject).orElse(null);
        AppUser user;
        if (identity != null) {
            user = identity.getUser();
        } else {
            String email = verifiedEmail.toLowerCase(Locale.ROOT);
            user = userRepository.findByEmail(email).orElseGet(() -> userRepository.save(new AppUser(email, displayName)));
            identity = identityRepository.save(new UserIdentity(user, provider, subject));
        }
        identity.recordLogin(now);
        user.recordLogin(displayName, now);
        return user.getId();
    }
}
