package ec.gob.simertpi.api.parking;

import ec.gob.simertpi.application.parking.MapTileService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/parking/catalog/map/tiles")
public class AdminMapTileController {
    private final MapTileService tiles;
    public AdminMapTileController(MapTileService tiles) { this.tiles = tiles; }

    @GetMapping("/{z}/{x}/{y}")
    public ResponseEntity<byte[]> tile(@PathVariable int z, @PathVariable int x, @PathVariable int y) {
        MapTileService.Tile tile = tiles.get(z, x, y);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.maxAge(tile.maxAgeSeconds(), java.util.concurrent.TimeUnit.SECONDS).cachePrivate())
                .body(tile.body());
    }
}
