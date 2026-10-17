import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { App } from './app/app';

bootstrapApplication(App, appConfig).catch(() => {
  const message = document.createElement('p');
  message.setAttribute('role', 'alert');
  message.textContent =
    'No se pudo iniciar SIMERTPI. Recargue la página o contacte al soporte municipal.';
  document.body.replaceChildren(message);
});
