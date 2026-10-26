import { Component, ElementRef, computed, inject, signal, viewChild } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize, Observable } from 'rxjs';
import { normalizeError } from '../../core/http/portal-error';
import { DataState } from '../../shared/ui/data-state';
import { PageHeading } from '../../shared/ui/page-heading';
import { OperationToast } from '../../shared/ui/operation-toast';
import { Icon } from '../../shared/ui/icon';
import { EffectiveZone, TariffsApi } from '../tariffs/tariffs-api';
import { CatalogPage, ParkingSpace, ParkingStreet, ParkingZone, SpaceCatalog, StreetCatalog, UpdateParkingSpace, UpdateStreet, UpdateZone, ZonesApi, ZoneCatalog } from './zones-api';

type View = 'zones' | 'streets' | 'spaces';
type RecordRow = ZoneCatalog | StreetCatalog | SpaceCatalog;

@Component({ selector: 'app-zones', imports: [ReactiveFormsModule, DataState, PageHeading, Icon], templateUrl: './zones.html', styleUrl: './zones.scss' })
export class Zones {
  private readonly api = inject(ZonesApi);
  private readonly builder = inject(FormBuilder);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly toast = inject(OperationToast);
  private readonly tariffsApi = inject(TariffsApi);
  private readonly dialogRef = viewChild<ElementRef<HTMLDialogElement>>('editorDialog');
  readonly preview = this.route.snapshot.pathFromRoot.some(item => item.data['preview'] === true);
  readonly view: View = (this.route.snapshot.data['catalogView'] as View | undefined) ?? 'zones';
  readonly title = { zones: 'Zonas', streets: 'Calles y tramos', spaces: 'Espacios' }[this.view];
  readonly description = {
    zones: 'Administra las zonas configuradas y consulta las calles y espacios relacionados.',
    streets: 'Consulta las calles existentes por zona. El modelo actual no registra tramos como entidad separada.',
    spaces: 'Administra espacios y consulta su estado administrativo y operativo registrado.',
  }[this.view];
  readonly state = signal<'loading' | 'error' | 'empty' | 'ready'>(this.preview ? 'empty' : 'loading');
  readonly zones = signal<ParkingZone[]>([]);
  readonly effectiveZones = signal<Map<string, EffectiveZone>>(new Map());
  readonly effectiveTariffsUnavailable = signal(false);
  readonly zoneStreets = signal<ParkingStreet[]>([]);
  readonly page = signal<CatalogPage<RecordRow> | null>(null);
  readonly selected = signal<RecordRow | null>(null);
  readonly error = signal('');
  readonly loading = signal(false);
  readonly saving = signal(false);
  private refreshQueued = false;
  readonly editor = signal<'create' | 'edit' | null>(null);
  readonly query = signal('');
  readonly activeFilter = signal('');
  readonly operationalFilter = signal('');
  readonly selectedZoneId = signal('');
  readonly selectedStreetId = signal('');
  readonly pageIndex = signal(0);
  readonly pageSize = 10;
  readonly visibleRows = computed(() => this.page()?.content ?? []);
  readonly georeferencedCount = computed(() => this.visibleRows().filter((row): row is SpaceCatalog => 'latitude' in row && row.latitude !== null && row.longitude !== null).length);
  readonly zoneForm = this.builder.nonNullable.group({ code: ['', [Validators.required, Validators.maxLength(50)]], name: ['', [Validators.required, Validators.maxLength(100)]], description: ['', [Validators.maxLength(255)]], active: [true] });
  readonly streetForm = this.builder.nonNullable.group({ code: ['', [Validators.required, Validators.maxLength(50)]], name: ['', [Validators.required, Validators.maxLength(150)]], description: ['', [Validators.maxLength(255)]], active: [true] });
  readonly spaceForm = this.builder.nonNullable.group({ code: ['', [Validators.required, Validators.maxLength(50)]], qrCode: ['', [Validators.required, Validators.maxLength(255)]], spaceNumber: ['', [Validators.required, Validators.maxLength(20)]], latitude: ['', [Validators.min(-90), Validators.max(90)]], longitude: ['', [Validators.min(-180), Validators.max(180)]], spaceType: ['', [Validators.maxLength(30)]], active: [true] });

  constructor() {
    const zoneId = this.route.snapshot.queryParamMap?.get('zoneId') ?? '';
    const streetId = this.route.snapshot.queryParamMap?.get('streetId') ?? '';
    if (this.view !== 'zones' && zoneId) this.selectedZoneId.set(zoneId);
    if (this.view === 'spaces' && streetId) this.selectedStreetId.set(streetId);
    if (!this.preview) {
      if (this.view === 'spaces' && zoneId) this.loadStreetChoices(zoneId);
      this.load();
    }
  }

  load(page = this.pageIndex()) {
    if (this.preview) return;
    if (this.loading()) { this.pageIndex.set(Math.max(0, page)); this.refreshQueued = true; return; }
    this.loading.set(true); this.error.set(''); this.state.set('loading'); this.pageIndex.set(Math.max(0, page));
    this.api.listZones().subscribe({ next: rows => this.zones.set(rows), error: () => this.zones.set([]) });
    if (this.view === 'zones') {
      this.effectiveTariffsUnavailable.set(false);
      this.tariffsApi.effective(new Date().toISOString()).subscribe({
        next: snapshot => this.effectiveZones.set(new Map(snapshot.zones.map(zone => [zone.zoneId, zone]))),
        error: () => { this.effectiveZones.set(new Map()); this.effectiveTariffsUnavailable.set(true); },
      });
    }
    const filters = { q: this.query(), active: this.activeFilter() === '' ? null : this.activeFilter() === 'true', zoneId: this.selectedZoneId() || undefined, streetId: this.selectedStreetId() || undefined, page: this.pageIndex(), size: this.pageSize };
    const request: Observable<CatalogPage<RecordRow>> = this.view === 'zones'
      ? this.api.catalogZones(filters) as Observable<CatalogPage<RecordRow>>
      : this.view === 'streets'
        ? this.api.catalogStreets(filters) as Observable<CatalogPage<RecordRow>>
        : this.api.catalogSpaces({ ...filters, operational: this.operationalFilter() || undefined }) as Observable<CatalogPage<RecordRow>>;
    request.pipe(finalize(() => {
      this.loading.set(false);
      if (this.refreshQueued) { const nextPage = this.pageIndex(); this.refreshQueued = false; this.load(nextPage); }
    })).subscribe({
      next: result => { this.page.set(result as CatalogPage<RecordRow>); this.state.set(result.content.length ? 'ready' : 'empty'); const old=this.selected()?.id; this.selected.set(result.content.find(row => row.id===old) ?? result.content[0] ?? null); },
      error: e => { this.error.set(normalizeError(e).message); this.state.set('error'); },
    });
  }

  applyFilters() { this.load(0); }
  clearFilters() { this.query.set(''); this.activeFilter.set(''); this.operationalFilter.set(''); this.selectedZoneId.set(''); this.selectedStreetId.set(''); this.zoneStreets.set([]); this.load(0); }
  select(row: RecordRow) { this.selected.set(row); }
  previous() { if (this.pageIndex()>0) this.load(this.pageIndex()-1); }
  next() { if ((this.page()?.totalPages ?? 0)>this.pageIndex()+1) this.load(this.pageIndex()+1); }
  openCreate() { this.editor.set('create'); this.resetForms(); if (this.view !== 'zones' && this.selectedZoneId()) this.loadStreetChoices(this.selectedZoneId()); this.dialogRef()?.nativeElement.showModal(); }
  openEdit(row: RecordRow) {
    this.selected.set(row); this.editor.set('edit');
    if (this.view==='zones') { const x=row as ZoneCatalog; this.zoneForm.setValue({code:x.code,name:x.name,description:x.description??'',active:x.active}); }
    else if (this.view==='streets') { const x=row as StreetCatalog; this.streetForm.setValue({code:x.code,name:x.name,description:x.description??'',active:x.active}); this.selectedZoneId.set(x.zoneId); }
    else { const x=row as SpaceCatalog; this.spaceForm.setValue({code:x.code,qrCode:x.qrCode,spaceNumber:x.spaceNumber,latitude:x.latitude===null?'':String(x.latitude),longitude:x.longitude===null?'':String(x.longitude),spaceType:x.spaceType,active:x.active}); this.selectedZoneId.set(x.zoneId); this.selectedStreetId.set(x.streetId); this.loadStreetChoices(x.zoneId); }
    this.dialogRef()?.nativeElement.showModal();
  }
  closeEditor() { if (this.saving()) return; this.dialogRef()?.nativeElement.close(); this.editor.set(null); }
  onSubmit(event: Event) { event.preventDefault(); this.submit(); }
  submit() {
    if (this.preview || this.saving()) return;
    const form=this.view==='zones'?this.zoneForm:this.view==='streets'?this.streetForm:this.spaceForm;
    if (form.invalid) { form.markAllAsTouched(); this.error.set('Revise los campos obligatorios y los valores indicados.'); return; }
    if (this.view==='spaces') { const x=this.spaceForm.getRawValue(); if ((x.latitude==='') !== (x.longitude==='')) { this.error.set('Ingrese latitud y longitud juntas, o deje ambas vacías.'); return; } }
    if (this.editor()==='create' && this.view==='streets' && !this.selectedZoneId()) { this.error.set('Seleccione la zona a la que pertenece la calle.'); return; }
    if (this.editor()==='create' && this.view==='spaces' && (!this.selectedZoneId() || !this.selectedStreetId())) { this.error.set('Seleccione una zona y una calle válidas.'); return; }
    this.saving.set(true); this.error.set(''); const operation=this.editor();
    const row=this.selected();
    const request: Observable<RecordRow> = this.view==='zones'
      ? this.submitZone(row as ZoneCatalog|null) as Observable<RecordRow>
      : this.view==='streets'
        ? this.submitStreet(row as StreetCatalog|null) as Observable<RecordRow>
        : this.submitSpace(row as SpaceCatalog|null) as Observable<RecordRow>;
    request.pipe(finalize(()=>this.saving.set(false))).subscribe({
      next: saved => { const message = this.view==='zones' ? (operation==='create'?'Zona creada':'Zona actualizada') : this.view==='streets' ? (operation==='create'?'Calle creada':'Calle actualizada') : (operation==='create'?'Espacio creado':'Espacio actualizado'); this.dialogRef()?.nativeElement.close(); this.editor.set(null); this.toast.success(`${message} correctamente.`); this.selected.set(saved as RecordRow); this.load(0); },
      error: e => { this.error.set(normalizeError(e).message); this.toast.error(this.error()); },
    });
  }
  private submitZone(row: ZoneCatalog|null) { const v=this.zoneForm.getRawValue(); if(this.editor()==='create') return this.api.createZone({code:v.code.trim(),name:v.name.trim(),description:this.optional(v.description)}); const body:UpdateZone={code:v.code.trim(),name:v.name.trim(),description:this.optional(v.description),active:v.active,expectedUpdatedAt:row!.updatedAt}; return this.api.updateZone(row!.id,body); }
  private submitStreet(row: StreetCatalog|null) { const v=this.streetForm.getRawValue(); if(this.editor()==='create') return this.api.createStreet(this.selectedZoneId(),{code:v.code.trim(),name:v.name.trim(),description:this.optional(v.description)}); const body:UpdateStreet={code:v.code.trim(),name:v.name.trim(),description:this.optional(v.description),active:v.active,expectedUpdatedAt:row!.updatedAt}; return this.api.updateStreet(row!.id,body); }
  private submitSpace(row: SpaceCatalog|null) { const v=this.spaceForm.getRawValue(); const body={code:v.code.trim(),qrCode:v.qrCode.trim(),spaceNumber:v.spaceNumber.trim(),latitude:v.latitude===''?undefined:Number(v.latitude),longitude:v.longitude===''?undefined:Number(v.longitude),spaceType:this.optional(v.spaceType)}; if(this.editor()==='create') return this.api.createSpace(this.selectedStreetId(),body); const update:UpdateParkingSpace={...body,active:v.active,expectedUpdatedAt:row!.updatedAt}; return this.api.updateSpace(row!.id,update); }
  history(row: RecordRow) { const resourceType=this.view==='zones'?'PARKING_ZONE':this.view==='streets'?'PARKING_STREET':'PARKING_SPACE'; void this.router.navigate(['/admin/audit'],{queryParams:{resourceType,resourceId:row.id}}); }
  viewStreets(zoneId: string) { void this.router.navigate(['/admin/streets'], { queryParams: { zoneId } }); }
  viewSpaces(zoneId: string, streetId?: string) { void this.router.navigate(['/admin/spaces'], { queryParams: { zoneId, streetId: streetId || null } }); }
  onZoneFilter(value: string) { this.selectedZoneId.set(value); this.selectedStreetId.set(''); if(value) this.loadStreetChoices(value); else this.zoneStreets.set([]); this.load(0); }
  onStreetFilter(value: string) { this.selectedStreetId.set(value); this.load(0); }
  onEditorZoneChange(value: string) { this.selectedZoneId.set(value); this.selectedStreetId.set(''); this.zoneStreets.set([]); if (value) this.loadStreetChoices(value); }
  formatDate(value: string) { return new Intl.DateTimeFormat('es-EC', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'America/Guayaquil' }).format(new Date(value)); }
  private loadStreetChoices(zoneId: string) { this.api.listStreets(zoneId).subscribe({ next: rows => this.zoneStreets.set(rows), error: () => this.zoneStreets.set([]) }); }
  isZone(row: RecordRow): row is ZoneCatalog { return this.view==='zones'; }
  isStreet(row: RecordRow): row is StreetCatalog { return this.view==='streets'; }
  isSpace(row: RecordRow): row is SpaceCatalog { return this.view==='spaces'; }
  effectiveTariffLabel(row: ZoneCatalog): string {
    if (this.effectiveTariffsUnavailable()) return 'No disponible';
    const tariff = this.effectiveZones().get(row.id)?.tariff;
    return tariff ? `${tariff.code} · ${this.formatTariffAmount(tariff.amount)} ${tariff.currency} / ${tariff.durationMinutes} min` : 'Sin tarifa efectiva';
  }
  effectiveTariffSource(row: ZoneCatalog): string {
    if (this.effectiveTariffsUnavailable()) return 'No se pudo consultar';
    const source = this.effectiveZones().get(row.id)?.tariffSource;
    return source === 'ZONE' ? 'Excepción de zona' : source === 'GENERAL' ? 'Tarifa general' : 'Sin configuración vigente';
  }
  private formatTariffAmount(amount: string | number): string {
    const [whole, fraction = ''] = String(amount).split('.');
    return `${whole},${fraction.padEnd(2, '0')}`;
  }
  private optional(v:string){const x=v.trim();return x||undefined;}
  private resetForms(){this.zoneForm.reset({code:'',name:'',description:'',active:true});this.streetForm.reset({code:'',name:'',description:'',active:true});this.spaceForm.reset({code:'',qrCode:'',spaceNumber:'',latitude:'',longitude:'',spaceType:'',active:true});this.error.set('');}
}
