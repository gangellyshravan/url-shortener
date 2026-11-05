package com.urlshortener.data;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcLinkDataProvider implements LinkDataProvider {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ReentrantLock writeLock = new ReentrantLock();

    public JdbcLinkDataProvider(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
        @Retryable(retryFor = TransientDataAccessException.class, maxAttempts = 5,
            backoff = @Backoff(delay = 250, multiplier = 2, maxDelay = 2000))
    public void addLink(LinkEntity link) {
        writeLock.lock();
        try {
            transactions.executeWithoutResult(status -> jdbc.update(
                    "INSERT INTO links (code, url, created, expires, disabled) VALUES (?, ?, ?, ?, 0)",
                    link.code(), link.url(), link.created(), link.expires()));
        } catch (DuplicateKeyException exception) {
            throw new LinkCodeConflictException(exception);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
        @Retryable(retryFor = TransientDataAccessException.class, maxAttempts = 5,
            backoff = @Backoff(delay = 250, multiplier = 2, maxDelay = 2000))
    public LinkResolution resolveAndTrack(String code, long now, String utcDay) {
        writeLock.lock();
        try {
            return transactions.execute(status -> {
                var links = jdbc.query("SELECT url, expires, disabled FROM links WHERE code = ?",
                        (result, row) -> new LinkRow(result.getString("url"),
                                (Long) result.getObject("expires"), result.getBoolean("disabled")), code);
                if (links.isEmpty()) {
                    return new LinkResolution(LinkResolution.Status.NOT_FOUND, null);
                }
                var link = links.get(0);
                if (link.disabled() || (link.expires() != null && link.expires() <= now)) {
                    return new LinkResolution(LinkResolution.Status.EXPIRED_OR_DISABLED, null);
                }

                int updated = jdbc.update("UPDATE clicks SET click_count = click_count + 1 WHERE code = ? AND day = ?", code, utcDay);
                if (updated == 0) {
                    jdbc.update("INSERT INTO clicks (code, day, click_count) VALUES (?, ?, 1)", code, utcDay);
                }
                return new LinkResolution(LinkResolution.Status.FOUND, link.url());
            });
        } finally {
            writeLock.unlock();
        }
    }

    @Override
        @Retryable(retryFor = TransientDataAccessException.class, maxAttempts = 5,
            backoff = @Backoff(delay = 250, multiplier = 2, maxDelay = 2000))
    public Optional<Map<String, Long>> getDailyClicks(String code) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM links WHERE code = ?", Integer.class, code) == 0) {
            return Optional.empty();
        }
        var daily = new LinkedHashMap<String, Long>();
        jdbc.query("SELECT day, click_count FROM clicks WHERE code = ? ORDER BY day", result -> {
            daily.put(result.getString("day"), result.getLong("click_count"));
        }, code);
        return Optional.of(daily);
    }

    @Override
        @Retryable(retryFor = TransientDataAccessException.class, maxAttempts = 5,
            backoff = @Backoff(delay = 250, multiplier = 2, maxDelay = 2000))
    public boolean disable(String code) {
        writeLock.lock();
        try {
            return Boolean.TRUE.equals(transactions.execute(
                status -> jdbc.update("UPDATE links SET disabled = 1 WHERE code = ?", code) > 0));
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public boolean canConnect() {
        try {
            return jdbc.queryForObject("SELECT 1", Integer.class) == 1;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private record LinkRow(String url, Long expires, boolean disabled) {
    }

    public static class LinkCodeConflictException extends RuntimeException {
        public LinkCodeConflictException(Throwable cause) {
            super(cause);
        }
    }
}