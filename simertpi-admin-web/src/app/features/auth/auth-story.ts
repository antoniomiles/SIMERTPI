import { Component } from '@angular/core';
import { Brand } from '../../shared/ui/brand';

@Component({
  selector: 'aside[appAuthStory]',
  imports: [Brand],
  host: { class: 'login-story', 'aria-label': 'SIMERTPI Municipal' },
  templateUrl: './auth-story.html',
})
export class AuthStory {}
