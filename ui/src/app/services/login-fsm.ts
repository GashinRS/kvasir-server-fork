import { inject, Injectable } from '@angular/core';
import Keycloak from 'keycloak-js';
import { LoginSessionService, LoginState } from './login-session.service';
import { SessionService } from './session.service';

@Injectable({
  providedIn: 'root',
})
export class LoginFSM {
  private kc = inject(Keycloak, { optional: true });
  private session = inject(SessionService);
  private loginSessionService = inject(LoginSessionService);

  constructor() {
    this.loginSessionService.restoreLoginState();
    this.executeCurrentState();
  }

  private async executeCurrentState(
    params?: Record<string, any>,
  ): Promise<void> {
    switch (this.loginSessionService.getCurrentLoginState()) {
      default:
      case LoginState.NO_AUTH:
        break;
      case LoginState.PREPARE_LOGIN:
        const podName = params!['podName']!;
        const redirectToRoot = params!['redirectToRoot'];
        const force = params!['force'];
        if (podName == null) {
          throw new Error('podName argument was mising!');
        }
        if (redirectToRoot == null) {
          throw new Error('redirectToRoot argument was mising!');
        }
        if (force == null) {
          throw new Error('force argument was mising!');
        }
        await this.prepareLogin(podName, redirectToRoot, force);
        break;
      case LoginState.KEYCLOAK_LOGIN:
        await this.triggerKeycloakLogin();
        break;
      case LoginState.WAITING_FOR_CALLBACK:
        this.waitingForCallback();
        break;
      case LoginState.AUTH:
        await this.tryReAuth();
        break;
    }
  }

  private async prepareLogin(
    podName: string,
    redirectToRoot: boolean,
    force: boolean,
  ) {
    try {
      // Create new session
      await this.loginSessionService.newLoginSession(
        podName,
        redirectToRoot,
        force,
      );
      // Move state
      this.loginSessionService.setLoginState(LoginState.KEYCLOAK_LOGIN);
      // Reload in order to execute the next state function
      window.location.reload();
    } catch (err: any) {
      console.error(err);
    }
  }

  private async triggerKeycloakLogin(): Promise<void> {
    if (this.kc) {
      // Generate verifier and persist
      this.loginSessionService.generateVerifier();

      // advance state
      this.loginSessionService.setLoginState(LoginState.WAITING_FOR_CALLBACK);

      // Redirection
      const loginSession = this.loginSessionService.getCurrentLoginSession()!;
      const redirectToRoot = loginSession.redirectToRoot ?? false;
      const prompt = this.loginSessionService.popForceFlag()
        ? 'login'
        : undefined;
      const redirectUri = window.location.pathname.endsWith('/login')
        ? window.location.origin + '/_ui'
        : window.location.href;

      return this.kc.login({
        redirectUri: redirectToRoot
          ? window.location.origin + '/_ui'
          : redirectUri,
        prompt,
      });
    } else {
      console.error(
        'Keycloak should have been initialised and loaded after page reload!',
      );
    }
  }

  private waitingForCallback(): void {
    const verifier = new URL(window.location.href).searchParams.get('v');
    if (verifier != null) {
      this.loginSessionService.validateSession(verifier);
    }

    // Session activation
    this.session.activateSession(
      this.loginSessionService.getCurrentLoginSession()?.podName ?? null,
    );

    // Set the login state
    this.loginSessionService.setLoginState(LoginState.AUTH);
  }

  private async tryReAuth() {
    const isForceSession = /^.*\/force-session\/\w+/.test(
      window.location.pathname,
    );
    // If not executing no the force-session page
    if (!isForceSession) {
      if (this.kc) {
        await this.triggerKeycloakLogin();
      } else {
        this.logout();
      }
    }
  }

  /**
   * Kick off the login flow.
   * @param podName Pod to log in to
   * @param redirectToRoot Shoul d redirect to root page afterwars. Default is window.location.href
   * @param force If true it will always prompt a login
   */
  public async login(
    podName: string,
    redirectToRoot: boolean = false,
    force: boolean = false,
  ): Promise<void> {
    this.loginSessionService.setLoginState(LoginState.PREPARE_LOGIN);
    await this.executeCurrentState({ podName, redirectToRoot, force });
  }

  public async logout(redirectUri?: string) {
    // Clearing login session info
    this.loginSessionService.clearCurrentLoginSession();
    // Session deactivation
    this.session.deactivateSession();
    // Clear keycloak cookies
    this.kc?.clearToken();
    // Redirect
    window.location.href = redirectUri ?? window.location.origin + '/_ui';
  }
}
