import { computed, inject, Injectable, signal } from '@angular/core';
import { LoginSessionService } from './login-session.service';

@Injectable({
  providedIn: 'root',
})
export class SessionService {
  podName = signal<string | null>(null);
  sessionActive = computed<boolean>(() => this.podName() != null);

  private loginSessionService = inject(LoginSessionService);

  constructor() {
    const loginSession = this.loginSessionService.getCurrentLoginSession();
    if (loginSession != null) {
      this.podName.set(loginSession.podName);
    }
  }

  async deactivateSession(): Promise<any> {
    this.podName.set(null);
  }

  /**
   * Providing null will deactivate the session
   * @param podName
   */
  async activateSession(podName: string | null) {
    let currentPodName =
      this.loginSessionService.getCurrentLoginSession()?.podName;
    if (currentPodName != podName && podName != null) {
      this.loginSessionService.updateCurrentLoginSession(podName);
    }
    this.podName.set(podName);
  }
}
