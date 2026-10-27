import { computed, inject, signal, DestroyRef } from '@angular/core';
import { Component } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { EMPTY, Subject, catchError, concat, interval, map, of, switchMap, tap, timeout } from 'rxjs';
import { normalizeError } from '../../core/http/portal-error';
import { DataState } from '../../shared/ui/data-state';
import { Icon } from '../../shared/ui/icon';
import { PageHeading } from '../../shared/ui/page-heading';
import { MapCoverage as MapCoverageResult, MapMarker, ParkingMapStatus, ParkingSpace, ParkingStreet, ParkingZone, ZonesApi } from '../zones/zones-api';
import { ParkingMap } from '../zones/parking-map';

type ViewState = 'loading' | 'error' | 'empty' | 'ready';
type AdministrativeFilter = 'all' | 'active' | 'inactive';

@Component({
  selector: 'app-map-coverage',
  imports: [DataState, Icon, PageHeading, ParkingMap],
  templateUrl: './map-coverage.html',
  styleUrl: './map-coverage.scss',
})
export class MapCoverage {
  private readonly api = inject(ZonesApi);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly mapScope$ = new Subject<{ zoneId?: string; streetId?: string }>();

  readonly preview = this.route.snapshot.pathFromRoot.some((item) => item.data['preview'] === true);
  readonly zones = signal<ParkingZone[]>([]);
  readonly streets = signal<ParkingStreet[]>([]);
  readonly spaces = signal<ParkingSpace[]>([]);
  readonly selectedZoneId = signal('');
  readonly selectedStreetId = signal('');
  readonly selectedMapStreetId = signal('');
  readonly zonesState = signal<ViewState>(this.preview ? 'empty' : 'loading');
  readonly streetsState = signal<ViewState>('empty');
  readonly spacesState = signal<ViewState>('empty');
  readonly errorMessage = signal('');
  readonly query = signal('');
  readonly administrativeFilter = signal<AdministrativeFilter>('all');
  readonly mapData = signal<MapCoverageResult|null>(null);
  readonly mapLoading = signal(false);
  readonly mapRefreshing = signal(false);
  readonly mapError = signal('');
  readonly selectedMarker = signal<MapMarker|null>(null);
  readonly tilesState = signal<'loading'|'ready'|'partial'|'error'>('loading');
  readonly mapRefreshKey = signal(0);
  readonly mapFitKey = signal(0);
  readonly mapStatusCounts = computed(() => {
    const counts: Record<ParkingMapStatus, number> = {
      ACTIVE: 0, ENDING_SOON: 0, EXPIRED: 0, MAX_TIME_REACHED: 0,
      NO_ACTIVE_SESSION: 0, DISABLED: 0, PAYMENT_PENDING: 0, UNKNOWN: 0,
    };
    for (const marker of this.mapData()?.markers ?? []) counts[marker.mapStatus]++;
    return counts;
  });
  readonly mapUpdatedAt = computed(() => {
    const value = this.mapData()?.evaluatedAt;
    if (!value) return '';
    return new Intl.DateTimeFormat('es-EC', { dateStyle:'medium', timeStyle:'short', timeZone:'America/Guayaquil' }).format(new Date(value));
  });

  readonly selectedZone = computed(
    () => this.zones().find((zone) => zone.id === this.selectedZoneId()) ?? null,
  );
  readonly selectedStreet = computed(
    () => this.streets().find((street) => street.id === this.selectedStreetId()) ?? null,
  );
  readonly completePairs = computed(
    () =>
      this.spaces().filter((space) => space.latitude !== null && space.longitude !== null).length,
  );
  readonly missingPairs = computed(() => this.spaces().length - this.completePairs());
  readonly outOfRangePairs = computed(
    () =>
      this.spaces().filter(
        (space) =>
          space.latitude !== null &&
          space.longitude !== null &&
          (space.latitude < -90 ||
            space.latitude > 90 ||
            space.longitude < -180 ||
            space.longitude > 180),
      ).length,
  );
  readonly enabledSpaces = computed(() => this.spaces().filter((space) => space.active).length);
  readonly visibleSpaces = computed(() => {
    const query = this.query().trim().toLocaleLowerCase();
    return this.spaces().filter((space) => {
      const matchesState =
        this.administrativeFilter() === 'all' ||
        (this.administrativeFilter() === 'active' ? space.active : !space.active);
      const matchesQuery =
        !query ||
        `${space.code} ${space.spaceNumber} ${space.spaceType}`.toLocaleLowerCase().includes(query);
      return matchesState && matchesQuery;
    });
  });

  constructor() {
    this.mapScope$.pipe(
      switchMap((scope) => concat(of(false), interval(60_000).pipe(map(() => true))).pipe(
        switchMap((isRefresh) => {
          if (isRefresh) this.mapRefreshing.set(true);
          return this.api.mapCoverage(scope.zoneId, scope.streetId).pipe(
          timeout({ first:15_000 }),
          tap((data) => {
            this.mapData.set(data);
            this.mapLoading.set(false);
            this.mapRefreshing.set(false);
            this.mapError.set('');
            const selected = this.selectedMarker();
            this.selectedMarker.set(selected ? data?.markers.find((marker) => marker.id === selected.id) ?? null : null);
          }),
          catchError((error: unknown) => {
            // A failed refresh must not leave old occupancy presented as current.
            this.mapData.set(null);
            this.mapLoading.set(false);
            this.mapRefreshing.set(false);
            this.selectedMarker.set(null);
            this.mapError.set(normalizeError(error).message || 'No se pudo actualizar el estado del mapa.');
            return EMPTY;
          }),
        );}),
      )),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe();
    if (!this.preview) this.loadZones();
  }

  loadZones(preferredId = this.selectedZoneId()) {
    if (this.preview) return;
    this.errorMessage.set('');
    this.zonesState.set('loading');
    this.api.listZones().subscribe({
      next: (rows) => {
        this.zones.set(rows);
        this.zonesState.set(rows.length ? 'ready' : 'empty');
        const selected = rows.find((zone) => zone.id === preferredId) ?? rows[0] ?? null;
        this.selectedZoneId.set(selected?.id ?? '');
        if (selected) { this.loadMap(selected.id,this.selectedMapStreetId()||undefined); this.loadStreets(selected.id); }
        else {
          this.mapData.set(null);
          this.mapLoading.set(false);
          this.mapRefreshing.set(false);
          this.mapError.set('');
          this.selectedMarker.set(null);
          this.selectedMapStreetId.set('');
          this.streets.set([]);
          this.spaces.set([]);
          this.selectedStreetId.set('');
          this.streetsState.set('empty');
          this.spacesState.set('empty');
        }
      },
      error: (error: unknown) => {
        this.zonesState.set('error');
        this.errorMessage.set(
          normalizeError(error).message || 'No se pudieron consultar las zonas.',
        );
      },
    });
  }

  selectZone(zoneId: string) {
    this.selectedZoneId.set(zoneId);
    this.selectedMapStreetId.set('');
    this.selectedStreetId.set('');
    this.spaces.set([]);
    this.spacesState.set('empty');
    const zone = this.zones().find((item) => item.id === zoneId);
    if (zone) { this.loadMap(zone.id); this.loadStreets(zone.id); }
    else {
      this.streets.set([]);
      this.streetsState.set('empty');
    }
  }

  loadStreets(zoneId: string) {
    this.streetsState.set('loading');
    this.spaces.set([]);
    this.spacesState.set('empty');
    this.errorMessage.set('');
    this.api.listStreets(zoneId).subscribe({
      next: (rows) => {
        this.streets.set(rows);
        this.streetsState.set(rows.length ? 'ready' : 'empty');
        const first = rows[0];
        this.selectedStreetId.set(first?.id ?? '');
        if (first) this.loadSpaces(first.id);
      },
      error: (error: unknown) => {
        this.streetsState.set('error');
        this.errorMessage.set(
          normalizeError(error).message || 'No se pudieron consultar las calles.',
        );
      },
    });
  }

  selectStreet(streetId: string) {
    this.selectedStreetId.set(streetId);
    this.selectedMapStreetId.set(streetId);
    const street = this.streets().find((item) => item.id === streetId);
    if (street) { this.loadMap(this.selectedZoneId(),street.id); this.loadSpaces(street.id); }
    else {
      this.spaces.set([]);
      this.spacesState.set('empty');
    }
  }

  loadSpaces(streetId = this.selectedStreetId()) {
    if (!streetId) return;
    this.spacesState.set('loading');
    this.errorMessage.set('');
    this.api.listSpaces(streetId).subscribe({
      next: (rows) => {
        this.spaces.set(rows);
        this.spacesState.set(rows.length ? 'ready' : 'empty');
      },
      error: (error: unknown) => {
        this.spacesState.set('error');
        this.errorMessage.set(
          normalizeError(error).message || 'No se pudieron consultar los espacios.',
        );
      },
    });
  }

  spaceGeographyLabel(space: ParkingSpace): string {
    if (space.latitude === null && space.longitude === null) return 'Pendientes de captura';
    if (space.latitude === null || space.longitude === null) return 'Par incompleto';
    if (
      space.latitude < -90 ||
      space.latitude > 90 ||
      space.longitude < -180 ||
      space.longitude > 180
    ) {
      return 'Revisar rango';
    }
    return 'Registradas · por validar';
  }

  setAdministrativeFilter(value: string) {
    if (value === 'all' || value === 'active' || value === 'inactive') {
      this.administrativeFilter.set(value);
    }
  }

  setQuery(value: string) {
    this.query.set(value);
  }

  loadMap(zoneId=this.selectedZoneId(),streetId?:string){
    this.mapError.set('');
    this.mapLoading.set(true);
    this.mapData.set(null);
    this.selectedMarker.set(null);
    this.mapScope$.next({ zoneId: zoneId || undefined, streetId: streetId || undefined });
  }
  selectMapStreet(streetId: string) {
    this.selectedMapStreetId.set(streetId);
    if (!streetId) this.loadMap(this.selectedZoneId());
    else this.selectStreet(streetId);
  }
  selectMarker(marker:MapMarker){this.selectedMarker.set(marker);}
  mapTileStateChanged(state: 'loading'|'ready'|'partial'|'error') { this.tilesState.set(state); }
  retryMapData() { this.loadMap(this.selectedZoneId(),this.selectedMapStreetId()||undefined); }
  retryMapTiles() { this.mapRefreshKey.update((value) => value + 1); this.tilesState.set('loading'); }
  centerMapResults() { this.mapFitKey.update((value) => value + 1); }
  mapStatusLabel(status: MapMarker['mapStatus']) {
    return ({
      ACTIVE:'Sesión vigente', ENDING_SOON:'Próxima a vencer', EXPIRED:'Tiempo vencido; sigue ocupado hasta el cierre', MAX_TIME_REACHED:'Tiempo máximo alcanzado; sigue ocupado',
      NO_ACTIVE_SESSION:'Sin sesión activa registrada; no confirma disponibilidad física', DISABLED:'Deshabilitado administrativamente',
      PAYMENT_PENDING:'Pago pendiente; espacio reservado', UNKNOWN:'Estado operativo no disponible',
    } as const)[status];
  }
  googleMapsUrl(marker:MapMarker){return `https://www.google.com/maps?q=${marker.latitude},${marker.longitude}`;}
  viewMarker(marker:MapMarker){void this.router.navigate(['/admin/spaces'],{queryParams:{zoneId:marker.zoneId,streetId:marker.streetId,spaceId:marker.id}});}
}
