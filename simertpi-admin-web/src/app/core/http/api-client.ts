import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { PORTAL_CONFIG } from '../config/portal-config';
/** Reserved transport. No component calls this client in CP25.3-B. */
@Injectable({ providedIn: 'root' })
export class ApiClient {
  private readonly http = inject(HttpClient);
  private readonly config = inject(PORTAL_CONFIG);
  get<T>(path: string, options: { params?: HttpParams } = {}) {
    return this.http.get<T>(this.url(path), options);
  }
  post<T>(path: string, body: unknown) {
    return this.http.post<T>(this.url(path), body);
  }
  put<T>(path: string, body: unknown) {
    return this.http.put<T>(this.url(path), body);
  }
  private url(path: string): string {
    if (!/^admin(?:\/[a-zA-Z0-9_-]+)*$/.test(path))
      throw new Error('Invalid administrative API path');
    return `${this.config.apiBaseUrl.replace(/\/$/, '')}/${path}`;
  }
}
