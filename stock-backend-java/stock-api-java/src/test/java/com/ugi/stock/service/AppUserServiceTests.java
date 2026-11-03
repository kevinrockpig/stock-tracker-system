package com.ugi.stock.service;

import com.ugi.stock.entity.AppUser;
import com.ugi.stock.entity.UserIdentity;
import com.ugi.stock.repository.AppUserRepository;
import com.ugi.stock.repository.UserIdentityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppUserServiceTests {

    private AppUserRepository users;
    private UserIdentityRepository identities;
    private AppUserService service;

    @BeforeEach
    void setUp() {
        users = mock(AppUserRepository.class);
        identities = mock(UserIdentityRepository.class);
        service = new AppUserService(users, identities);
        when(users.save(any())).thenAnswer(call -> withId(call.getArgument(0), 7L));
        when(identities.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    private static AppUser withId(AppUser user, long id) {
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @Test
    void firstLoginCreatesUserAndIdentity() {
        when(identities.findByProviderAndSubject("google", "sub-1")).thenReturn(Optional.empty());
        when(users.findByEmail("me@example.com")).thenReturn(Optional.empty());

        long id = service.recordLogin("google", "sub-1", "Me@Example.com", "Me");

        assertThat(id).isEqualTo(7L);
        verify(users).save(any(AppUser.class));
        verify(identities).save(any(UserIdentity.class));
    }

    @Test
    void sameIdentityReturnsSameUser() {
        AppUser user = withId(new AppUser("me@example.com", "Old"), 3L);
        UserIdentity identity = new UserIdentity(user, "google", "sub-1");
        when(identities.findByProviderAndSubject("google", "sub-1")).thenReturn(Optional.of(identity));

        long id = service.recordLogin("google", "sub-1", "me@example.com", "New");

        assertThat(id).isEqualTo(3L);
        assertThat(user.getDisplayName()).isEqualTo("New");
        assertThat(user.getLastLoginAt()).isNotNull();
        assertThat(identity.getLastLoginAt()).isNotNull();
        verify(users, never()).save(any());
        verify(identities, never()).save(any());
    }

    @Test
    void newLoginMethodWithSameEmailJoinsExistingUser() {
        AppUser user = withId(new AppUser("me@example.com", "Me"), 3L);
        when(identities.findByProviderAndSubject("google", "sub-2")).thenReturn(Optional.empty());
        when(users.findByEmail("me@example.com")).thenReturn(Optional.of(user));

        long id = service.recordLogin("google", "sub-2", "ME@example.com", "Me");

        assertThat(id).isEqualTo(3L);
        verify(users, never()).save(any());
        verify(identities).save(any(UserIdentity.class));
    }
}
