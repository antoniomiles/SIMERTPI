import { Component, ElementRef, computed, inject, signal, viewChild } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { AbstractControl, FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { DecimalPipe } from '@angular/common';
import { finalize, Observable } from 'rxjs';
import { normalizeError } from '../../core/http/portal-error';
import { DataState } from '../../shared/ui/data-state';
import { PageHeading } from '../../shared/ui/page-heading';
import { OperationToast } from '../../shared/ui/operation-toast';
import { Icon } from '../../shared/ui/icon';
import { EffectiveZone, TariffsApi } from '../tariffs/tariffs-api';
import { CatalogPage, ParkingSpace, ParkingStreet, ParkingZone, SpaceCatalog, StreetCatalog, UpdateParkingSpace, UpdateStreet, UpdateZone, ZonesApi, ZoneCatalog } from './zones-api';
import { ParkingMap } from './parking-map';
import QRCode from 'qrcode';

type View = 'zones' | 'streets' | 'spaces';
type RecordRow = ZoneCatalog | StreetCatalog | SpaceCatalog;

@Component({ selector: 'app-zones', imports: [ReactiveFormsModule, DataState, PageHeading, Icon, ParkingMap, DecimalPipe], templateUrl: './zones.html', styleUrl: './zones.scss' })
export class Zones {
  private readonly api = inject(ZonesApi);
  private readonly builder = inject(FormBuilder);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly toast = inject(OperationToast);
  private readonly tariffsApi = inject(TariffsApi);
  private readonly dialogRef = viewChild<ElementRef<HTMLDialogElement>>('editorDialog');
  private readonly mapDialogRef = viewChild<ElementRef<HTMLDialogElement>>('mapDialog');
  private readonly qrDialogRef = viewChild<ElementRef<HTMLDialogElement>>('qrDialog');
  readonly preview = this.route.snapshot.pathFromRoot.some(item => item.data['preview'] === true);
  readonly view: View = (this.route.snapshot.data['catalogView'] as View | undefined) ?? 'zones';
  readonly title = { zones: 'Zonas', streets: 'Calles y tramos', spaces: 'Espacios' }[this.view];
  readonly pageHeadingTitle = { zones: 'Zonas de estacionamiento', streets: 'Calles y tramos', spaces: 'Espacios de estacionamiento' }[this.view];
  readonly navigationLabel = { zones: 'Zonas', streets: 'Calles y tramos', spaces: 'Espacios' }[this.view];
  readonly description = {
    zones: 'Administra las zonas de estacionamiento, sus calles y espacios.',
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
  readonly qrDataUrl = signal('');
  readonly qrDialogOpen = signal(false);
  readonly mapCoverage = signal<import('./zones-api').MapCoverage|null>(null);
  readonly coordinateCandidate = signal<{latitude:number;longitude:number}|null>(null);
  readonly nearbySpaces = signal<import('./zones-api').NearbySpace[]>([]);
  readonly mapSelectorOpen = signal(false);
  readonly query = signal('');
  readonly activeFilter = signal('');
  readonly operationalFilter = signal('');
  readonly selectedZoneId = signal('');
  readonly selectedStreetId = signal('');
  readonly pageIndex = signal(0);
  private readonly preferredRecordId: string;
  readonly pageSize = 10;
  mapStatusLabel(status: SpaceCatalog['mapStatus']): string {
    return ({ ACTIVE:'Sesión vigente', ENDING_SOON:'Próxima a vencer', EXPIRED:'Tiempo vencido; sigue ocupado hasta el cierre', MAX_TIME_REACHED:'Tiempo máximo alcanzado; sigue ocupado',
      NO_ACTIVE_SESSION:'Sin sesión activa registrada', DISABLED:'Deshabilitado administrativamente', PAYMENT_PENDING:'Pago pendiente',
      UNKNOWN:'Estado operativo no disponible' } as const)[status];
  }
  readonly visibleRows = computed(() => this.page()?.content ?? []);
  readonly georeferencedCount = computed(() => this.visibleRows().filter((row): row is SpaceCatalog => 'latitude' in row && row.latitude !== null && row.longitude !== null).length);
  readonly zoneForm = this.builder.nonNullable.group({ code: ['', [Validators.required, Validators.maxLength(50)]], name: ['', [Validators.required, Validators.maxLength(100)]], description: ['', [Validators.maxLength(255)]], active: [true] });
  readonly streetForm = this.builder.nonNullable.group({ code: ['', [Validators.required, Validators.maxLength(50)]], name: ['', [Validators.required, Validators.maxLength(150)]], description: ['', [Validators.maxLength(255)]], active: [true] });
  readonly spaceForm = this.builder.nonNullable.group({ code: ['', [Validators.required, Validators.maxLength(50)]], spaceNumber: ['', [Validators.required, Validators.maxLength(20)]], latitude: ['', [Validators.min(-90), Validators.max(90)]], longitude: ['', [Validators.min(-180), Validators.max(180)]], spaceType: ['', [Validators.maxLength(30)]], active: [true] });

  constructor() {
    const zoneId = this.route.snapshot.queryParamMap?.get('zoneId') ?? '';
    const streetId = this.route.snapshot.queryParamMap?.get('streetId') ?? '';
    this.preferredRecordId = this.route.snapshot.queryParamMap?.get('spaceId') ?? '';
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
    this.api.listZones().subscribe({ next: rows => this.zones.set(rows), error: () => { this.zones.set([]); this.toast.error('No se pudieron cargar las zonas para los filtros y formularios.'); } });
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
      next: result => { this.page.set(result as CatalogPage<RecordRow>); this.state.set(result.content.length ? 'ready' : 'empty'); const old=this.selected()?.id; const choice=result.content.find(row => row.id===this.preferredRecordId) ?? result.content.find(row => row.id===old) ?? result.content[0] ?? null; this.selected.set(choice); if(choice&&this.isSpace(choice))void this.makeQr(choice.qrCode); },
      error: e => { this.error.set(normalizeError(e).message); this.state.set('error'); },
    });
  }

  applyFilters() { this.load(0); }
  navigateToCatalog(view: View) { void this.router.navigate([this.preview ? '/preview' : '/admin', view]); }
  clearFilters() { this.query.set(''); this.activeFilter.set(''); this.operationalFilter.set(''); this.selectedZoneId.set(''); this.selectedStreetId.set(''); this.zoneStreets.set([]); this.load(0); }
  select(row: RecordRow) { this.selected.set(row); if(this.isSpace(row)) void this.makeQr(row.qrCode); }
  previous() { if (this.pageIndex()>0) this.load(this.pageIndex()-1); }
  next() { if ((this.page()?.totalPages ?? 0)>this.pageIndex()+1) this.load(this.pageIndex()+1); }
  openCreate() { this.editor.set('create'); this.resetForms(); if (this.view !== 'zones' && this.selectedZoneId()) this.loadStreetChoices(this.selectedZoneId()); this.dialogRef()?.nativeElement.showModal(); }
  openEdit(row: RecordRow) {
    this.error.set(''); this.selected.set(row); this.editor.set('edit');
    if (this.view==='zones') { const x=row as ZoneCatalog; this.zoneForm.setValue({code:x.code,name:x.name,description:x.description??'',active:x.active}); }
    else if (this.view==='streets') { const x=row as StreetCatalog; this.streetForm.setValue({code:x.code,name:x.name,description:x.description??'',active:x.active}); this.selectedZoneId.set(x.zoneId); }
    else { const x=row as SpaceCatalog; this.spaceForm.setValue({code:x.code,spaceNumber:x.spaceNumber,latitude:x.latitude===null?'':String(x.latitude),longitude:x.longitude===null?'':String(x.longitude),spaceType:x.spaceType,active:x.active}); this.selectedZoneId.set(x.zoneId); this.selectedStreetId.set(x.streetId); this.loadStreetChoices(x.zoneId); void this.makeQr(x.qrCode); }
    this.dialogRef()?.nativeElement.showModal();
  }
  closeEditor() { if (this.saving()) return; this.dialogRef()?.nativeElement.close(); this.editor.set(null); }
  onSubmit(event: Event) { event.preventDefault(); this.submit(); }
  submit() {
    if (this.preview || this.saving()) return;
    const form=this.view==='zones'?this.zoneForm:this.view==='streets'?this.streetForm:this.spaceForm;
    if (form.invalid) { form.markAllAsTouched(); this.error.set(this.validationMessages(form).join(' ')); return; }
    if (this.view==='spaces') {
      const x=this.spaceForm.getRawValue();
      if ((x.latitude==='') !== (x.longitude==='')) { this.error.set('Ingrese latitud y longitud juntas, o deje ambas vacías.'); return; }
      const invalidPrecision = [x.latitude,x.longitude].some(value => {
        const text = String(value);
        return text !== '' && (!Number.isFinite(Number(text)) || text.split('.')[1]?.length > 7);
      });
      if (invalidPrecision) { this.error.set('Revise las coordenadas: deben ser válidas y admitir hasta 7 decimales.'); return; }
    }
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
  private submitSpace(row: SpaceCatalog|null) { const v=this.spaceForm.getRawValue(); const body={code:v.code.trim(),spaceNumber:v.spaceNumber.trim(),latitude:v.latitude===''?undefined:Number(v.latitude),longitude:v.longitude===''?undefined:Number(v.longitude),spaceType:this.optional(v.spaceType)}; if(this.editor()==='create') return this.api.createSpace(this.selectedStreetId(),body); const update:UpdateParkingSpace={...body,active:v.active,expectedUpdatedAt:row!.updatedAt}; return this.api.updateSpace(row!.id,update); }
  history(row: RecordRow) { const resourceType=this.view==='zones'?'PARKING_ZONE':this.view==='streets'?'PARKING_STREET':'PARKING_SPACE'; void this.router.navigate(['/admin/audit'],{queryParams:{resourceType,resourceId:row.id}}); }
  viewStreets(zoneId: string) { void this.router.navigate(['/admin/streets'], { queryParams: { zoneId } }); }
  viewSpaces(zoneId: string, streetId?: string) { void this.router.navigate(['/admin/spaces'], { queryParams: { zoneId, streetId: streetId || null } }); }
  onZoneFilter(value: string) { this.selectedZoneId.set(value); this.selectedStreetId.set(''); if(value) this.loadStreetChoices(value); else this.zoneStreets.set([]); this.load(0); }
  onStreetFilter(value: string) { this.selectedStreetId.set(value); this.load(0); }
  onEditorZoneChange(value: string) { this.selectedZoneId.set(value); this.selectedStreetId.set(''); this.zoneStreets.set([]); if (value) this.loadStreetChoices(value); }
  formatDate(value: string) { return new Intl.DateTimeFormat('es-EC', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'America/Guayaquil' }).format(new Date(value)); }
  private loadStreetChoices(zoneId: string) { this.api.listStreets(zoneId).subscribe({ next: rows => this.zoneStreets.set(rows), error: () => { this.zoneStreets.set([]); this.toast.error('No se pudieron cargar las calles de la zona seleccionada.'); } }); }
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
  openMapSelector(){if(!this.selectedZoneId()||!this.selectedStreetId())return;this.coordinateCandidate.set(null);this.nearbySpaces.set([]);this.api.mapCoverage(this.selectedZoneId(),this.selectedStreetId()).subscribe({next:x=>this.mapCoverage.set(x),error:()=>{this.mapCoverage.set(null);this.toast.error('No se pudieron cargar los espacios georreferenciados existentes.');}});const v=this.spaceForm.getRawValue();if(v.latitude&&v.longitude)this.coordinateCandidate.set({latitude:Number(v.latitude),longitude:Number(v.longitude)});this.mapSelectorOpen.set(true);this.mapDialogRef()?.nativeElement.showModal();}
  closeMapSelector(){this.mapDialogRef()?.nativeElement.close();this.mapSelectorOpen.set(false);}
  choosePoint(point:{latitude:number;longitude:number}){this.coordinateCandidate.set(point);this.nearbySpaces.set([]);this.api.nearby(point.latitude,point.longitude,this.editor()==='edit'?this.selected()?.id:undefined).subscribe({next:x=>this.nearbySpaces.set(x),error:()=>{this.nearbySpaces.set([]);this.toast.error('No se pudo comprobar si existen espacios cercanos.');}});}
  confirmPoint(){const point=this.coordinateCandidate();if(!point)return;this.spaceForm.patchValue({latitude:point.latitude.toFixed(7),longitude:point.longitude.toFixed(7)});this.closeMapSelector();this.checkNearby();}
  async makeQr(value:string){try{this.qrDataUrl.set(await QRCode.toDataURL(value,{errorCorrectionLevel:'M',margin:4,width:320}));}catch{this.qrDataUrl.set('');}}
  openQr(){if(!this.qrDataUrl())return;this.qrDialogOpen.set(true);this.qrDialogRef()?.nativeElement.showModal();}
  closeQr(){this.qrDialogRef()?.nativeElement.close();this.qrDialogOpen.set(false);}
  downloadQr(){const row=this.selected();if(!row||!this.isSpace(row))return;const link=document.createElement('a');link.href=this.qrDataUrl();link.download=`QR-${row.code}.png`;link.click();}
  printQr(){const row=this.selected();if(!row||!this.isSpace(row)||!this.qrDataUrl())return;const win=window.open('','_blank','width=500,height=700');if(!win)return;win.opener=null;const doc=win.document;doc.title=`Etiqueta SIMERTPI ${row.code}`;const style=doc.createElement('style');style.textContent='@page{size:auto;margin:12mm}*{box-sizing:border-box}body{margin:0;padding:28px;background:#f3f6f8;color:#17332b;font:14px/1.5 Arial,sans-serif}.label{width:min(100%,390px);margin:0 auto;padding:26px;border:1px solid #cbd8d2;border-radius:14px;background:#fff;text-align:center}.brand{margin:0;color:#087747;font-size:22px;font-weight:700;letter-spacing:.08em}.institution{margin:2px 0 24px;color:#53645d;font-size:12px}.eyebrow{margin:0 0 6px;color:#65776f;font-size:10px;font-weight:700;letter-spacing:.12em}.title{margin:0;font-size:18px}.qr{display:block;width:240px;height:240px;margin:22px auto 16px;image-rendering:pixelated}.code{margin:0;font-size:18px;font-weight:700}.number{margin:3px 0 14px;color:#405249}.place{margin:0;padding-top:12px;border-top:1px solid #dfe7e3;color:#34483f;font-size:12px}@media print{body{padding:0;background:#fff}.label{border:1px solid #9caea5;box-shadow:none;break-inside:avoid}}';doc.head.append(style);const section=doc.createElement('main');section.className='label';const brand=doc.createElement('p');brand.className='brand';brand.textContent='SIMERTPI';const institution=doc.createElement('p');institution.className='institution';institution.textContent='GAD Municipal de Piñas';const eyebrow=doc.createElement('p');eyebrow.className='eyebrow';eyebrow.textContent='IDENTIFICACIÓN DE ESPACIO DE ESTACIONAMIENTO';const title=doc.createElement('h1');title.className='title';title.textContent='Código QR del espacio';const img=doc.createElement('img');img.className='qr';img.src=this.qrDataUrl();img.alt=`QR ${row.code}`;const code=doc.createElement('p');code.className='code';code.textContent=row.code;const number=doc.createElement('p');number.className='number';number.textContent=`Espacio ${row.spaceNumber}`;const place=doc.createElement('p');place.className='place';place.textContent=`Zona ${row.zoneCode} · Calle ${row.streetCode} ${row.streetName}`;section.append(brand,institution,eyebrow,title,img,code,number,place);doc.body.replaceChildren(section);win.focus();win.print();}
  checkNearby(){const v=this.spaceForm.getRawValue();if(v.latitude===''||v.longitude===''||!Number.isFinite(Number(v.latitude))||!Number.isFinite(Number(v.longitude)))return;this.nearbySpaces.set([]);this.api.nearby(Number(v.latitude),Number(v.longitude),this.editor()==='edit'?this.selected()?.id:undefined).subscribe({next:x=>this.nearbySpaces.set(x),error:()=>{this.nearbySpaces.set([]);this.toast.error('No se pudo comprobar si existen espacios cercanos.');}});}
  private validationMessages(form:FormGroup):string[]{
    const messages:string[]=[];for(const [name,control] of Object.entries(form.controls) as [string,AbstractControl][]){if(!control.invalid)continue;const label=({code:'Código',name:'Nombre',spaceNumber:'Número',latitude:'Latitud',longitude:'Longitud',description:'Descripción',spaceType:'Tipo'} as Record<string,string>)[name]??name;if(control.hasError('required'))messages.push(`${label}: es obligatorio.`);else if(control.hasError('min')||control.hasError('max'))messages.push(`${label}: el valor está fuera del rango permitido.`);else if(control.hasError('maxlength'))messages.push(`${label}: supera la longitud máxima.`);}
    return messages.length?messages:['Revise los datos indicados.'];
  }
  private resetForms(){this.zoneForm.reset({code:'',name:'',description:'',active:true});this.streetForm.reset({code:'',name:'',description:'',active:true});this.spaceForm.reset({code:'',spaceNumber:'',latitude:'',longitude:'',spaceType:'',active:true});this.error.set('');this.qrDataUrl.set('');}
}
