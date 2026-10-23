import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { OperationToastOutlet } from './shared/ui/operation-toast-outlet';
@Component({ selector: 'app-root', imports: [RouterOutlet, OperationToastOutlet], template: '<router-outlet /><app-operation-toast-outlet />' })
export class App {}
