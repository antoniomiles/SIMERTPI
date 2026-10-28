import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal, OnDestroy } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { normalizeError } from '../../core/http/portal-error';
import { DataState } from '../../shared/ui/data-state';
import { PageHeading } from '../../shared/ui/page-heading';
import {
  AdminEvidence,
  AdminViolation,
  AdminViolationPage,
  ViolationStatus,
  ViolationsApi,
} from './violations-api';

const PAGE_SIZE = 25;
const MAX_RANGE_DAYS = 366;
type ViewState = 'loading' | 'error' | 'empty' | 'ready';
type DetailState = 'idle' | 'loading' | 'error';
type EvidenceState = 'idle' | 'loading' | 'ready' | 'error';
const STATUS_LABELS: Record<ViolationStatus, string> = {
  OPEN: 'Abierta',
  NOTIFIED: 'Notificada',
  PAID: 'Marcada como pagada',
  CANCELLED: 'Cancelada',
  DISPUTED: 'En disputa',
};

@Component({
  selector: 'app-violations',
  imports: [DatePipe, DataState, PageHeading, ReactiveFormsModule],
  templateUrl: './violations.html',
  styleUrl: './violations.scss',
})
export class Violations implements OnDestroy {
  private readonly api = inject(ViolationsApi);
  private readonly builder = inject(FormBuilder);
  readonly preview = inject(ActivatedRoute).snapshot.pathFromRoot.some(
    (item) => item.data['preview'] === true,
  );
  readonly state = signal<ViewState>(this.preview ? 'empty' : 'loading');
  readonly result = signal<AdminViolationPage | null>(null);
  readonly errorMessage = signal('');
  readonly page = signal(0);
  readonly pageCount = computed(() => this.result()?.totalPages ?? 0);
  readonly selected = signal<AdminViolation | null>(null);
  readonly detailState = signal<DetailState>('idle');
  readonly detailError = signal('');
  readonly evidence = signal<AdminEvidence[]>([]);
  readonly evidenceState = signal<EvidenceState>('idle');
  readonly evidenceError = signal('');
  readonly downloadingEvidenceId = signal<string | null>(null);
  readonly evidenceDownloadMessage = signal('');
  readonly filters = this.builder.nonNullable.group({
    q: ['', [Validators.maxLength(100)]],
    plate: ['', [Validators.maxLength(10)]],
    zone: ['', [Validators.maxLength(100)]],
    status: [''],
    from: [''],
    to: [''],
  });

  constructor() {
    if (!this.preview) this.load();
  }

  load(page = this.page()) {
    if (this.preview || this.filters.invalid) {
      this.filters.markAllAsTouched();
      return;
    }
    const values = this.filters.getRawValue();
    const range =
      values.from && values.to
        ? (Date.parse(`${values.to}T00:00:00Z`) - Date.parse(`${values.from}T00:00:00Z`)) /
          86_400_000
        : 0;
    if (values.from && values.to && (values.from > values.to || range >= MAX_RANGE_DAYS)) {
      this.errorMessage.set('El rango debe ser válido y no superar 366 días.');
      this.state.set('error');
      return;
    }
    this.page.set(Math.max(0, page));
    this.errorMessage.set('');
    this.selected.set(null);
    this.state.set('loading');
    this.api
      .search({
        page: this.page(),
        size: PAGE_SIZE,
        q: values.q.trim() || undefined,
        plate: values.plate.trim() || undefined,
        zone: values.zone.trim() || undefined,
        status: (values.status || undefined) as ViolationStatus | undefined,
        from: values.from || undefined,
        to: values.to || undefined,
      })
      .subscribe({
        next: (result) => {
          this.result.set(result);
          this.state.set(result.items.length ? 'ready' : 'empty');
        },
        error: (error: unknown) => {
          this.errorMessage.set(
            normalizeError(error).message || 'No se pudieron consultar las infracciones.',
          );
          this.state.set('error');
        },
      });
  }

  applyFilters() {
    this.load(0);
  }

  clearFilters() {
    this.filters.reset({ q: '', plate: '', zone: '', status: '', from: '', to: '' });
    this.load(0);
  }

  openDetail(row: AdminViolation) {
    this.selected.set(row);
    this.detailState.set('loading');
    this.detailError.set('');
    this.evidence.set([]);
    this.evidenceState.set('loading');
    this.evidenceError.set('');
    this.evidenceDownloadMessage.set('');
    this.api.detail(row.id).subscribe({
      next: (detail) => {
        this.selected.set(detail);
        this.detailState.set('idle');
        this.loadEvidence(detail.id);
      },
      error: (error: unknown) => {
        this.detailError.set(normalizeError(error).message || 'No se pudo cargar el detalle.');
        this.detailState.set('error');
        this.evidenceState.set('idle');
      },
    });
  }

  closeDetail() {
    this.selected.set(null);
    this.detailState.set('idle');
    this.detailError.set('');
    this.evidence.set([]);
    this.evidenceState.set('idle');
    this.evidenceError.set('');
    this.evidenceDownloadMessage.set('');
    this.downloadingEvidenceId.set(null);
  }

  loadEvidence(violationId: string) {
    this.evidenceState.set('loading');
    this.evidenceError.set('');
    this.api.evidence(violationId).subscribe({
      next: (items) => {
        if (this.selected()?.id !== violationId) return;
        this.evidence.set(items);
        this.evidenceState.set('ready');
      },
      error: (error: unknown) => {
        if (this.selected()?.id !== violationId) return;
        this.evidenceError.set(
          normalizeError(error).message || 'No se pudieron consultar las evidencias.',
        );
        this.evidenceState.set('error');
      },
    });
  }

  downloadEvidence(item: AdminEvidence) {
    if (this.downloadingEvidenceId()) return;
    this.downloadingEvidenceId.set(item.id);
    this.evidenceDownloadMessage.set('');
    this.api.downloadEvidence(item.id).subscribe({
      next: (blob) => {
        try {
          const url = URL.createObjectURL(blob);
          const link = document.createElement('a');
          link.href = url;
          link.download = evidenceDownloadName(item);
          link.rel = 'noopener';
          link.hidden = true;
          document.body.append(link);
          link.click();
          link.remove();
          window.setTimeout(() => URL.revokeObjectURL(url), 1000);
          this.evidenceDownloadMessage.set('Descarga de evidencia iniciada.');
        } catch {
          this.evidenceError.set('No se pudo preparar la descarga de la evidencia.');
        } finally {
          this.downloadingEvidenceId.set(null);
        }
      },
      error: (error: unknown) => {
        this.evidenceError.set(
          normalizeError(error).message || 'No se pudo descargar la evidencia.',
        );
        this.downloadingEvidenceId.set(null);
      },
    });
  }

  ngOnDestroy() {
    this.closeDetail();
  }

  previous() {
    if (this.page() > 0) this.load(this.page() - 1);
  }

  next() {
    if (this.page() + 1 < this.pageCount()) this.load(this.page() + 1);
  }

  statusLabel(status: ViolationStatus) {
    return STATUS_LABELS[status] ?? status;
  }

  evidenceTypeLabel(item: AdminEvidence) {
    if (item.contentType === 'image/jpeg') return 'Fotografía JPEG';
    if (item.contentType === 'image/png') return 'Fotografía PNG';
    if (item.contentType === 'application/pdf') return 'Documento PDF';
    return item.evidenceType === 'PHOTO' ? 'Fotografía' : 'Archivo adjunto';
  }

  fileSizeLabel(bytes: number) {
    if (!Number.isFinite(bytes) || bytes < 0) return 'Tamaño no disponible';
    return bytes < 1024 * 1024
      ? `${Math.max(1, Math.round(bytes / 1024))} KB`
      : `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  }

  evidenceDownloadLabel(item: AdminEvidence) {
    return `Evidencia ${item.id.slice(0, 8)}`;
  }

  location(row: AdminViolation) {
    return `${row.zoneCode} · ${row.zoneName} / ${row.streetCode} · ${row.streetName}`;
  }
}

function evidenceDownloadName(item: AdminEvidence): string {
  const extension =
    item.contentType === 'image/jpeg'
      ? '.jpg'
      : item.contentType === 'image/png'
        ? '.png'
        : item.contentType === 'application/pdf'
          ? '.pdf'
          : '';
  return `evidencia-${item.id.slice(0, 8)}${extension}`;
}
