import {
  AfterViewInit,
  Component,
  ElementRef,
  EventEmitter,
  Input,
  OnChanges,
  OnDestroy,
  Output,
  SimpleChanges,
  ViewChild,
  inject,
} from '@angular/core';
import type { DivIcon, Map as LeafletMap, Marker, TileLayer } from 'leaflet';
import { MapMarker } from './zones-api';

type MapTilesState = 'loading' | 'ready' | 'partial' | 'error';
type MapMarkerState = MapMarker['mapStatus'];

@Component({
  selector: 'app-parking-map',
  standalone: true,
  template:
    '<div #map class="parking-map-canvas" role="application" aria-label="Mapa interactivo de estacionamientos"></div>',
  styleUrl: './parking-map.scss',
})
export class ParkingMap implements AfterViewInit, OnChanges, OnDestroy {
  @Input() markers: MapMarker[] = [];
  @Input() selector = false;
  @Input() selectedPoint: { latitude: number; longitude: number } | null = null;
  @Input() refreshKey = 0;
  @Input() fitRequest = 0;
  @Input() selectedMarkerId = '';
  @Output() pointSelected = new EventEmitter<{ latitude: number; longitude: number }>();
  @Output() markerSelected = new EventEmitter<MapMarker>();
  @Output() tilesStateChange = new EventEmitter<MapTilesState>();
  @ViewChild('map') private mapHost!: ElementRef<HTMLDivElement>;

  private readonly host = inject(ElementRef<HTMLElement>);
  private map: LeafletMap | null = null;
  private leaflet: typeof import('leaflet') | null = null;
  private tileLayer: TileLayer | null = null;
  private pins: Marker[] = [];
  private candidate: Marker | null = null;
  private resizeObserver: ResizeObserver | null = null;
  private destroyed = false;
  private geometryKey = '';

  async ngAfterViewInit() {
    const imported = await import('leaflet');
    if (this.destroyed) return;
    this.leaflet = (typeof imported.map === 'function' ? imported : imported.default) as typeof import('leaflet');
    this.map = this.leaflet
      // CSS zoom transitions can finish after Angular removes a responsive map view.
      // Keep zoom changes deterministic so Leaflet cannot access a removed map pane.
      .map(this.mapHost.nativeElement, { zoomControl: true, scrollWheelZoom: false, preferCanvas: true, zoomAnimation: false })
      .setView([-3.67, -79.65], 12);
    this.tileLayer = this.leaflet.tileLayer('/api/v1/admin/parking/catalog/map/tiles/{z}/{x}/{y}', {
        maxZoom: 19,
        attribution:
          '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a> contributors',
      });
    this.trackTileState(this.tileLayer);
    this.tileLayer.addTo(this.map);
    this.map.on('click', (event: import('leaflet').LeafletMouseEvent) => {
      if (!this.selector) return;
      const point = {
        latitude: Number(event.latlng.lat.toFixed(7)),
        longitude: Number(event.latlng.lng.toFixed(7)),
      };
      this.pointSelected.emit(point);
      this.showCandidate(point.latitude, point.longitude);
    });
    this.map.on('zoomend moveend', () => this.paint(false));
    this.paint(true);
    if (this.selectedPoint) this.showCandidate(this.selectedPoint.latitude, this.selectedPoint.longitude);
    this.resizeObserver = new ResizeObserver(() => this.map?.invalidateSize({ pan: false }));
    this.resizeObserver.observe(this.host.nativeElement);
    setTimeout(() => this.map?.invalidateSize({ pan: false }), 80);
  }

  ngOnChanges(changes: SimpleChanges) {
    if (changes['markers'] && this.map) this.paint(true);
    if (changes['selectedPoint'] && this.map) {
      if (this.selectedPoint) this.showCandidate(this.selectedPoint.latitude, this.selectedPoint.longitude);
      else { this.candidate?.remove(); this.candidate = null; }
    }
    if (changes['refreshKey'] && !changes['refreshKey'].firstChange && this.map && this.tileLayer) {
      this.tilesStateChange.emit('loading');
      this.tileLayer.redraw();
      this.map.invalidateSize({ pan: false });
    }
    if (changes['fitRequest'] && !changes['fitRequest'].firstChange) this.fitResults();
    if (changes['selectedMarkerId'] && this.selectedMarkerId) {
      const selected = this.markers.find((item) => item.id === this.selectedMarkerId);
      if (selected) this.focus(selected);
    }
  }

  ngOnDestroy() {
    this.destroyed = true;
    this.resizeObserver?.disconnect();
    this.resizeObserver = null;
    // Cancel Leaflet's pending pan/zoom transition before removing the pane.
    // Otherwise its transition-end callback can run after Angular destroys this view.
    this.map?.stop();
    this.map?.off();
    this.map?.remove();
    this.map = null;
  }

  focus(item: MapMarker) {
    if (this.map && item.latitude !== null && item.longitude !== null)
      this.map.setView([item.latitude, item.longitude], 18);
  }

  private trackTileState(layer: TileLayer) {
    let loaded = 0;
    let failed = 0;
    layer.on('loading', () => {
      loaded = 0;
      failed = 0;
      this.tilesStateChange.emit('loading');
    });
    layer.on('tileload', () => {
      loaded++;
      this.tilesStateChange.emit('ready');
    });
    layer.on('tileerror', () => { failed++; });
    layer.on('load', () => this.tilesStateChange.emit(failed > 0 ? loaded > 0 ? 'partial' : 'error' : 'ready'));
  }

  private paint(fit: boolean) {
    if (!this.map || !this.leaflet) return;
    this.pins.forEach((pin) => pin.remove());
    this.pins = [];
    const located = this.markers.filter(
      (item) => item.latitude !== null && item.longitude !== null,
    );
    if (!located.length) {
      this.geometryKey = '';
      return;
    }

    // Group nearby points in screen pixels at the current zoom. The group contains only real records.
    const zoom = this.map.getZoom();
    const buckets = new Map<string, MapMarker[]>();
    for (const item of located) {
      const point = this.map.project([item.latitude!, item.longitude!], zoom);
      const key = `${Math.floor(point.x / 48)}:${Math.floor(point.y / 48)}`;
      const bucket = buckets.get(key) ?? [];
      bucket.push(item);
      buckets.set(key, bucket);
    }

    for (const group of buckets.values()) {
      if (group.length === 1) {
        const item = group[0];
        const label = this.stateLabel(item.mapStatus);
        const tooltip = document.createElement('span');
        tooltip.textContent = `${item.spaceNumber} · ${item.code} — ${label}`;
        const pin = this.leaflet
          .marker([item.latitude!, item.longitude!], {
            icon: this.pinIcon(item.mapStatus),
            keyboard: true,
            title: `${item.spaceNumber} · ${item.code} · ${label}`,
            alt: `${item.spaceNumber}, espacio ${item.code}, ${label}`,
            riseOnHover: true,
          })
          .addTo(this.map);
        pin.bindTooltip(tooltip, { direction: 'top' });
        pin.on('click', () => this.markerSelected.emit(item));
        this.pins.push(pin);
        continue;
      }

      const latitude = group.reduce((sum, item) => sum + item.latitude!, 0) / group.length;
      const longitude = group.reduce((sum, item) => sum + item.longitude!, 0) / group.length;
      const center = [latitude, longitude] as [number, number];
      const cluster = this.leaflet
        .marker(center, {
          icon: this.clusterIcon(group),
          keyboard: true,
          title: `${group.length} espacios agrupados. Activar para acercar el mapa.`,
          alt: `${group.length} espacios agrupados`,
          riseOnHover: true,
        })
        .addTo(this.map);
      const clusterTooltip = document.createElement('span');
      clusterTooltip.textContent = `${group.length} espacios cercanos · ampliar para verlos`;
      cluster.bindTooltip(clusterTooltip);
      cluster.on('click', () => {
        if (this.map!.getZoom() >= 19) {
          this.markerSelected.emit(group[0]);
          return;
        }
        const bounds = this.leaflet!.latLngBounds(
          group.map((item) => [item.latitude!, item.longitude!] as [number, number]),
        );
        this.map!.fitBounds(bounds.pad(0.3), { maxZoom: 19, animate: true });
      });
      this.pins.push(cluster);
    }

    const nextGeometryKey = located.map((item) => `${item.id}:${item.latitude}:${item.longitude}`).sort().join('|');
    const geometryChanged = nextGeometryKey !== this.geometryKey;
    if (fit && geometryChanged) {
      const points = located.map((item) => [item.latitude!, item.longitude!] as [number, number]);
      this.map.fitBounds(this.leaflet.latLngBounds(points).pad(0.2), { maxZoom: 16 });
    }
    this.geometryKey = nextGeometryKey;
  }

  private fitResults() {
    if (!this.map || !this.leaflet) return;
    const points = this.markers.filter((item) => item.latitude !== null && item.longitude !== null)
      .map((item) => [item.latitude!, item.longitude!] as [number, number]);
    if (points.length) this.map.fitBounds(this.leaflet.latLngBounds(points).pad(0.2), { maxZoom: 16, animate: true });
  }

  private pinIcon(state: MapMarkerState): DivIcon {
    const className = this.stateClass(state);
    return this.leaflet!.divIcon({
      className: `simertpi-map-icon simertpi-map-icon-${className}`,
      html: '<span class="simertpi-space-pin"><span class="simertpi-space-pin-core">P</span></span>',
      iconSize: [34, 42],
      iconAnchor: [17, 42],
      tooltipAnchor: [0, -38],
    });
  }

  private clusterIcon(group: MapMarker[]): DivIcon {
    const states: MapMarkerState[] = ['ACTIVE', 'ENDING_SOON', 'EXPIRED', 'MAX_TIME_REACHED', 'NO_ACTIVE_SESSION', 'DISABLED', 'PAYMENT_PENDING', 'UNKNOWN'];
    const segments = states
      .map((state) => ({ state, count: group.filter((item) => item.mapStatus === state).length }))
      .filter((item) => item.count > 0)
      .map((item) => `<i class="simertpi-cluster-segment ${this.stateClass(item.state)}" title="${item.count}"></i>`)
      .join('');
    return this.leaflet!.divIcon({
      className: 'simertpi-map-icon simertpi-map-cluster',
      html: `<span class="simertpi-cluster-count">${group.length}</span><span class="simertpi-cluster-segments">${segments}</span>`,
      iconSize: [48, 48],
      iconAnchor: [24, 24],
    });
  }

  private showCandidate(latitude: number, longitude: number) {
    if (!this.map || !this.leaflet) return;
    this.candidate?.remove();
    const icon = this.leaflet.divIcon({
      className: 'simertpi-map-icon simertpi-map-candidate',
      html: '<span class="simertpi-candidate-target" aria-hidden="true">+</span>',
      iconSize: [42, 42],
      iconAnchor: [21, 21],
    });
    this.candidate = this.leaflet
      .marker([latitude, longitude], { icon, keyboard: false, title: 'Punto seleccionado, pendiente de confirmación' })
      .addTo(this.map);
    this.map.setView([latitude, longitude], Math.max(this.map.getZoom(), 16));
    setTimeout(() => this.map?.invalidateSize({ pan: false }), 80);
  }

  private stateClass(state: MapMarkerState): string {
    return ({
      ACTIVE: 'active',
      ENDING_SOON: 'ending-soon',
      EXPIRED: 'expired',
      MAX_TIME_REACHED: 'max-time-reached',
      NO_ACTIVE_SESSION: 'no-session',
      DISABLED: 'disabled',
      PAYMENT_PENDING: 'payment-pending',
      UNKNOWN: 'unknown',
    } as const)[state] ?? 'unknown';
  }

  private stateLabel(state: MapMarkerState): string {
    return ({
      ACTIVE: 'sesión vigente',
      ENDING_SOON: 'próxima a vencer',
      EXPIRED: 'tiempo vencido; sigue ocupado hasta el cierre',
      MAX_TIME_REACHED: 'tiempo máximo alcanzado; sigue ocupado',
      NO_ACTIVE_SESSION: 'sin sesión activa registrada; no confirma disponibilidad física',
      DISABLED: 'deshabilitado administrativamente',
      PAYMENT_PENDING: 'pago pendiente; espacio reservado',
      UNKNOWN: 'estado operativo no disponible',
    } as const)[state] ?? 'estado operativo no disponible';
  }
}
