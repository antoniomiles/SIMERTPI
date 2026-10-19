import { computed, inject, signal } from '@angular/core';
import { Component } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { normalizeError } from '../../core/http/portal-error';
import { DataState } from '../../shared/ui/data-state';
import { Icon } from '../../shared/ui/icon';
import { PageHeading } from '../../shared/ui/page-heading';
import { ParkingSpace, ParkingStreet, ParkingZone, ZonesApi } from '../zones/zones-api';

type ViewState = 'loading' | 'error' | 'empty' | 'ready';
type AdministrativeFilter = 'all' | 'active' | 'inactive';

@Component({
  selector: 'app-map-coverage',
  imports: [DataState, Icon, PageHeading],
  templateUrl: './map-coverage.html',
  styleUrl: './map-coverage.scss',
})
export class MapCoverage {
  private readonly api = inject(ZonesApi);
  private readonly route = inject(ActivatedRoute);

  readonly preview = this.route.snapshot.pathFromRoot.some((item) => item.data['preview'] === true);
  readonly zones = signal<ParkingZone[]>([]);
  readonly streets = signal<ParkingStreet[]>([]);
  readonly spaces = signal<ParkingSpace[]>([]);
  readonly selectedZoneId = signal('');
  readonly selectedStreetId = signal('');
  readonly zonesState = signal<ViewState>(this.preview ? 'empty' : 'loading');
  readonly streetsState = signal<ViewState>('empty');
  readonly spacesState = signal<ViewState>('empty');
  readonly errorMessage = signal('');
  readonly query = signal('');
  readonly administrativeFilter = signal<AdministrativeFilter>('all');

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
        if (selected) this.loadStreets(selected.id);
        else {
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
    this.selectedStreetId.set('');
    this.spaces.set([]);
    this.spacesState.set('empty');
    const zone = this.zones().find((item) => item.id === zoneId);
    if (zone) this.loadStreets(zone.id);
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
    const street = this.streets().find((item) => item.id === streetId);
    if (street) this.loadSpaces(street.id);
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
}
