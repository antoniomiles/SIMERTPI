import { computed, inject, signal } from '@angular/core';
import { Component } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { normalizeError } from '../../core/http/portal-error';
import { DataState } from '../../shared/ui/data-state';
import { PageHeading } from '../../shared/ui/page-heading';
import { CreateParkingSpace, ParkingSpace, ParkingStreet, ParkingZone, ZonesApi } from './zones-api';

type ViewState = 'loading' | 'error' | 'empty' | 'ready';

@Component({
  selector: 'app-zones',
  imports: [ReactiveFormsModule, DataState, PageHeading],
  templateUrl: './zones.html',
  styleUrl: './zones.scss',
})
export class Zones {
  private readonly api = inject(ZonesApi);
  private readonly builder = inject(FormBuilder);
  private readonly route = inject(ActivatedRoute);

  readonly preview =
    this.route.snapshot.pathFromRoot.some((item) => item.data['preview'] === true);

  readonly zones = signal<ParkingZone[]>([]);
  readonly streets = signal<ParkingStreet[]>([]);
  readonly spaces = signal<ParkingSpace[]>([]);
  readonly selectedZone = signal<ParkingZone | null>(null);
  readonly selectedStreet = signal<ParkingStreet | null>(null);
  readonly zonesState = signal<ViewState>(this.preview ? 'empty' : 'loading');
  readonly streetsState = signal<ViewState>('empty');
  readonly spacesState = signal<ViewState>('empty');
  readonly zoneQuery = signal('');
  readonly streetQuery = signal('');
  readonly spaceQuery = signal('');
  readonly mutationError = signal('');
  readonly mutationMessage = signal('');
  readonly saving = signal<'zone' | 'street' | 'space' | null>(null);
  readonly showZoneForm = signal(false);
  readonly showStreetForm = signal(false);
  readonly showSpaceForm = signal(false);

  readonly visibleZones = computed<ParkingZone[]>(() => this.filterRows<ParkingZone>(this.zones(), this.zoneQuery()));
  readonly visibleStreets = computed<ParkingStreet[]>(() => this.filterRows<ParkingStreet>(this.streets(), this.streetQuery()));
  readonly visibleSpaces = computed<ParkingSpace[]>(() => this.filterRows<ParkingSpace>(this.spaces(), this.spaceQuery()));
  readonly enabledSpaces = computed(() => this.spaces().filter((space) => space.active).length);

  readonly zoneForm = this.builder.nonNullable.group({
    code: ['', [Validators.required, Validators.maxLength(50)]],
    name: ['', [Validators.required, Validators.maxLength(100)]],
    description: ['', [Validators.maxLength(255)]],
  });
  readonly streetForm = this.builder.nonNullable.group({
    code: ['', [Validators.required, Validators.maxLength(50)]],
    name: ['', [Validators.required, Validators.maxLength(150)]],
    description: ['', [Validators.maxLength(255)]],
  });
  readonly spaceForm = this.builder.nonNullable.group({
    code: ['', [Validators.required, Validators.maxLength(50)]],
    qrCode: ['', [Validators.required, Validators.maxLength(255)]],
    spaceNumber: ['', [Validators.required, Validators.maxLength(20)]],
    latitude: ['', [Validators.min(-90), Validators.max(90)]],
    longitude: ['', [Validators.min(-180), Validators.max(180)]],
    spaceType: ['', [Validators.maxLength(30)]],
  });

  constructor() {
    if (!this.preview) this.loadZones();
  }

  loadZones(preferredId?: string) {
    if (this.preview) return;
    this.zonesState.set('loading');
    this.mutationError.set('');
    this.api.listZones().subscribe({
      next: (rows) => {
        this.zones.set(rows);
        this.zonesState.set(rows.length ? 'ready' : 'empty');
        const selectedId = preferredId ?? this.selectedZone()?.id ?? rows[0]?.id;
        const selected = rows.find((zone) => zone.id === selectedId) ?? null;
        this.selectedZone.set(selected);
        if (selected) this.loadStreets(selected.id);
        else {
          this.selectedStreet.set(null);
          this.streets.set([]);
          this.spaces.set([]);
          this.streetsState.set('empty');
          this.spacesState.set('empty');
        }
      },
      error: (error: unknown) => {
        this.zonesState.set('error');
        this.mutationError.set(normalizeError(error).message);
      },
    });
  }

  selectZone(zone: ParkingZone) {
    this.selectedZone.set(zone);
    this.selectedStreet.set(null);
    this.spaces.set([]);
    this.spacesState.set('empty');
    this.mutationMessage.set('');
    this.loadStreets(zone.id);
  }

  loadStreets(zoneId: string, preferredId?: string) {
    if (this.preview) return;
    this.streetsState.set('loading');
    this.api.listStreets(zoneId).subscribe({
      next: (rows) => {
        this.streets.set(rows);
        this.streetsState.set(rows.length ? 'ready' : 'empty');
        const selectedId = preferredId ?? this.selectedStreet()?.id ?? rows[0]?.id;
        const selected = rows.find((street) => street.id === selectedId) ?? null;
        this.selectedStreet.set(selected);
        if (selected) this.loadSpaces(selected.id);
        else {
          this.spaces.set([]);
          this.spacesState.set('empty');
        }
      },
      error: (error: unknown) => {
        this.streetsState.set('error');
        this.mutationError.set(normalizeError(error).message);
      },
    });
  }

  selectStreet(street: ParkingStreet) {
    this.selectedStreet.set(street);
    this.mutationMessage.set('');
    this.loadSpaces(street.id);
  }

  loadSpaces(streetId: string) {
    if (this.preview) return;
    this.spacesState.set('loading');
    this.api.listSpaces(streetId).subscribe({
      next: (rows) => {
        this.spaces.set(rows);
        this.spacesState.set(rows.length ? 'ready' : 'empty');
      },
      error: (error: unknown) => {
        this.spacesState.set('error');
        this.mutationError.set(normalizeError(error).message);
      },
    });
  }

  createZone() {
    if (this.preview || this.saving() || this.zoneForm.invalid) {
      this.zoneForm.markAllAsTouched();
      return;
    }
    const value = this.zoneForm.getRawValue();
    this.beginSave('zone');
    this.api
      .createZone({ code: value.code.trim(), name: value.name.trim(), description: this.optional(value.description) })
      .pipe(finalize(() => this.saving.set(null)))
      .subscribe({
        next: (created) => {
          this.zoneForm.reset({ code: '', name: '', description: '' });
          this.showZoneForm.set(false);
          this.mutationMessage.set(`Zona ${created.code} registrada.`);
          this.loadZones(created.id);
        },
        error: (error: unknown) => this.reportSaveError(error),
      });
  }

  createStreet() {
    const zone = this.selectedZone();
    if (this.preview || this.saving() || !zone?.active || this.streetForm.invalid) {
      this.streetForm.markAllAsTouched();
      return;
    }
    const value = this.streetForm.getRawValue();
    this.beginSave('street');
    this.api
      .createStreet(zone.id, {
        code: value.code.trim(),
        name: value.name.trim(),
        description: this.optional(value.description),
      })
      .pipe(finalize(() => this.saving.set(null)))
      .subscribe({
        next: (created) => {
          this.streetForm.reset({ code: '', name: '', description: '' });
          this.showStreetForm.set(false);
          this.mutationMessage.set(`Calle ${created.code} registrada en ${zone.name}.`);
          this.loadStreets(zone.id, created.id);
        },
        error: (error: unknown) => this.reportSaveError(error),
      });
  }

  createSpace() {
    const street = this.selectedStreet();
    const zone = this.selectedZone();
    if (this.preview || this.saving() || !zone?.active || !street?.active || this.spaceForm.invalid) {
      this.spaceForm.markAllAsTouched();
      return;
    }
    const value = this.spaceForm.getRawValue();
    if ((value.latitude === '') !== (value.longitude === '')) {
      this.mutationError.set('Ingresa latitud y longitud juntas, o deja ambas vacías.');
      return;
    }
    const body: CreateParkingSpace = {
      code: value.code.trim(),
      qrCode: value.qrCode.trim(),
      spaceNumber: value.spaceNumber.trim(),
      latitude: value.latitude === '' ? undefined : Number(value.latitude),
      longitude: value.longitude === '' ? undefined : Number(value.longitude),
      spaceType: this.optional(value.spaceType),
    };
    this.beginSave('space');
    this.api
      .createSpace(street.id, body)
      .pipe(finalize(() => this.saving.set(null)))
      .subscribe({
        next: (created) => {
          this.spaceForm.reset({ code: '', qrCode: '', spaceNumber: '', latitude: '', longitude: '', spaceType: '' });
          this.showSpaceForm.set(false);
          this.mutationMessage.set(`Espacio ${created.code} registrado en ${street.name}.`);
          this.loadSpaces(street.id);
        },
        error: (error: unknown) => this.reportSaveError(error),
      });
  }

  private beginSave(kind: 'zone' | 'street' | 'space') {
    this.saving.set(kind);
    this.mutationError.set('');
    this.mutationMessage.set('');
  }

  private reportSaveError(error: unknown) {
    const normalized = normalizeError(error);
    this.mutationError.set(
      normalized.kind === 'conflict' || normalized.kind === 'validation'
        ? 'No se pudo guardar. Verifica los datos y que los códigos no estén repetidos.'
        : normalized.message,
    );
  }

  private optional(value: string): string | undefined {
    const trimmed = value.trim();
    return trimmed ? trimmed : undefined;
  }

  private filterRows<T extends { code: string; name?: string; spaceNumber?: string }>(rows: T[], query: string): T[] {
    const normalized = query.trim().toLocaleLowerCase();
    if (!normalized) return rows;
    return rows.filter((row) =>
      [row.code, row.name ?? '', row.spaceNumber ?? ''].some((value) => value.toLocaleLowerCase().includes(normalized)),
    );
  }
}
