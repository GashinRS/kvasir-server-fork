import { inject, Injectable, signal } from '@angular/core';
import Keycloak from 'keycloak-js';

interface Session {
  podName: string;
  podOwner: string;
}

const SESSION_KEY = 'kvasir.session';

@Injectable({
  providedIn: 'root',
})
export class SessionService {
  private kc = inject(Keycloak);

  podName = signal<string | null>(null);
  podOwner = signal<string | null>(null);

  isActive(): boolean {
    return this.kc.authenticated;
  }

  /**
   * Call this only on application entry, as it will try to read the pod from the query
   * parameter. If it is not there, fallback to localStorage, else just null;
   */
  initialize() {
    const params = new URLSearchParams(window.location.search.slice(1));
    const podName =
      params.get('pod') ?? this.getSessionFromLocalStorage()?.podName ?? null;
    this.podName.set(podName);
    this.kc.onReady = (authenticated: boolean) => {
      if (this.podName() != null && authenticated) {
        this.podOwner.set(this.kc.idTokenParsed?.['preferred_username'] || '');
        this.saveSessionToLocalStorage();
      }
    };
  }

  private saveSessionToLocalStorage() {
    const session: Session = {
      podName: this.podName()!,
      podOwner: this.podOwner()!,
    };
    localStorage.setItem(SESSION_KEY, JSON.stringify(session));
  }

  private getSessionFromLocalStorage(): Session | null {
    try {
      const txt = localStorage.getItem(SESSION_KEY);
      if (txt != null) {
        const session = JSON.parse(txt);
        return session;
      } else {
        return null;
      }
    } catch {
      throw new Error(`Error parsing session from localStorage`);
    }
  }

  private destroySessionInLocalStorage() {
    localStorage.removeItem(SESSION_KEY);
  }

  public destroySession() {
    this.podName.set(null);
    this.podOwner.set(null);
    this.destroySessionInLocalStorage();
  }

  /**
   * Login to keycloak, for the specified pod.
   * @param podName
   */
  async doLogin(podName: string): Promise<void> {
    // destroy session of previous pod, if any
    this.destroySession();
    this.kc.clearToken();
    // Add podname to the redirect URI, so that it can be picked up after login and set in the session
    const searchParams = new URLSearchParams();
    searchParams.append('pod', podName);
    const fromLogin = window.location.pathname.endsWith('/login');
    const fromForceSession = /\/force-session\/[^/]+$/.test(
      window.location.pathname,
    );
    const redirectUri =
      fromLogin || fromForceSession
        ? window.location.origin + '/_ui?' + searchParams.toString()
        : window.location.href;
    this.kc.login({
      redirectUri,
      prompt: 'login',
    });
  }

  logout(clientSideOnly = false): Promise<void> {
    this.destroySession();
    if (!clientSideOnly) {
      return this.kc.logout({
        redirectUri: window.location.origin + '/_ui/',
      });
    } else {
      this.kc.clearToken();
      return Promise.resolve();
    }
  }

  logoutAndRedirectToLogin(podName: string): Promise<void> {
    this.destroySession();
    return this.kc.logout({
      redirectUri: window.location.origin + '/_ui/login?selectedPod=' + podName,
    });
  }
}
