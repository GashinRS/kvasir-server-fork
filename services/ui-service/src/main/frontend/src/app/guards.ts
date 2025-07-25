import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import Keycloak from 'keycloak-js';
import { SessionService } from './services/session.service';

export const sessionActiveGuard: CanActivateFn = async (route, state) => {
  const session = inject(SessionService);
  const router = inject(Router);

  if (session.sessionActive()) {
    const kc = inject(Keycloak, { optional: true });
    return kc?.authenticated ?? false;
  } else {
    return router.createUrlTree(['/login']);
  }
};
