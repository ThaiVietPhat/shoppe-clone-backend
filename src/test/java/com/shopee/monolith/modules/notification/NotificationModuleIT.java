package com.shopee.monolith.modules.notification;

import com.shopee.monolith.BasePostgresRedisIntegrationTest;
import com.shopee.monolith.common.security.EventPayloadCryptoService;
import com.shopee.monolith.modules.notification.scheduler.EventPublicationRetryScheduler;
import com.shopee.monolith.modules.notification.service.EmailService;
import com.shopee.monolith.modules.user.event.UserRegisteredEvent;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

class NotificationModuleIT extends BasePostgresRedisIntegrationTest {

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EventPayloadCryptoService cryptoService;

    @Autowired
    private EventPublicationRetryScheduler retryScheduler;

    @MockitoSpyBean
    private EmailService emailService;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM event_publication");
    }

    @Test
    void whenRegistrationCommitsShouldDeliverEmailAsync() throws Exception {
        UUID userId = UUID.randomUUID();
        String rawToken = "rawToken123";
        String encryptedToken = cryptoService.encrypt(rawToken);
        String email = "buyer@shoppe.local";

        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            latch.countDown();
            return null;
        }).when(emailService).sendVerificationEmail(anyString(), anyString());

        // Publish event inside committed transaction
        transactionTemplate.executeWithoutResult(status -> {
            eventPublisher.publishEvent(new UserRegisteredEvent(userId, email, encryptedToken));
        });

        // Wait for async listener execution
        boolean completed = latch.await(5, TimeUnit.SECONDS);
        assertTrue(completed, "Async email listener did not execute");

        Mockito.verify(emailService).sendVerificationEmail(email, "http://localhost:3000/verify-email?token=" + rawToken);

        // Verify Modulith event publication completed in DB
        boolean dbUpdated = false;
        for (int i = 0; i < 50; i++) {
            Integer completedCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM event_publication WHERE completion_date IS NOT NULL", Integer.class);
            if (completedCount != null && completedCount == 1) {
                dbUpdated = true;
                break;
            }
            Thread.sleep(100);
        }
        assertTrue(dbUpdated, "Modulith event publication completion was not recorded in DB");
    }

    @Test
    void whenRegistrationRollsBackShouldNotDeliverEmail() throws Exception {
        UUID userId = UUID.randomUUID();
        String encryptedToken = cryptoService.encrypt("rawToken123");
        String email = "buyer@shoppe.local";

        try {
            transactionTemplate.executeWithoutResult(status -> {
                eventPublisher.publishEvent(new UserRegisteredEvent(userId, email, encryptedToken));
                throw new RuntimeException("Simulated registration rollback");
            });
        } catch (Exception e) {
            // expected rollback
        }

        // Wait to verify it didn't run
        Thread.sleep(1000);
        // Scoped to this test's own email/token: emailService is a shared @MockitoSpyBean across
        // IT classes, and other suites (e.g. AuthControllerRegistrationIT) trigger the real async
        // verification-email listener for their own users. Asserting anyString()/anyString() here
        // made this test flaky whenever one of those unrelated async deliveries landed during the
        // 1s wait window.
        Mockito.verify(emailService, Mockito.never())
                .sendVerificationEmail(eq(email), eq("http://localhost:3000/verify-email?token=rawToken123"));

        Integer publicationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM event_publication", Integer.class);
        assertEquals(0, publicationCount);
    }

    @Test
    void whenSmtpFailsShouldNotRollbackRegistrationButLogFailureInModulith() throws Exception {
        UUID userId = UUID.randomUUID();
        String encryptedToken = cryptoService.encrypt("rawToken123");
        String email = "buyer@shoppe.local";

        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            latch.countDown();
            throw new RuntimeException("SMTP delivery failed");
        }).when(emailService).sendVerificationEmail(anyString(), anyString());

        // Registration transaction completes successfully
        Boolean txOutcome = transactionTemplate.execute(status -> {
            eventPublisher.publishEvent(new UserRegisteredEvent(userId, email, encryptedToken));
            return true;
        });

        assertTrue(txOutcome);

        // Wait for async execution
        latch.await(5, TimeUnit.SECONDS);

        // Verify transaction was committed, but event publication remains incomplete in DB
        boolean dbUpdated = false;
        for (int i = 0; i < 50; i++) {
            Integer incompleteCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM event_publication WHERE completion_date IS NULL", Integer.class);
            if (incompleteCount != null && incompleteCount == 1) {
                dbUpdated = true;
                break;
            }
            Thread.sleep(100);
        }
        assertTrue(dbUpdated, "Modulith event publication was not recorded as incomplete in DB");
    }

    @Test
    void eventToStringShouldNotExposeTokenDetails() {
        UUID userId = UUID.randomUUID();
        UserRegisteredEvent event = new UserRegisteredEvent(userId, "test@example.com", "crypto.payload");
        String eventStr = event.toString();

        assertFalse(eventStr.contains("crypto.payload"));
        assertTrue(eventStr.contains("[REDACTED]"));
    }

    @Test
    void retrySchedulerWhenNoIncompletePublicationsShouldNotThrow() {
        // Regression guard for P1: the scheduler catches all exceptions internally,
        // so assertThatCode().doesNotThrowAnyException() is always green even when
        // UnknownEntityException occurs. Instead, capture Logback output and assert
        // no ERROR was emitted — that ERROR is the only observable side-effect of the bug.
        Logger schedulerLogger = (Logger) LoggerFactory.getLogger(EventPublicationRetryScheduler.class);
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        schedulerLogger.addAppender(listAppender);

        try {
            retryScheduler.retryFailedPublications();
        } finally {
            schedulerLogger.detachAppender(listAppender);
        }

        boolean hasError = listAppender.list.stream()
                .anyMatch(event -> event.getLevel() == Level.ERROR);
        assertFalse(hasError,
                "Retry scheduler must not log ERROR: UnknownEntityException would indicate " +
                "that DefaultJpaEventPublication is not registered in the JPA persistence unit. " +
                "Check META-INF/orm.xml.");
    }
}
