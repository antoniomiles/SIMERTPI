import { TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { of, Subject, throwError } from 'rxjs';
import { PortalError } from '../../core/http/portal-error';
import { OperationToast } from '../../shared/ui/operation-toast';
import { Zones } from './zones';
import { CatalogPage, ZonesApi, ZoneCatalog } from './zones-api';
import { TariffsApi } from '../tariffs/tariffs-api';

const zone = { id:'z1',code:'Z-01',name:'Centro',description:null,active:true,createdAt:'2026-10-18T00:00:00Z',updatedAt:'2026-10-18T00:00:00Z',streetCount:1,spaceCount:1,activeSpaceCount:1 };
const street = { id:'s1',zoneId:'z1',zoneCode:'Z-01',zoneName:'Centro',code:'S-01',name:'Calle Norte',description:null,active:true,createdAt:zone.createdAt,updatedAt:zone.updatedAt,spaceCount:1 };
const space = { id:'p1',zoneId:'z1',zoneCode:'Z-01',zoneName:'Centro',streetId:'s1',streetCode:'S-01',streetName:'Calle Norte',code:'P-01',qrCode:'SIMERTPI-QR-01',spaceNumber:'01',latitude:null,longitude:null,spaceType:'STANDARD',active:true,operationalStatus:'AVAILABLE' as const,mapStatus:'NO_ACTIVE_SESSION' as const,createdAt:zone.createdAt,updatedAt:zone.updatedAt };
const page = <T,>(content:T[]) => ({ page:0,size:10,totalElements:content.length,totalPages:content.length?1:0,content });

describe('Administrative zones, streets and spaces screens', () => {
  let api: Record<string, ReturnType<typeof vi.fn>>;
  let tariffsApi: { effective: ReturnType<typeof vi.fn> };
  let view='zones';
  beforeEach(async () => {
    view='zones';
    tariffsApi={effective:vi.fn().mockReturnValue(of({zones:[{zoneId:'z1',tariff:{id:'t1',code:'GEN-01',name:'General',currency:'USD',amount:'0.25',durationMinutes:60},tariffSource:'GENERAL'}]}))};
    api={
      listZones:vi.fn().mockReturnValue(of([zone])), listStreets:vi.fn().mockReturnValue(of([street])),
      catalogZones:vi.fn().mockReturnValue(of(page([zone]))), catalogStreets:vi.fn().mockReturnValue(of(page([street]))), catalogSpaces:vi.fn().mockReturnValue(of(page([space]))),
      mapCoverage:vi.fn().mockReturnValue(of({totalSpaces:1,georeferencedSpaces:0,pendingGeoreference:1,evaluatedAt:'2026-10-24T12:00:00Z',refreshIntervalSeconds:60,markers:[]})),nearby:vi.fn().mockReturnValue(of([])),
      createZone:vi.fn().mockReturnValue(of(zone)), updateZone:vi.fn().mockReturnValue(of(zone)),
      createStreet:vi.fn().mockReturnValue(of(street)), updateStreet:vi.fn().mockReturnValue(of(street)),
      createSpace:vi.fn().mockReturnValue(of(space)), updateSpace:vi.fn().mockReturnValue(of(space)),
    };
    await TestBed.configureTestingModule({imports:[Zones],providers:[
      {provide:ZonesApi,useValue:api},
      {provide:TariffsApi,useValue:tariffsApi},
      {provide:ActivatedRoute,useValue:{snapshot:{data:{catalogView:view},pathFromRoot:[{data:{}}]}}},
    ]}).compileComponents();
    if (!HTMLDialogElement.prototype.showModal) HTMLDialogElement.prototype.showModal=function(){this.setAttribute('open','');};
    if (!HTMLDialogElement.prototype.close) HTMLDialogElement.prototype.close=function(){this.removeAttribute('open');};
  });

  it('shows Zonas as an independent server-paginated page with real relation counts',()=>{
    const f=TestBed.createComponent(Zones);f.detectChanges();
    expect(api['catalogZones']).toHaveBeenCalledWith(expect.objectContaining({page:0,size:10}));
    expect(f.nativeElement.textContent).toContain('Zonas');
    expect(f.nativeElement.textContent).toContain('1');
    expect(f.nativeElement.textContent).toContain('habilitados administrativamente');
    expect(f.nativeElement.textContent).toContain('GEN-01 · 0,25 USD / 60 min');
    expect(f.nativeElement.textContent).toContain('Tarifa general');
    expect(tariffsApi.effective).toHaveBeenCalledWith(expect.stringMatching(/^\d{4}-\d{2}-\d{2}T/));
  });

  it('shows effective zone exceptions from the existing resolver and never labels a failed query as unconfigured',()=>{
    tariffsApi.effective.mockReturnValueOnce(of({zones:[{zoneId:'z1',tariff:{id:'t2',code:'ZON-01',name:'Excepción',currency:'USD',amount:0.3,durationMinutes:30},tariffSource:'ZONE'}]}));
    const f=TestBed.createComponent(Zones);f.detectChanges();
    expect(f.nativeElement.textContent).toContain('ZON-01 · 0,30 USD / 30 min');
    expect(f.nativeElement.textContent).toContain('Excepción de zona');
    tariffsApi.effective.mockReturnValueOnce(throwError(()=>new Error('upstream')));
    f.componentInstance.load();f.detectChanges();
    expect(f.nativeElement.textContent).toContain('No disponible');
  });

  it('uses a separate Calles y tramos route and states that segments are not modeled',()=>{
    view='streets';
    TestBed.overrideProvider(ActivatedRoute,{useValue:{snapshot:{data:{catalogView:'streets'},pathFromRoot:[{data:{}}]}}});
    const f=TestBed.createComponent(Zones);f.detectChanges();
    expect(api['catalogStreets']).toHaveBeenCalled();
    expect(f.nativeElement.textContent).toContain('Calles y tramos');
    expect(f.nativeElement.textContent).toContain('no registra tramos por separado');
  });

  it('shows registered operational status separately from administrative enablement and geodata',()=>{
    view='spaces';
    TestBed.overrideProvider(ActivatedRoute,{useValue:{snapshot:{data:{catalogView:'spaces'},pathFromRoot:[{data:{}}]}}});
    const f=TestBed.createComponent(Zones);f.detectChanges();
    expect(api['catalogSpaces']).toHaveBeenCalled();
    expect(f.nativeElement.textContent).toContain('Estado operativo registrado');
    expect(f.nativeElement.textContent).toContain('Sin sesión activa');
    expect(f.nativeElement.textContent).toContain('No se muestran posiciones aproximadas');
  });

  it('loads and displays the immutable zone and street relations when editing a space',()=>{
    view='spaces';
    TestBed.overrideProvider(ActivatedRoute,{useValue:{snapshot:{data:{catalogView:'spaces'},pathFromRoot:[{data:{}}],queryParamMap:{get:()=>null}}}});
    const f=TestBed.createComponent(Zones);f.detectChanges();const c=f.componentInstance;
    c.openEdit(space);f.detectChanges();
    expect(api['listStreets']).toHaveBeenCalledWith('z1');
    const selects=Array.from(f.nativeElement.querySelectorAll('dialog select')) as HTMLSelectElement[];
    expect(selects.some(select=>select.disabled && select.value==='s1')).toBe(true);
  });

  it('submits a real edit once with the server version, then reports confirmed success and refreshes',()=>{
    const f=TestBed.createComponent(Zones);f.detectChanges();const pageComponent=f.componentInstance;
    pageComponent.openEdit(zone);pageComponent.zoneForm.controls.name.setValue('Centro actualizado');pageComponent.submit();
    expect(api['updateZone']).toHaveBeenCalledTimes(1);
    expect(api['updateZone']).toHaveBeenCalledWith('z1',expect.objectContaining({name:'Centro actualizado',expectedUpdatedAt:zone.updatedAt}));
    expect(TestBed.inject(OperationToast).current()?.kind).toBe('success');
    expect(api['catalogZones']).toHaveBeenCalledTimes(2);
  });

  it('intercepts the native form submit and sends a real create request without reloading the page',()=>{
    const f=TestBed.createComponent(Zones);f.detectChanges();const c=f.componentInstance;
    c.openCreate();c.zoneForm.setValue({code:'Z-02',name:'Zona Sur',description:'',active:true});
    const form=f.nativeElement.querySelector('dialog.catalog-dialog form') as HTMLFormElement;
    expect(form.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}))).toBe(false);
    expect(api['createZone']).toHaveBeenCalledWith({code:'Z-02',name:'Zona Sur',description:undefined});
    expect(TestBed.inject(OperationToast).current()?.kind).toBe('success');
  });

  it('queues the post-save refresh when a create finishes during the initial list load',()=>{
    const initial=new Subject<CatalogPage<ZoneCatalog>>();
    api['catalogZones'].mockReset().mockReturnValueOnce(initial).mockReturnValueOnce(of(page([zone])));
    const f=TestBed.createComponent(Zones);f.detectChanges();const c=f.componentInstance;
    expect(c.loading()).toBe(true);
    c.openCreate();c.zoneForm.setValue({code:'Z-02',name:'Zona Sur',description:'',active:true});c.submit();
    expect(api['createZone']).toHaveBeenCalledTimes(1);
    expect(api['catalogZones']).toHaveBeenCalledTimes(1);
    initial.next(page([]));initial.complete();
    expect(api['catalogZones']).toHaveBeenCalledTimes(2);
    expect(c.page()?.content).toEqual([zone]);
    expect(c.loading()).toBe(false);
  });

  it('keeps an editor open and retains values after a backend rejection',()=>{
    api['updateZone'].mockReturnValue(throwError(()=>new PortalError('conflict','Otro administrador modificÃ³ este registro. Actualice la informaciÃ³n.')));
    const f=TestBed.createComponent(Zones);f.detectChanges();const c=f.componentInstance;
    c.openEdit(zone);c.zoneForm.controls.name.setValue('Nombre pendiente');c.submit();
    expect(c.editor()).toBe('edit');expect(c.zoneForm.controls.name.value).toBe('Nombre pendiente');
    expect(c.error()).toContain('Otro administrador');expect(TestBed.inject(OperationToast).current()?.kind).toBe('error');
  });

  it('gives the create/edit and coordinate dialogs accessible names',()=>{
    view='spaces';TestBed.overrideProvider(ActivatedRoute,{useValue:{snapshot:{data:{catalogView:'spaces'},pathFromRoot:[{data:{}}]}}});
    const f=TestBed.createComponent(Zones);f.detectChanges();const c=f.componentInstance;
    c.openCreate();f.detectChanges();
    expect(f.nativeElement.querySelector('dialog.catalog-dialog')?.getAttribute('aria-label')).toBe('Formulario de creación o edición de registro');
    c.closeEditor();c.selectedZoneId.set('z1');c.selectedStreetId.set('s1');c.openMapSelector();f.detectChanges();
    expect(f.nativeElement.querySelector('dialog.map-dialog')?.getAttribute('aria-labelledby')).toBe('map-dialog-title');
    expect(f.nativeElement.querySelector('#map-dialog-title')?.textContent).toContain('Seleccionar coordenadas');
  });

  it('reports catalog relation and proximity lookup failures instead of treating them as empty results',()=>{
    view='spaces';TestBed.overrideProvider(ActivatedRoute,{useValue:{snapshot:{data:{catalogView:'spaces'},pathFromRoot:[{data:{}}],queryParamMap:{get:(key:string)=>key==='zoneId'?'z1':null}}}});
    api['listStreets'].mockReturnValue(throwError(()=>new Error('network')));
    const f=TestBed.createComponent(Zones);f.detectChanges();const c=f.componentInstance;
    expect(TestBed.inject(OperationToast).current()?.message).toContain('No se pudieron cargar las calles');
    api['nearby'].mockReturnValue(throwError(()=>new Error('network')));
    c.spaceForm.controls.latitude.setValue('-3.67');c.spaceForm.controls.longitude.setValue('-79.65');c.checkNearby();
    expect(TestBed.inject(OperationToast).current()?.message).toContain('No se pudo comprobar si existen espacios cercanos');
  });

  it('rejects partial coordinates before an API write',()=>{
    view='spaces';TestBed.overrideProvider(ActivatedRoute,{useValue:{snapshot:{data:{catalogView:'spaces'},pathFromRoot:[{data:{}}]}}});
    const f=TestBed.createComponent(Zones);f.detectChanges();const c=f.componentInstance;
    c.editor.set('create');c.selectedZoneId.set('z1');c.selectedStreetId.set('s1');
    c.spaceForm.setValue({code:'P2',spaceNumber:'02',latitude:'-3.6',longitude:'',spaceType:'STANDARD',active:true});c.submit();
    expect(api['createSpace']).not.toHaveBeenCalled();expect(c.error()).toContain('latitud y longitud juntas');
  });

  it('accepts Angular numeric coordinate values with up to seven decimals',()=>{
    view='spaces';TestBed.overrideProvider(ActivatedRoute,{useValue:{snapshot:{data:{catalogView:'spaces'},pathFromRoot:[{data:{}}]}}});
    const f=TestBed.createComponent(Zones);f.detectChanges();const c=f.componentInstance;
    c.openCreate();c.selectedZoneId.set('z1');c.selectedStreetId.set('s1');
    c.spaceForm.setValue({code:'P2',spaceNumber:'02',latitude:-3.6812345 as unknown as string,longitude:-79.6812345 as unknown as string,spaceType:'STANDARD',active:true});
    c.submit();
    expect(api['createSpace']).toHaveBeenCalledWith('s1',expect.objectContaining({latitude:-3.6812345,longitude:-79.6812345}));
    expect(c.error()).toBe('');
  });

  it('rejects numeric coordinate values with more than seven decimal places before writing',()=>{
    view='spaces';TestBed.overrideProvider(ActivatedRoute,{useValue:{snapshot:{data:{catalogView:'spaces'},pathFromRoot:[{data:{}}]}}});
    const f=TestBed.createComponent(Zones);f.detectChanges();const c=f.componentInstance;
    c.openCreate();c.selectedZoneId.set('z1');c.selectedStreetId.set('s1');
    c.spaceForm.setValue({code:'P2',spaceNumber:'02',latitude:-3.68123456 as unknown as string,longitude:-79.6812345 as unknown as string,spaceType:'STANDARD',active:true});
    c.submit();
    expect(api['createSpace']).not.toHaveBeenCalled();expect(c.error()).toContain('hasta 7 decimales');
  });

  it('warns about nearby persisted spaces without blocking the location choice',()=>{
    view='spaces';TestBed.overrideProvider(ActivatedRoute,{useValue:{snapshot:{data:{catalogView:'spaces'},pathFromRoot:[{data:{}}]}}});
    api['nearby'].mockReturnValue(of([{code:'P-NEAR',spaceNumber:'08',distanceMeters:2.7}]));
    const f=TestBed.createComponent(Zones);f.detectChanges();const c=f.componentInstance;
    c.openCreate();c.selectedZoneId.set('z1');c.selectedStreetId.set('s1');
    c.spaceForm.controls.latitude.setValue(-3.671 as unknown as string);c.spaceForm.controls.longitude.setValue(-79.651 as unknown as string);
    c.checkNearby();f.detectChanges();
    expect(api['nearby']).toHaveBeenCalledWith(-3.671,-79.651,undefined);
    expect(f.nativeElement.textContent).toContain('P-NEAR');expect(f.nativeElement.textContent).toContain('2.7 m');
  });

  it('renders a QR image from the persisted opaque identifier and keeps the editor free of manual QR input',async()=>{
    view='spaces';TestBed.overrideProvider(ActivatedRoute,{useValue:{snapshot:{data:{catalogView:'spaces'},pathFromRoot:[{data:{}}]}}});
    const f=TestBed.createComponent(Zones);f.detectChanges();const c=f.componentInstance;
    c.select(space);await vi.waitFor(()=>expect(c.qrDataUrl().startsWith('data:image/png;base64,')).toBe(true));
    c.openCreate();f.detectChanges();
    expect(f.nativeElement.textContent).toContain('El identificador QR se genera automáticamente');
    expect(f.nativeElement.querySelector('[formcontrolname="qrCode"]')).toBeNull();
  });

  it('prevents duplicate submissions while a backend write is pending',()=>{
    const pending=new Subject<typeof zone>();api['updateZone'].mockReturnValue(pending);
    const f=TestBed.createComponent(Zones);f.detectChanges();const c=f.componentInstance;
    c.openEdit(zone);c.submit();c.submit();expect(api['updateZone']).toHaveBeenCalledTimes(1);pending.next(zone);pending.complete();
  });
});
