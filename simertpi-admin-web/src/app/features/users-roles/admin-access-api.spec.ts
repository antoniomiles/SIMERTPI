import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { apiInterceptor } from '../../core/http/api.interceptor';
import { PORTAL_CONFIG } from '../../core/config/portal-config';
import { environment } from '../../../environments/environment';
import { AdminAccessApi } from './admin-access-api';

describe('Administrative access API', () => {
  beforeEach(() => TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([apiInterceptor])),
      provideHttpClientTesting(),
      { provide: PORTAL_CONFIG, useValue: environment },
    ],
  }));
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('queries the account directory with bounded server filters using GET only', () => {
    TestBed.inject(AdminAccessApi).users('  name  ', 'INSPECTOR', false, 3).subscribe();
    const req = TestBed.inject(HttpTestingController).expectOne((request) => request.url === '/api/v1/admin/access/users');
    expect(req.request.method).toBe('GET');
    expect(req.request.withCredentials).toBe(true);
    expect(Object.fromEntries(req.request.params.keys().map((key) => [key, req.request.params.get(key)]))).toEqual({
      page: '3', size: '25', q: 'name', role: 'INSPECTOR', enabled: 'false',
    });
    req.flush({ content: [], page: 3, size: 25, totalElements: 0, totalPages: 0 });
  });

  it('queries the catalog through GET without requesting or sending permission mutations', () => {
    TestBed.inject(AdminAccessApi).roles().subscribe();
    const req = TestBed.inject(HttpTestingController).expectOne('/api/v1/admin/access/roles');
    expect(req.request.method).toBe('GET');
    expect(req.request.body).toBeNull();
    req.flush([]);
  });
});
