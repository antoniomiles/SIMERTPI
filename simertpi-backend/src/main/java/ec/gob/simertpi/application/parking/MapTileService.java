package ec.gob.simertpi.application.parking;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Fixed-host, identified, cached proxy for OpenStreetMap raster tiles. */
@Service
public class MapTileService {
    private static final int MAX_ZOOM = 19;
    private static final long DEFAULT_CACHE_SECONDS = 604_800;
    private static final long MAX_TILE_BYTES = 5_000_000;
    private static final Pattern MAX_AGE = Pattern.compile("(?:^|,)\\s*max-age=(\\d+)", Pattern.CASE_INSENSITIVE);
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final Map<String, Cached> cache = new LinkedHashMap<>(64, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) { return size() > 512; }
    };

    public record Tile(byte[] body, long maxAgeSeconds) { }
    private record Cached(byte[] body, long expiresAt, long maxAgeSeconds) { }

    public Tile get(int z, int x, int y) {
        validateTileCoordinate(z, x, y);
        String key = z + "/" + x + "/" + y;
        long now = System.currentTimeMillis();
        synchronized (cache) {
            Cached cached = cache.get(key);
            if (cached != null && cached.expiresAt() > now) return new Tile(cached.body(), cached.maxAgeSeconds());
            if (cached != null) cache.remove(key);
        }

        URI uri = URI.create("https://tile.openstreetmap.org/" + key + ".png");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", "SIMERTPI-admin-map/1.0 (+https://simertpi-admin-web-dev.onrender.com)")
                .header("Referer", "https://simertpi-admin-web-dev.onrender.com/")
                .header("Accept", "image/png")
                .GET().build();
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 404) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "La tesela cartográfica no existe.");
            if (response.statusCode() != 200 || response.body().length > MAX_TILE_BYTES
                    || !response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT).startsWith("image/png")) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "El proveedor cartográfico no entregó una imagen válida.");
            }
            long maxAge = maxAge(response.headers().firstValue("Cache-Control").orElse(null));
            Tile tile = new Tile(response.body(), maxAge);
            if (maxAge > 0) synchronized (cache) {
                cache.put(key, new Cached(tile.body(), now + maxAge * 1000, maxAge));
            }
            return tile;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "No se pudo consultar el proveedor cartográfico.");
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "No se pudo consultar el proveedor cartográfico.");
        }
    }

    static void validateTileCoordinate(int z, int x, int y) {
        if (z < 0 || z > MAX_ZOOM || x < 0 || y < 0 || x >= (1 << z) || y >= (1 << z)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La coordenada de tesela cartográfica no es válida.");
        }
    }

    private long maxAge(String cacheControl) {
        if (cacheControl == null) return DEFAULT_CACHE_SECONDS;
        String lower = cacheControl.toLowerCase(Locale.ROOT);
        if (lower.contains("no-store") || lower.contains("no-cache")) return 0;
        Matcher matcher = MAX_AGE.matcher(cacheControl);
        if (!matcher.find()) return DEFAULT_CACHE_SECONDS;
        try { return Math.min(DEFAULT_CACHE_SECONDS, Long.parseLong(matcher.group(1))); }
        catch (NumberFormatException exception) { return 0; }
    }
}
