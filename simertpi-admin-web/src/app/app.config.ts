import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter, withInMemoryScrolling } from '@angular/router';
import { provideHttpClient, withInterceptors, withXsrfConfiguration } from '@angular/common/http';
import { routes } from './app.routes';
import { apiInterceptor } from './core/http/api.interceptor';
export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withInMemoryScrolling({ scrollPositionRestoration: 'top' })),
    provideHttpClient(
      withInterceptors([apiInterceptor]),
      withXsrfConfiguration({
        cookieName: '__Host-SIMERTPI_ADMIN_XSRF',
        headerName: 'X-XSRF-TOKEN',
      }),
    ),
  ],
};
