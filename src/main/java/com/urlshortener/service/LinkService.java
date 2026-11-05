package com.urlshortener.service;

import com.urlshortener.contracts.LinkStats;
import com.urlshortener.data.JdbcLinkDataProvider.LinkCodeConflictException;
import com.urlshortener.data.LinkDataProvider;
import com.urlshortener.data.LinkEntity;
import com.urlshortener.data.LinkResolution;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.security.SecureRandom;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class LinkService {
    private static final Pattern ALIAS = Pattern.compile("^[A-Za-z0-9_-]{4,32}$");
    private static final Pattern IPV4_LITERAL = Pattern.compile("[0-9.]+");
    private static final DateTimeFormatter UTC_DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final LinkDataProvider dataProvider;

    public LinkService(LinkDataProvider dataProvider) {
        this.dataProvider = dataProvider;
    }

    public String create(String url, String alias, Long ttlSeconds) {
        validateUrl(url);
        if (alias != null && !ALIAS.matcher(alias).matches()) {
            throw new ApiException(400, "Alias must contain 4-32 letters, digits, underscores or hyphens");
        }
        if (ttlSeconds != null && (ttlSeconds < 1 || ttlSeconds > 31_536_000)) {
            throw new ApiException(400, "ttl_seconds must be between 1 and 31536000");
        }

        for (int attempt = 0; attempt < 5; attempt++) {
            long now = Instant.now().getEpochSecond();
            var link = new LinkEntity(alias == null ? generateCode() : alias, url, now,
                    ttlSeconds == null ? null : now + ttlSeconds);
            try {
                dataProvider.addLink(link);
                return link.code();
            } catch (LinkCodeConflictException exception) {
                if (alias != null) {
                    throw new ApiException(409, "Alias already exists");
                }
            }
        }
        throw new ApiException(503, "Could not allocate code");
    }

    public String resolve(String code) {
        var resolution = dataProvider.resolveAndTrack(code, Instant.now().getEpochSecond(), UTC_DAY.format(Instant.now()));
        return switch (resolution.status()) {
            case FOUND -> resolution.url();
            case NOT_FOUND -> throw new ApiException(404, "Link not found");
            case EXPIRED_OR_DISABLED -> throw new ApiException(410, "Link expired or disabled");
        };
    }

    public LinkStats getStats(String code) {
        Map<String, Long> daily = dataProvider.getDailyClicks(code)
                .orElseThrow(() -> new ApiException(404, "Link not found"));
        return new LinkStats(code, daily.values().stream().mapToLong(Long::longValue).sum(), daily);
    }

    public void disable(String code) {
        if (!dataProvider.disable(code)) {
            throw new ApiException(404, "Link not found");
        }
    }

    public boolean canConnect() {
        return dataProvider.canConnect();
    }

    private static void validateUrl(String value) {
        if (value == null || value.length() > 2048 || value.chars().anyMatch(character -> character < 33)) {
            throw invalidUrl();
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")) ||
                    host == null || host.isBlank() || uri.getRawUserInfo() != null) {
                throw invalidUrl();
            }
            host = host.toLowerCase().replaceAll("\\.$", "");
            if (host.equals("localhost") || host.endsWith(".local") || host.endsWith(".localhost") || !host.contains(".")) {
                throw invalidUrl();
            }
            if (IPV4_LITERAL.matcher(host).matches() || host.contains(":")) {
                if (!isPublicAddress(host)) {
                    throw invalidUrl();
                }
            }
        } catch (URISyntaxException exception) {
            throw invalidUrl();
        }
    }

    private static boolean isPublicAddress(String host) {
        try {
            if (host.contains("%")) {
                return false;
            }
            InetAddress address = InetAddress.getByName(host);
            byte[] bytes = address.getAddress();
            if (bytes.length == 4) {
                int first = Byte.toUnsignedInt(bytes[0]);
                int second = Byte.toUnsignedInt(bytes[1]);
                int third = Byte.toUnsignedInt(bytes[2]);
                return first != 0 && first != 10 && first != 127 && first < 224 &&
                        !(first == 100 && second >= 64 && second <= 127) &&
                        !(first == 169 && second == 254) &&
                        !(first == 172 && second >= 16 && second <= 31) &&
                        !(first == 192 && (second == 0 || second == 168)) &&
                        !(first == 198 && (second == 18 || second == 19 || second == 51)) &&
                        !(first == 203 && second == 0 && third == 113);
            }
            if (bytes.length != 16 || (Byte.toUnsignedInt(bytes[0]) & 0xE0) != 0x20) {
                return false;
            }
            return !(Byte.toUnsignedInt(bytes[0]) == 0x20 && Byte.toUnsignedInt(bytes[1]) == 0x01 &&
                    Byte.toUnsignedInt(bytes[2]) == 0x0D && Byte.toUnsignedInt(bytes[3]) == 0xB8);
        } catch (UnknownHostException exception) {
            return false;
        }
    }

    private static String generateCode() {
        byte[] bytes = new byte[6];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static ApiException invalidUrl() {
        return new ApiException(400, "Use a public HTTP(S) URL without credentials");
    }
}