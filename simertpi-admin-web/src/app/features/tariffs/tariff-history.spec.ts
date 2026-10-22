import { TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { of } from 'rxjs';
import { TariffsApi } from './tariffs-api';
import { TariffHistory } from './tariff-history';

describe('Tariff history screen', () => {
  it('loads audit snapshots and shows responsible actor, reason, and Ecuador-local effective time', async () => {
    const api = { tariffHistory: vi.fn().mockReturnValue(of({
      items: [{ id: 'event-1', tariffId: 'tariff-2', previousTariffId: 'tariff-1', action: 'REVISED',
        actorId: 'admin-1', actorName: 'qa.admin', occurredAt: '2026-10-22T15:00:00Z',
        effectiveAt: '2027-01-01T05:00:00Z', reason: 'Corrección aprobada',
        beforeState: { amount: 0.25, code: 'GENERAL' }, afterState: { amount: 0.3, code: 'GENERAL-V2', name: 'Tarifa general' } }],
      page: 0, size: 25, total: 1,
    })) };
    await TestBed.configureTestingModule({
      imports: [TariffHistory],
      providers: [
        { provide: TariffsApi, useValue: api },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: { get: () => 'tariff-1' }, pathFromRoot: [{ data: {} }] } } },
      ],
    }).compileComponents();
    const fixture = TestBed.createComponent(TariffHistory);
    fixture.detectChanges();
    expect(api.tariffHistory).toHaveBeenCalledWith(0, 25, 'tariff-1');
    expect(fixture.nativeElement.textContent).toContain('Historial de cambios de tarifas');
    expect(fixture.nativeElement.textContent).toContain('Nueva versión');
    expect(fixture.nativeElement.textContent).toContain('qa.admin');
    expect(fixture.nativeElement.textContent).toContain('Corrección aprobada');
    expect(fixture.nativeElement.textContent).toContain('Importe');
    expect(fixture.nativeElement.textContent).toContain('enero de 2027');
  });
});
