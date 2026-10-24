import { TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { of } from 'rxjs';
import { TariffHistoryItem, TariffsApi } from './tariffs-api';
import { TariffHistory } from './tariff-history';

const revisedEvent = {
  id: 'event-1', tariffId: 'tariff-2', previousTariffId: 'tariff-1', action: 'REVISED',
  actorId: 'admin-1', actorName: 'qa.admin', occurredAt: '2026-10-22T15:00:00Z',
  effectiveAt: '2027-01-01T05:00:00Z', reason: 'Corrección aprobada',
  beforeState: {
    id: 'tariff-1', versionGroupId: 'series-1', previousVersionId: null, rowVersion: 0,
    code: 'GENERAL', name: 'Tarifa general', zoneId: null, currency: 'USD', amount: 0.25,
    durationMinutes: 60, minMinutes: 15, maxContinuousMinutes: 240, gracePeriodMinutes: 5,
    roundingMode: 'HALF_UP', validFrom: '2026-01-01T05:00:00Z', validTo: null,
    normativeReference: 'Ordenanza anterior', active: true, cancelledAt: null,
    cancelledByUserId: null, cancellationReason: null,
  },
  afterState: {
    id: 'tariff-2', versionGroupId: 'series-1', previousVersionId: 'tariff-1', rowVersion: 0,
    code: 'GENERAL-V2', name: 'Tarifa general revisada', zoneId: null, currency: 'USD', amount: 0.3,
    durationMinutes: 45, minMinutes: 15, maxContinuousMinutes: 240, gracePeriodMinutes: 5,
    roundingMode: 'HALF_UP', validFrom: '2027-01-01T05:00:00Z', validTo: null,
    normativeReference: 'Ordenanza nueva', active: true, cancelledAt: null,
    cancelledByUserId: null, cancellationReason: null,
  },
};

describe('Tariff history screen', () => {
  beforeEach(() => {
    Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
      configurable: true, value(this: HTMLDialogElement) { this.setAttribute('open', ''); },
    });
    Object.defineProperty(HTMLDialogElement.prototype, 'close', {
      configurable: true, value(this: HTMLDialogElement) { this.removeAttribute('open'); },
    });
  });

  async function createFixture(event: TariffHistoryItem = revisedEvent) {
    const api = { tariffHistory: vi.fn().mockReturnValue(of({ items: [event], page: 0, size: 25, total: 1 })) };
    await TestBed.configureTestingModule({
      imports: [TariffHistory],
      providers: [
        { provide: TariffsApi, useValue: api },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: { get: () => 'tariff-1' }, pathFromRoot: [{ data: {} }] } } },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(TariffHistory);
    fixture.detectChanges();
    return { fixture, api };
  }

  it('shows tariff series, readable columns, Ecuador time, action, actor, separate reason and changed values', async () => {
    const { fixture, api } = await createFixture();
    expect(api.tariffHistory).toHaveBeenCalledWith(0, 25, 'tariff-1');
    expect(fixture.nativeElement.textContent).toContain('Historial de cambios de tarifas');
    expect(fixture.nativeElement.textContent).toContain('GENERAL-V2 · Tarifa general revisada');
    expect(fixture.nativeElement.textContent).toContain('Nueva versión');
    expect(fixture.nativeElement.textContent).toContain('qa.admin');
    expect(fixture.nativeElement.textContent).toContain('Corrección aprobada');
    expect(fixture.nativeElement.textContent).toContain('Importe: 0,25 USD → 0,30 USD');
    expect(fixture.nativeElement.textContent).toContain('enero de 2027');
    expect(fixture.nativeElement.querySelectorAll('.history-event-card')).toHaveLength(1);
    expect(fixture.nativeElement.querySelector('.history-event-meta')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.history-detail-dialog')).toBeNull();
  });

  it('opens the complete before and after snapshots in an accessible dialog on the independent history screen', async () => {
    const { fixture } = await createFixture();
    fixture.componentInstance.openDetails(revisedEvent);
    fixture.detectChanges();
    const dialog = fixture.nativeElement.querySelector('.history-detail-dialog') as HTMLDialogElement;
    expect(dialog).not.toBeNull();
    expect(dialog.getAttribute('aria-labelledby')).toBe('history-detail-title');
    expect(dialog.textContent).toContain('Detalle del cambio');
    expect(dialog.textContent).toContain('Valores anteriores');
    expect(dialog.textContent).toContain('Valores registrados después del cambio');
    expect(dialog.textContent).toContain('Ordenanza anterior');
    expect(dialog.textContent).toContain('Ordenanza nueva');
    expect(dialog.textContent).toContain('0,25 USD');
    expect(dialog.textContent).toContain('0,30 USD');
    expect(dialog.textContent).toContain('Identificador de tarifa');
    fixture.componentInstance.closeDetails();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.history-detail-dialog')).toBeNull();
    expect(fixture.nativeElement.querySelector('.history-event-card')).not.toBeNull();
  });

  it('explains missing legacy actor and reason without inventing either value', async () => {
    const legacyEvent = { ...revisedEvent, actorId: null, actorName: null, reason: '' };
    const { fixture } = await createFixture(legacyEvent);
    expect(fixture.nativeElement.textContent).toContain('No disponible para este registro histórico anterior.');
    expect(fixture.nativeElement.textContent).toContain('No se registró un motivo para este evento histórico.');
    expect(fixture.nativeElement.textContent).not.toContain('admin.fixture');
    expect(fixture.nativeElement.querySelector('.history-detail-dialog')).toBeNull();
  });
});
