package ec.gob.simertpi.application.parking;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MapTileServiceTest {
    @Test void acceptsOnlyValidSlippyMapTileCoordinates() {
        assertThatCode(() -> MapTileService.validateTileCoordinate(0,0,0)).doesNotThrowAnyException();
        assertThatCode(() -> MapTileService.validateTileCoordinate(19,524287,524287)).doesNotThrowAnyException();
        assertThatThrownBy(() -> MapTileService.validateTileCoordinate(-1,0,0)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> MapTileService.validateTileCoordinate(20,0,0)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> MapTileService.validateTileCoordinate(3,8,1)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> MapTileService.validateTileCoordinate(3,1,8)).isInstanceOf(ResponseStatusException.class);
    }
}
