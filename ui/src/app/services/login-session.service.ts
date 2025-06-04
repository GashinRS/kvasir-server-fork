import { inject, Injectable } from '@angular/core';
import { KEY_KVASIR_LOGIN_SESSION, KEY_LOGIN_STATE } from '../util/constants';
import { ConfigService } from './config.service';

export interface KvasirLoginSession {
  podName: string;
  keycloakUrl: string;
  sessionStarted: number;
  redirectToRoot: boolean;
  force: boolean;
  verifier?: string;
}

export enum LoginState {
  /** Not authed */
  NO_AUTH,
  /** Setup persist LoginSession object and get ready to reload window (to enable keycloak) */
  PREPARE_LOGIN,
  /** Keycloak is loaded, redirecting to Keycloak Login */
  KEYCLOAK_LOGIN,
  /** Keycloak login is sent, thus now receiving callback */
  WAITING_FOR_CALLBACK,
  /** Authed */
  AUTH,
}

const TTL_LOGIN_SESSION = 120; // seconds

@Injectable({
  providedIn: 'root',
})
export class LoginSessionService {
  private readonly longStorage = localStorage;
  private readonly tempStorgae = sessionStorage;

  private session: KvasirLoginSession | null = null;
  private state: LoginState = LoginState.NO_AUTH;

  // DI
  private config = inject(ConfigService);

  constructor() {
    // Try to restore login state
    this.restoreLoginState();
    // Try to restore login sessions
    this.restoreLoginSession();
  }

  /**
   * Create new KvasirLoginSession and persist it.
   * @param podName
   * @returns
   */
  async newLoginSession(
    podName: string,
    redirectToRoot: boolean = false,
    force: boolean = false,
  ): Promise<void> {
    // Clear any existing login sessions in storage
    this.clearCurrentLoginSession();
    // Fetch details
    try {
      const response = await fetch(`${this.config.host}/${podName}/.profile`);
      const { 'kss:authServerUrl': keycloakUrl } = await response.json();
      this.session = {
        keycloakUrl,
        podName,
        redirectToRoot,
        force,
        sessionStarted: Date.now(),
      };
      // Persist and return
      this.persistLoginSession();
    } catch (err: any) {
      console.error(err);
    }
  }

  /**
   * A new verifier will be generated, added to the session, that session persisted and returned.
   * @param session Session to add the verifier to
   * @returns
   */
  generateVerifier(): void {
    this.session!.verifier = generateRandomString(20);
    this.persistLoginSession();
  }

  popForceFlag(): boolean {
    const force = this.session!.force ?? false;
    this.session!.force = false;
    this.persistLoginSession();
    return force;
  }

  /**
   * Errors if either verifier is wrong, or TTL has expired
   * @param session
   * @param incomingVerifier
   */
  validateSession(incomingVerifier: string): void {
    if (incomingVerifier != this.session!.verifier) {
      throw new Error('Verifier does not match!');
    }
    if (Date.now() - this.session!.sessionStarted > TTL_LOGIN_SESSION) {
      throw new Error('Login session timed out!');
    }
  }

  /**
   * Manually persist the login session.
   */
  persistLoginSession() {
    if (this.session) {
      this.longStorage.setItem(
        KEY_KVASIR_LOGIN_SESSION,
        JSON.stringify(this.session),
      );
    }
  }

  /**
   * Manually persist the login state.
   */
  persistLoginState() {
    if (this.state) {
      this.longStorage.setItem(KEY_LOGIN_STATE, JSON.stringify(this.state));
    }
  }

  /**
   * Restores the login session from persistence.
   * @returns
   */
  restoreLoginSession(): void {
    const rawSession = this.longStorage.getItem(KEY_KVASIR_LOGIN_SESSION);
    if (rawSession == null) {
      return;
    }
    this.session = JSON.parse(rawSession!) as KvasirLoginSession;
  }

  /**
   * Restores the login state from persistence.
   * @returns
   */
  restoreLoginState(): void {
    const rawState = this.longStorage.getItem(KEY_LOGIN_STATE);
    if (rawState == null) {
      return;
    }
    this.state = parseInt(rawState) as LoginState;
  }

  /**
   * Manually remove the current login session from storage.
   */
  clearCurrentLoginSession() {
    this.longStorage.removeItem(KEY_KVASIR_LOGIN_SESSION);
    this.longStorage.removeItem(KEY_LOGIN_STATE);
  }

  /**
   * Returns the current login session. Null of no session is active
   * @returns
   */
  getCurrentLoginSession(): KvasirLoginSession | null {
    return this.session;
  }

  getCurrentLoginState(): LoginState {
    return this.state;
  }

  setLoginState(state: LoginState): void {
    this.state = state;
    this.persistLoginState();
  }
}

function generateRandomString(length: number): string {
  const gen = (len = 10) =>
    Math.random()
      .toString(36)
      .slice(2, len + 2);
  let str = gen(Math.min(length, 10));
  while (str.length < length) {
    str += gen(Math.min(10, length - str.length));
  }
  return str;
}
