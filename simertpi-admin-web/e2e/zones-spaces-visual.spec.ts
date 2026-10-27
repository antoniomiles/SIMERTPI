import { expect, test } from '@playwright/test';

// Visual-only browser fixtures. Persistence and authorization are covered separately by PostgreSQL integration tests.
const zoneId='00000000-0000-4000-8000-000000000011';
const streetId='00000000-0000-4000-8000-000000000022';
const spaceId='00000000-0000-4000-8000-000000000033';
const now='2026-10-25T12:00:00Z';
const identity={userId:'00000000-0000-4000-8000-000000000044',username:'qa-visual-fixture',roles:['SIMERTPI_ADMIN'],enabled:true,sessionExpiresAt:new Date(Date.now()+60*60*1000).toISOString(),absoluteExpiresAt:new Date(Date.now()+2*60*60*1000).toISOString()};
const zone={id:zoneId,code:'QA-VISUAL-Z',name:'Zona de prueba visual',description:'Fixture local no municipal',active:true,createdAt:now,updatedAt:now,streetCount:1,spaceCount:1,activeSpaceCount:1};
const street={id:streetId,zoneId,zoneCode:zone.code,zoneName:zone.name,code:'QA-VISUAL-S',name:'Calle de prueba visual',description:null,active:true,createdAt:now,updatedAt:now,spaceCount:1};
const space={id:spaceId,zoneId,zoneCode:zone.code,zoneName:zone.name,streetId,streetCode:street.code,streetName:street.name,code:'QA-VISUAL-P',qrCode:`SIMERTPI:SPACE:${spaceId}`,spaceNumber:'QA-01',latitude:0.1,longitude:0.1,spaceType:'STANDARD',active:true,operationalStatus:'AVAILABLE',mapStatus:'NO_ACTIVE_SESSION',createdAt:now,updatedAt:now};
const mapStates=['ACTIVE','ENDING_SOON','EXPIRED','MAX_TIME_REACHED','NO_ACTIVE_SESSION','DISABLED','PAYMENT_PENDING','UNKNOWN'];
const mapMarkers=mapStates.map((mapStatus,index)=>({...space,id:`${spaceId.slice(0,-2)}${String(index+1).padStart(2,'0')}`,code:`QA-MAP-${index+1}`,spaceNumber:`0${index+1}`,latitude:0.1+index*0.00001,active:mapStatus!=='DISABLED',operationalStatus:mapStatus==='NO_ACTIVE_SESSION'?'AVAILABLE':'OCCUPIED',mapStatus}));
const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/ZxoAAAAASUVORK5CYII=','base64');
const responsiveViewports=[{width:1366,height:768},{width:768,height:1024},{width:390,height:844}];
const comparisonViewports=[{width:1536,height:1024},...responsiveViewports];
async function expectInstitutionalGreen(button:any){
  await expect.poll(()=>button.evaluate((element:any)=>getComputedStyle(element).backgroundColor)).toBe('rgb(11, 107, 58)');
}

async function assertResponsive(page:any,testInfo:any,prefix:string){
  for(const viewport of responsiveViewports){
    await page.setViewportSize(viewport);await page.waitForTimeout(80);
    expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(viewport.width+1);
    await page.screenshot({path:testInfo.outputPath(`${prefix}-${viewport.width}x${viewport.height}.png`)});
  }
}

async function mockAdminAndCatalog(page:any){
  await page.route('**/api/v1/admin/auth/me', (route:any)=>route.fulfill({json:identity}));
  await page.route('**/api/v1/admin/parking/zones', (route:any)=>route.fulfill({json:[zone]}));
  await page.route('**/api/v1/admin/parking/configuration/effective**',(route:any)=>route.fulfill({json:{generalTariff:null,generalBillingDaysConfigured:false,zones:[{zoneId,tariff:{id:'tariff-qa',code:'QA-T',name:'Tarifa sintÃ©tica',currency:'USD',amount:'0.25',durationMinutes:60},tariffSource:'GENERAL'}]}}));
  await page.route('**/api/v1/admin/parking/catalog/zones**',(route:any)=>route.fulfill({json:{page:0,size:10,totalElements:1,totalPages:1,content:[zone]}}));
  await page.route('**/api/v1/admin/parking/catalog/streets**',(route:any)=>route.fulfill({json:{page:0,size:10,totalElements:1,totalPages:1,content:[street]}}));
  await page.route('**/api/v1/admin/parking/catalog/spaces**',(route:any)=>route.fulfill({json:{page:0,size:10,totalElements:1,totalPages:1,content:[space]}}));
  await page.route('**/api/v1/admin/parking/zones/*/streets',(route:any)=>route.fulfill({json:[street]}));
  await page.route('**/api/v1/admin/parking/streets/*/spaces',(route:any)=>route.fulfill({json:[space]}));
  await page.route((url:any)=>url.pathname==='/api/v1/admin/parking/catalog/map',(route:any)=>route.fulfill({json:{totalSpaces:8,georeferencedSpaces:8,pendingGeoreference:0,evaluatedAt:now,refreshIntervalSeconds:60,markers:mapMarkers}}));
  await page.route('**/api/v1/admin/parking/catalog/map/tiles/**',(route:any)=>route.fulfill({status:200,contentType:'image/png',body:png,headers:{'cache-control':'private, max-age=604800'}}));
  await page.route('**/api/v1/admin/parking/catalog/spaces/nearby**',(route:any)=>route.fulfill({json:[]}));
}

test('Zonas visual fixture follows the approved list/detail layout and the editor is aligned at desktop and mobile sizes',async({page},testInfo)=>{
  await mockAdminAndCatalog(page);await page.setViewportSize({width:1536,height:1024});await page.goto('http://127.0.0.1:4301/admin/zones');
  await expect(page.locator('.portal-layout.catalog-mode')).toBeVisible();
  const primaryCreate = page.getByRole('button', { name: /Nueva zona/ });
  const primaryColor = await primaryCreate.evaluate((element) => getComputedStyle(element).backgroundColor);
  expect(primaryColor).toBe('rgb(11, 107, 58)');
  expect(Math.round((await page.locator('.sidebar').boundingBox())!.width)).toBe(226);
  expect(Math.round((await page.locator('.topbar').boundingBox())!.height)).toBe(58);
  expect((await page.locator('.catalog-toolbar').boundingBox())!.height).toBeLessThan(80);
  const listWidth=(await page.locator('.catalog-table-wrap').boundingBox())!.width;
  const detailWidth=(await page.locator('.record-detail').boundingBox())!.width;
  expect(Math.abs(listWidth/detailWidth-2)).toBeLessThan(.08);
  await expect(page.getByRole('heading',{name:'Zonas de estacionamiento',exact:true})).toBeVisible();
  await expect(page.getByText('0,25 USD / 60 min').first()).toBeVisible();
  for(const viewport of comparisonViewports){await page.setViewportSize(viewport);expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(viewport.width+1);await page.screenshot({path:testInfo.outputPath(`zones-list-${viewport.width}x${viewport.height}.png`)});}
  await page.setViewportSize({width:1536,height:1024});await page.getByRole('button',{name:/Nueva zona/}).click();
  await expect(page.getByRole('dialog', { name: 'Formulario de creación o edición de registro' })).toBeVisible();
  for(const viewport of comparisonViewports){await page.setViewportSize(viewport);expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(viewport.width+1);await page.screenshot({path:testInfo.outputPath(`zones-create-${viewport.width}x${viewport.height}.png`)});}
  await page.setViewportSize({width:1536,height:1024});
  await page.getByRole('button',{name:'Cancelar',exact:true}).click();
  await page.getByRole('row').filter({hasText:'QA-VISUAL-Z'}).getByRole('button',{name:'Editar'}).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  for(const viewport of comparisonViewports){
    await page.setViewportSize(viewport);await page.waitForTimeout(100);const width=await page.evaluate(()=>document.documentElement.scrollWidth);
    expect(width).toBeLessThanOrEqual(viewport.width+1);
    if(viewport.width<=960) {
      await expect(page.getByRole('navigation',{name:'Secciones de Zonas y espacios'})).toBeVisible();
      const activeMobileItem=page.locator('.catalog-mobile-nav button.active');
      await expect(activeMobileItem).toHaveCSS('background-color','rgb(233, 245, 237)');
      await expect(activeMobileItem).toHaveCSS('color','rgb(11, 107, 58)');
      await expect(page.locator('.catalog-mobile-module-heading')).toHaveCSS('background-color','rgb(11, 107, 58)');
    }
    await page.screenshot({path:testInfo.outputPath(`zones-edit-${viewport.width}x${viewport.height}.png`)});
  }
});

test('Spaces visual fixture displays the backend QR and compact view/download/print actions',async({page},testInfo)=>{
  await mockAdminAndCatalog(page);await page.setViewportSize({width:1536,height:1024});await page.goto('http://127.0.0.1:4301/admin/spaces');
  await expectInstitutionalGreen(page.getByRole('button',{name:'Nuevo espacio'}));
  await expect(page.locator('.catalog-table-wrap')).toBeVisible();
  await expect(page.getByRole('row').filter({hasText:'QA-VISUAL-P'})).toBeVisible();
  await page.evaluate(()=>document.fonts.ready);
  await page.waitForTimeout(100);
  expect((await page.locator('.catalog-toolbar').boundingBox())!.height).toBeLessThan(75);
  await page.getByText('Más filtros',{exact:true}).click();
  await expect(page.locator('.advanced-filter-popover label').filter({hasText:'Calle'})).toBeVisible();
  await expect(page.locator('.advanced-filter-popover label').filter({hasText:'Estado operativo registrado'})).toBeVisible();
  await page.getByText('Más filtros',{exact:true}).click();
  for(const viewport of comparisonViewports){await page.setViewportSize(viewport);expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(viewport.width+1);await page.screenshot({path:testInfo.outputPath(`spaces-list-${viewport.width}x${viewport.height}.png`)});}
  await page.setViewportSize({width:1536,height:1024});
  await expect(page.getByText(space.qrCode)).toBeVisible();
  await expect(page.getByRole('img',{name:/QR del espacio QA-VISUAL-P/})).toHaveAttribute('src',/^data:image\/png;base64,/);
  await expect(page.getByRole('button',{name:'Ver QR'})).toBeVisible();await expect(page.getByRole('button',{name:'Descargar QR'})).toBeVisible();await expect(page.getByRole('button',{name:'Imprimir QR'})).toBeVisible();
  for(const viewport of comparisonViewports){await page.setViewportSize(viewport);expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(viewport.width+1);await page.screenshot({path:testInfo.outputPath(`spaces-detail-qr-${viewport.width}x${viewport.height}.png`)});}
  await page.setViewportSize({width:1536,height:1024});
  await page.getByRole('button',{name:'Ver QR'}).click();
  const qrDialog=page.getByRole('dialog',{name:/QR/});await expect(qrDialog).toBeVisible();
  await expect(qrDialog.getByText(space.qrCode,{exact:true})).toBeVisible();
  for(const viewport of comparisonViewports){await page.setViewportSize(viewport);expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(viewport.width+1);await page.screenshot({path:testInfo.outputPath(`spaces-qr-dialog-${viewport.width}x${viewport.height}.png`)});}
  await page.setViewportSize({width:1536,height:1024});
  await qrDialog.getByRole('button',{name:'Cerrar',exact:true}).click();
  const downloadPromise=page.waitForEvent('download');await page.getByRole('button',{name:'Descargar QR'}).click();
  expect((await downloadPromise).suggestedFilename()).toBe('QR-QA-VISUAL-P.png');
  const popupPromise=page.waitForEvent('popup');await page.getByRole('button',{name:'Imprimir QR'}).click();
  const printPage=await popupPromise;await expect(printPage.getByRole('heading',{name:'Código QR del espacio'})).toBeVisible();await expect(printPage.getByText('GAD Municipal de Piñas')).toBeVisible();
  await expect(printPage.getByRole('img',{name:/QR QA-VISUAL-P/})).toHaveAttribute('src',/^data:image\/png;base64,/);
  await page.getByRole('button',{name:'Nuevo espacio'}).click();
  for(const viewport of comparisonViewports){await page.setViewportSize(viewport);expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(viewport.width+1);await page.screenshot({path:testInfo.outputPath(`spaces-create-${viewport.width}x${viewport.height}.png`)});}
  await page.setViewportSize({width:1536,height:1024});
  await page.getByRole('button',{name:'Cancelar',exact:true}).click();
  await page.getByRole('row').filter({hasText:'QA-VISUAL-P'}).getByRole('button',{name:'Editar'}).click();
  for(const viewport of comparisonViewports){await page.setViewportSize(viewport);expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(viewport.width+1);await page.screenshot({path:testInfo.outputPath(`spaces-edit-${viewport.width}x${viewport.height}.png`)});}
  await page.getByRole('button',{name:'Cancelar',exact:true}).click();
});

test('Streets remain a separate administrative screen with their own detail and create dialog',async({page},testInfo)=>{
  await mockAdminAndCatalog(page);await page.setViewportSize({width:1536,height:1024});await page.goto('http://127.0.0.1:4301/admin/streets');
  await expectInstitutionalGreen(page.getByRole('button',{name:'Nueva calle'}));
  await expect(page.locator('.portal-layout.catalog-mode')).toBeVisible();
  await expect(page.getByRole('heading',{name:'Calles y tramos',exact:true})).toBeVisible();
  await expect(page.getByRole('cell',{name:'QA-VISUAL-S',exact:true})).toBeVisible();
  for(const viewport of comparisonViewports){await page.setViewportSize(viewport);expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(viewport.width+1);await page.screenshot({path:testInfo.outputPath(`streets-list-${viewport.width}x${viewport.height}.png`)});}
  await page.setViewportSize({width:1536,height:1024});await page.getByRole('button',{name:'Nueva calle'}).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  for(const viewport of comparisonViewports){await page.setViewportSize(viewport);expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(viewport.width+1);await page.screenshot({path:testInfo.outputPath(`streets-create-${viewport.width}x${viewport.height}.png`)});}
  await page.setViewportSize({width:1536,height:1024});
  await page.getByRole('button',{name:'Cancelar',exact:true}).click();
  await page.getByRole('row').filter({hasText:'QA-VISUAL-S'}).getByRole('button',{name:'Editar'}).click();
  for(const viewport of comparisonViewports){await page.setViewportSize(viewport);expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(viewport.width+1);await page.screenshot({path:testInfo.outputPath(`streets-edit-${viewport.width}x${viewport.height}.png`)});}
  await page.getByRole('button',{name:'Cancelar',exact:true}).click();
});

test('Space location selector confirms a selected map point before copying it to the form',async({page},testInfo)=>{
  await mockAdminAndCatalog(page);await page.setViewportSize({width:1536,height:1024});await page.goto('http://127.0.0.1:4301/admin/spaces');
  await expect(page.locator('.portal-layout.catalog-mode')).toBeVisible();
  await page.getByRole('button',{name:'Nuevo espacio'}).click();
  const editor=page.locator('dialog.catalog-dialog');
  await editor.getByLabel('Zona').selectOption(zoneId);
  await editor.getByLabel('Calle').selectOption(streetId);
  await page.getByRole('button',{name:'Seleccionar en mapa'}).click();
  await expect(page.getByRole('dialog', { name: 'Seleccionar coordenadas' })).toBeVisible();
  const map=page.locator('.parking-map-canvas.leaflet-container');
  await expect(map).toBeVisible();
  const mapBox=await map.boundingBox();expect(mapBox).not.toBeNull();
  await page.mouse.click(mapBox!.x+mapBox!.width*.25,mapBox!.y+mapBox!.height*.25);
  await expect(page.locator('.coordinate-readout')).toHaveText(/-?[0-9]+[,.][0-9]+, -?[0-9]+[,.][0-9]+/);
  for(const viewport of comparisonViewports){await page.setViewportSize(viewport);expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(viewport.width+1);await page.screenshot({path:testInfo.outputPath(`spaces-location-selector-${viewport.width}x${viewport.height}.png`)});}
  await page.setViewportSize({width:1536,height:1024});
  await page.getByRole('button',{name:/Confirmar ubicaci/}).click();
  await expect(page.getByLabel('Latitud')).not.toHaveValue('');
  await expect(page.getByLabel('Longitud')).not.toHaveValue('');
});

test('Map visual fixture shows only the actual marker response and exposes an accessible marker list',async({page},testInfo)=>{
  const pageErrors:string[]=[];page.on('pageerror',error=>pageErrors.push(error.message));
  await mockAdminAndCatalog(page);await page.setViewportSize({width:1536,height:1024});await page.goto('http://127.0.0.1:4301/admin/map');
  await expect(page.getByRole('heading',{name:'Ubicaciones georreferenciadas'})).toBeVisible();
  await expect(page.getByRole('list',{name:/Espacios georreferenciados/}).getByText(/01.*QA-MAP-1/)).toBeVisible();
  await expect(page.locator('.parking-map-canvas.leaflet-container')).toBeVisible();
  await expect(page.getByText('Sin sesión activa no confirma disponibilidad física.')).toBeVisible();
  await expect(page.locator('.map-marker-list li')).toHaveCount(8);
  await expect(page.locator('.simertpi-map-cluster')).toBeVisible();
  await expect(page.locator('.simertpi-cluster-segment')).toHaveCount(8);
  await expect(page.locator('.map-marker-list').getByText(/Próxima a vencer/)).toBeVisible();
  for (const label of ['Sesión vigente', 'Próxima a vencer', 'Tiempo vencido; sigue ocupado', 'Tiempo máximo alcanzado', 'Sin sesión activa registrada', 'Deshabilitado', 'Pago pendiente', 'Estado no disponible'])
    await expect(page.locator('.map-legend span').filter({ hasText: label }).locator('b')).toHaveText('1');
  const cluster=page.locator('.simertpi-map-cluster').first();await cluster.click();await cluster.click();
  await expect(page.locator('.map-selection').getByText(/QA-MAP-/)).toBeVisible();
  await expect(page.locator('.map-update-time')).toContainText('hora de Ecuador');
  const markerButton=page.locator('.map-marker-list > li > button').first();
  await expect(markerButton).toHaveCSS('display','flex');
  expect(pageErrors).toEqual([]);
  for (const viewport of comparisonViewports) {
    await page.setViewportSize(viewport);
    expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(viewport.width+1);
    await page.screenshot({path:`artifacts/map-status-cluster-mock-${viewport.width}x${viewport.height}.png`});
  }
});
