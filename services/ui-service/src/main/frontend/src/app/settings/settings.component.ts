import {
  Component,
  effect,
  inject,
  signal,
  WritableSignal,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import {
  FormBuilder,
  FormControl,
  FormGroup,
  FormsModule,
  ReactiveFormsModule,
} from '@angular/forms';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzCodeEditorModule } from 'ng-zorro-antd/code-editor';
import { NzFlexModule } from 'ng-zorro-antd/flex';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzGridModule } from 'ng-zorro-antd/grid';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzInputNumberModule } from 'ng-zorro-antd/input-number';
import { NzLayoutModule } from 'ng-zorro-antd/layout';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzModalService } from 'ng-zorro-antd/modal';
import { NzPageHeaderModule } from 'ng-zorro-antd/page-header';
import { NzRadioModule } from 'ng-zorro-antd/radio';
import { NzSpaceModule } from 'ng-zorro-antd/space';
import { NzSwitchModule } from 'ng-zorro-antd/switch';
import { NzTabsModule } from 'ng-zorro-antd/tabs';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import { NzTypographyModule } from 'ng-zorro-antd/typography';
import { DefaultStateLockComponent } from '../components/default-state-lock/default-state-lock.component';
import { HelpComponent } from '../components/help/help.component';
import { JsonPreviewComponent } from '../modals/json-preview/json-preview.component';
import { ConfigService } from '../services/config.service';
import { DevSettingsService } from '../services/dev-settings.service';
import { KvasirService } from '../services/kvasir.service';
import { SessionService } from '../services/session.service';
import {
  ApiKeySendVia,
  HttpEndpointPolicyEnforcerConfig,
  PodConfiguration,
  PodDetails,
  OIDCConfig as UMAConfig,
} from '../types';
import { REDACTED_CREDENTIALS } from '../util/constants';

// Flat PodConfiguration form
interface PodSettingsForm {
  defaultContext: FormControl<string>;
  autoIngestRDF: FormControl<boolean>;
  enableSolidWebId: FormControl<boolean>;
  requireDpop: FormControl<boolean>;
  skipDpopAthCheck: FormControl<boolean>;
  enableOidc: FormControl<boolean>;
  oidcServerUrl: FormControl<string>;
  oidcPrincipalExtractor: FormControl<string | null>;
  oidcExtractorConfig: FormControl<string>;
  oidcAllowedSkew: FormControl<number>;
  enableUma: FormControl<boolean>;
  umaServerUrl: FormControl<string>;
  umaClientId: FormControl<string | null>;
  umaClientSecret: FormControl<string | null>;
  umaPrincipalExtractor: FormControl<string | null>;
  umaExtractorConfig: FormControl<string>;
  umaAllowedSkew: FormControl<number>;
  enableHttpPep: FormControl<boolean>;
  httpPepUrl: FormControl<string>;
  enableHttpPepBasicAuth: FormControl<boolean>;
  httpPepBasicAuthUsername: FormControl<string | null>;
  httpPepBasicAuthPassword: FormControl<string | null>;
  enableHttpPepApiKey: FormControl<boolean>;
  httpPepApiKeySendVia: FormControl<ApiKeySendVia>;
  httpPepApiKeyName: FormControl<string | null>;
  httpPepApiKeyValue: FormControl<string | null>;
}

interface LockStates {
  defaultContext: FormControl<boolean>;
  autoIngestRDF: FormControl<boolean>;
  enableSolidWebId: FormControl<boolean>;
  requireDpop: FormControl<boolean>;
  skipDpopAthCheck: FormControl<boolean>;
  enableOidc: FormControl<boolean>;
  enableUma: FormControl<boolean>;
  enableHttpPep: FormControl<boolean>;
}

interface SettingsForm {
  lockStates: FormGroup<LockStates>;
  podSettings: FormGroup<PodSettingsForm>;
}

@Component({
  selector: 'app-settings',
  imports: [
    FormsModule,
    NzFormModule,
    NzIconModule,
    NzInputModule,
    NzInputNumberModule,
    NzSpaceModule,
    NzButtonModule,
    NzTypographyModule,
    NzGridModule,
    NzSwitchModule,
    NzRadioModule,
    NzFlexModule,
    NzPageHeaderModule,
    NzTooltipModule,
    NzLayoutModule,
    NzTabsModule,
    NzSpaceModule,
    HelpComponent,
    ReactiveFormsModule,
    NzCodeEditorModule,
    DefaultStateLockComponent,
  ],
  templateUrl: './settings.component.html',
  styleUrl: './settings.component.less',
})
export class SettingsComponent {
  // DI
  private kvasir = inject(KvasirService);
  private config = inject(ConfigService);
  private session = inject(SessionService);
  private notify = inject(NzMessageService);
  private modal = inject(NzModalService);
  settings = inject(DevSettingsService);

  oidcDisabled = signal(true);
  umaDisabled = signal(true);
  httpPepApiKeyDisabled = signal(true);
  httpPepBasicAuthDisabled = signal(true);
  clientIdSaved = signal(false);
  clientSecretSaved = signal(false);

  defaultContextError = signal<string | undefined>(undefined);
  oidcExtractorConfigError = signal<string | undefined>(undefined);
  umaExtractorConfigError = signal<string | undefined>(undefined);
  settingsForm?: FormGroup<SettingsForm> = undefined;

  private podResource = rxResource({
    stream: (params) => this.kvasir.getPod(),
  });

  platformConfig = rxResource({
    stream: (params) => this.kvasir.getPlatformConfig(),
  });

  constructor(fb: FormBuilder) {
    this.setupForm(fb);

    // Init form on each podResource reload
    effect(() => {
      if (this.podResource.hasValue() && this.platformConfig.hasValue()) {
        const pod = this.podResource.value();
        if (pod != null && pod != undefined) {
          this.initForm(pod);
        }
      }
    });
    // REDACTED CREDENTIALS effects
    effect(() => {
      this.podResource.value();
      if (this.clientIdSaved()) {
        this.podSettingsForm.controls.umaClientId.disable();
      } else {
        this.podSettingsForm.controls.umaClientId.enable();
      }
    });
    effect(() => {
      this.podResource.value();
      if (this.clientSecretSaved()) {
        this.podSettingsForm.controls.umaClientSecret.disable();
      } else {
        this.podSettingsForm.controls.umaClientSecret.enable();
      }
    });
  }

  isSaveDisabled(): boolean {
    return (
      this.settingsForm?.pristine ||
      this.settingsForm?.invalid ||
      !this.JSONvalidate(this.podSettingsForm.controls.defaultContext.value!) ||
      this.isHttpPepSectionValid() === false ||
      this.isOidcSectionValid() === false ||
      this.isUmaSectionValid() === false
    );
  }

  get rtDefaultConfig() {
    return JSON.stringify(
      this.platformConfig.value()?.['default-context'] || {},
      null,
      4,
    );
  }

  private jsonValidate(
    value: string,
    errorSignal: WritableSignal<string | undefined>,
  ) {
    if (this.JSONvalidate(value)) {
      errorSignal.set(undefined);
    } else {
      errorSignal.set('Invalid JSON syntax!');
    }
  }

  private saveOidc() {
    const controls = this.podSettingsForm.controls;
    let podConfig: Partial<PodConfiguration> = {};
    let oidc: UMAConfig | null = null;
    if (!controls.enableOidc.value) {
      oidc = null;
    } else {
      let configValue: string | undefined =
        controls.oidcExtractorConfig.value?.trim();
      if (configValue == null || configValue.length == 0) {
        configValue = undefined;
      }
      oidc = {
        'server-url': controls.oidcServerUrl.value ?? '',
        'principal-extractor':
          controls.oidcPrincipalExtractor.value?.trim() != null
            ? {
                'class-name':
                  controls.oidcPrincipalExtractor.value?.trim() ?? undefined,
                config:
                  configValue == null || configValue.length == 0
                    ? {}
                    : JSON.parse(configValue),
              }
            : undefined,
        'jwt-allowed-clock-skew-seconds': controls.oidcAllowedSkew.value ?? 30,
      };
    }

    return oidc;
  }

  private saveUma() {
    const controls = this.podSettingsForm.controls;
    let podConfig: Partial<PodConfiguration> = {};
    let uma: UMAConfig | null = null;
    if (!controls.enableUma.value) {
      uma = null;
    } else {
      let configValue: string | undefined =
        controls.umaExtractorConfig.value?.trim();
      if (configValue == null || configValue.length == 0) {
        configValue = undefined;
      }
      uma = {
        'server-url': controls.umaServerUrl.value ?? '',
        'client-id': controls.umaClientId.value?.trim() ?? undefined,
        'client-secret': controls.umaClientSecret.value?.trim() ?? undefined,
        'principal-extractor':
          controls.umaPrincipalExtractor.value?.trim() != null
            ? {
                'class-name':
                  controls.umaPrincipalExtractor.value?.trim() ?? undefined,
                config:
                  configValue == null || configValue.length == 0
                    ? {}
                    : JSON.parse(configValue),
              }
            : undefined,
        'jwt-allowed-clock-skew-seconds': controls.umaAllowedSkew.value ?? 30,
      };
      if (this.clientSecretSaved()) {
        delete uma['client-secret'];
      }
      if (this.clientIdSaved()) {
        delete uma['client-id'];
      }
    }
    return uma;
  }

  private isHttpPepSectionValid() {
    const controls = this.podSettingsForm.controls;
    const stateLocked =
      this.settingsForm!.controls.lockStates.controls.enableHttpPep.value;
    return !stateLocked &&
      controls.enableHttpPep.value &&
      controls.httpPepUrl.value.length == 0
      ? false
      : true;
  }

  private isOidcSectionValid() {
    const controls = this.podSettingsForm.controls;
    const stateLocked =
      this.settingsForm!.controls.lockStates.controls.enableOidc.value;
    return !stateLocked &&
      controls.enableOidc.value &&
      controls.oidcServerUrl.value.length == 0
      ? false
      : true;
  }

  private isUmaSectionValid() {
    const controls = this.podSettingsForm.controls;
    const stateLocked =
      this.settingsForm!.controls.lockStates.controls.enableUma.value;
    return !stateLocked &&
      controls.enableUma.value &&
      controls.umaServerUrl.value.length == 0
      ? false
      : true;
  }

  private saveHttpPep() {
    const controls = this.podSettingsForm.controls;
    let podConfig: Partial<PodConfiguration> = {};
    let httpPep: HttpEndpointPolicyEnforcerConfig | null = null;
    if (!controls.enableHttpPep.value) {
      httpPep = null;
    } else {
      const apiKeyEnabled = controls.enableHttpPepApiKey.value;
      const basicAuthEnabled = controls.enableHttpPepBasicAuth.value;

      httpPep = {
        url: controls.httpPepUrl.value ?? null,
        'basic-auth': basicAuthEnabled
          ? {
              username: controls.httpPepBasicAuthUsername.value ?? '',
              password: controls.httpPepBasicAuthPassword.value ?? '',
            }
          : null,
        'api-key': apiKeyEnabled
          ? {
              'send-via': controls.httpPepApiKeySendVia.value,
              'key-name': controls.httpPepApiKeyName.value ?? '',
              'key-value': controls.httpPepApiKeyValue.value ?? '',
            }
          : null,
      };
    }

    return httpPep;
  }

  private JSONvalidate(input: string) {
    try {
      JSON.parse(input);
      return true;
    } catch {
      return false;
    }
  }

  get sentRadioGroup(): ApiKeySendVia {
    return this.podSettingsForm.controls.httpPepApiKeySendVia!.value;
  }

  private setupForm(fb: FormBuilder): void {
    // Init form
    this.settingsForm = fb.group<SettingsForm>({
      podSettings: fb.group<PodSettingsForm>({
        defaultContext: fb.nonNullable.control('{}'),
        autoIngestRDF: fb.nonNullable.control(false),
        enableSolidWebId: fb.nonNullable.control(false),
        requireDpop: fb.nonNullable.control(false),
        skipDpopAthCheck: fb.nonNullable.control(false),
        enableOidc: fb.nonNullable.control(false),

        oidcServerUrl: fb.nonNullable.control({ value: '', disabled: true }),
        oidcPrincipalExtractor: fb.control({ value: null, disabled: true }),
        oidcExtractorConfig: fb.nonNullable.control({
          value: '{}',
          disabled: true,
        }),
        oidcAllowedSkew: fb.nonNullable.control({ value: 30, disabled: true }),

        enableUma: fb.nonNullable.control(false),
        umaServerUrl: fb.nonNullable.control({ value: '', disabled: true }),
        umaClientId: fb.control({ value: null, disabled: true }),
        umaClientSecret: fb.control({ value: null, disabled: true }),
        umaPrincipalExtractor: fb.control({ value: null, disabled: true }),
        umaExtractorConfig: fb.nonNullable.control({
          value: '{}',
          disabled: true,
        }),
        umaAllowedSkew: fb.nonNullable.control({ value: 30, disabled: true }),

        enableHttpPep: fb.nonNullable.control(false),
        httpPepUrl: fb.nonNullable.control({ value: '', disabled: true }),

        enableHttpPepBasicAuth: fb.nonNullable.control({
          value: false,
          disabled: true,
        }),
        httpPepBasicAuthUsername: fb.control({ value: null, disabled: true }),
        httpPepBasicAuthPassword: fb.control({ value: null, disabled: true }),

        enableHttpPepApiKey: fb.nonNullable.control({
          value: false,
          disabled: true,
        }),
        httpPepApiKeyName: fb.control({ value: null, disabled: true }),
        httpPepApiKeyValue: fb.control({ value: null, disabled: true }),
        httpPepApiKeySendVia: fb.nonNullable.control({
          value: ApiKeySendVia.HEADER,
          disabled: true,
        }),
      }),
      lockStates: fb.group<LockStates>({
        defaultContext: fb.nonNullable.control(true),
        autoIngestRDF: fb.nonNullable.control(true),
        enableSolidWebId: fb.nonNullable.control(true),
        requireDpop: fb.nonNullable.control(true),
        skipDpopAthCheck: fb.nonNullable.control(true),
        enableOidc: fb.nonNullable.control(true),
        enableUma: fb.nonNullable.control(true),
        enableHttpPep: fb.nonNullable.control(true),
      }),
    });

    // Setup DISABLE triggers
    const controls = this.podSettingsForm.controls;
    controls.enableOidc.valueChanges.subscribe((enabled) =>
      this.setEnabledStateOidc(enabled),
    );
    controls.enableUma.valueChanges.subscribe((enabled) =>
      this.setEnabledStateUma(enabled),
    );
    controls.enableHttpPep.valueChanges.subscribe((enabled) =>
      this.setEnabledStateHttpPep(enabled),
    );
    controls.enableHttpPepBasicAuth.valueChanges.subscribe((enabled) =>
      this.setEnabledStateHttpPepBasicAuth(enabled),
    );
    controls.enableHttpPepApiKey.valueChanges.subscribe((enabled) =>
      this.setEnabledStateHttpPepApiKey(enabled),
    );

    // Setup ON CHANGE EFFECTS
    controls.defaultContext.valueChanges.subscribe((val) =>
      this.jsonValidate(val!, this.defaultContextError),
    );
    controls.oidcExtractorConfig.valueChanges.subscribe((val) =>
      this.jsonValidate(val!, this.oidcExtractorConfigError),
    );
    controls.umaExtractorConfig.valueChanges.subscribe((val) =>
      this.jsonValidate(val!, this.umaExtractorConfigError),
    );

    // On Change effects
    controls.requireDpop.valueChanges.subscribe((val) =>
      this.confirmToggle(val, controls.requireDpop),
    );
  }

  private initForm(pod: PodDetails): void {
    const cfg = pod['kss:configuration'];
    const defaults = this.platformConfig.value()!;

    // Prepare values
    const defaultContext =
      JSON.stringify(
        cfg['default-context'] ?? defaults['default-context'],
        null,
        4,
      ) ?? '{}';
    const autoIngestRDF = cfg['auto-ingest-rdf'] ?? defaults['auto-ingest-rdf'];
    const enableSolidWebId =
      cfg.auth?.['enable-solid-web-id'] ??
      defaults.auth?.['enable-solid-web-id'];
    const requireDpop =
      cfg.auth?.['require-dpop'] ?? defaults.auth?.['require-dpop'];
    const skipDpopAthCheck =
      cfg.auth?.['skip-dpop-ath-check'] ??
      defaults.auth?.['skip-dpop-ath-check'];
    const enableOidc = (cfg.auth?.oidc ?? defaults.auth?.oidc) ? true : false;
    const oidcServerUrl = enableOidc
      ? (cfg.auth?.oidc?.['server-url'] ?? defaults.auth?.oidc?.['server-url'])
      : '';
    const oidcPrincipalExtractor = enableOidc
      ? (cfg.auth?.oidc?.['principal-extractor']?.['class-name'] ??
        defaults.auth?.oidc?.['principal-extractor']?.['class-name'])
      : null;
    const oidcExtractorConfig = enableOidc
      ? JSON.stringify(
          cfg.auth?.oidc?.['principal-extractor']?.config ??
            defaults.auth?.oidc?.['principal-extractor']?.config,
          null,
          4,
        )
      : '{}';
    const oidcAllowedSkew = enableOidc
      ? (cfg.auth?.oidc?.['jwt-allowed-clock-skew-seconds'] ??
        defaults.auth?.oidc?.['jwt-allowed-clock-skew-seconds'])
      : 30;
    const enableUma = (cfg.auth?.uma ?? defaults.auth?.uma) ? true : false;
    const umaServerUrl = enableUma
      ? (cfg.auth?.uma?.['server-url'] ?? defaults.auth?.uma?.['server-url'])
      : '';
    const umaClientId = enableUma
      ? (cfg.auth?.uma?.['client-id'] ?? defaults.auth?.uma?.['client-id'])
      : '';
    const umaClientSecret = enableUma
      ? (cfg.auth?.uma?.['client-secret'] ??
        defaults.auth?.uma?.['client-secret'])
      : '';
    const umaPrincipalExtractor = enableUma
      ? (cfg.auth?.uma?.['principal-extractor']?.['class-name'] ??
        defaults.auth?.uma?.['principal-extractor']?.['class-name'])
      : null;
    const umaExtractorConfig = enableUma
      ? JSON.stringify(
          cfg.auth?.uma?.['principal-extractor']?.config ??
            defaults.auth?.uma?.['principal-extractor']?.config,
          null,
          4,
        )
      : '{}';
    const umaAllowedSkew = enableUma
      ? (cfg.auth?.uma?.['jwt-allowed-clock-skew-seconds'] ??
        defaults.auth?.uma?.['jwt-allowed-clock-skew-seconds'])
      : 30;
    const enableHttpPep =
      (cfg.auth?.['http-endpoint-policy-enforcer'] ??
      defaults.auth?.['http-endpoint-policy-enforcer'])
        ? true
        : false;
    const httpPepUrl = enableHttpPep
      ? (cfg.auth?.['http-endpoint-policy-enforcer']?.url ??
        defaults.auth?.['http-endpoint-policy-enforcer']?.url)
      : '';
    const enableHttpPepBasicAuth =
      enableHttpPep &&
      (cfg.auth?.['http-endpoint-policy-enforcer']?.['basic-auth'] ??
        defaults.auth?.['http-endpoint-policy-enforcer']?.['basic-auth'])
        ? true
        : false;
    const httpPepBasicAuthUsername = enableHttpPepBasicAuth
      ? (cfg.auth['http-endpoint-policy-enforcer']?.['basic-auth']?.username ??
        defaults.auth?.['http-endpoint-policy-enforcer']?.['basic-auth']
          ?.username)
      : null;
    const httpPepBasicAuthPassword = enableHttpPepBasicAuth
      ? (cfg.auth?.['http-endpoint-policy-enforcer']?.['basic-auth']
          ?.password ??
        defaults.auth?.['http-endpoint-policy-enforcer']?.['basic-auth']
          ?.password)
      : null;
    const enableHttpPepApiKey =
      enableHttpPep &&
      (cfg.auth?.['http-endpoint-policy-enforcer']?.['api-key'] ??
        defaults.auth?.['http-endpoint-policy-enforcer']?.['api-key'])
        ? true
        : false;
    const httpPepApiKeySendVia = enableHttpPepApiKey
      ? (cfg.auth?.['http-endpoint-policy-enforcer']?.['api-key']?.[
          'send-via'
        ] ??
        defaults.auth?.['http-endpoint-policy-enforcer']?.['api-key']?.[
          'send-via'
        ])
      : ApiKeySendVia.HEADER;
    const httpPepApiKeyName = enableHttpPepApiKey
      ? (cfg.auth?.['http-endpoint-policy-enforcer']?.['api-key']?.[
          'key-name'
        ] ??
        defaults.auth?.['http-endpoint-policy-enforcer']?.['api-key']?.[
          'key-name'
        ])
      : null;
    const httpPepApiKeyValue = enableHttpPepApiKey
      ? (cfg.auth?.['http-endpoint-policy-enforcer']?.['api-key']?.[
          'key-value'
        ] ??
        defaults.auth?.['http-endpoint-policy-enforcer']?.['api-key']?.[
          'key-value'
        ])
      : null;

    this.podSettingsForm.reset(
      {
        defaultContext,
        autoIngestRDF,
        enableSolidWebId,
        requireDpop,
        skipDpopAthCheck,
        enableOidc,
        oidcServerUrl,
        oidcPrincipalExtractor,
        oidcExtractorConfig,
        oidcAllowedSkew,
        enableUma,
        umaServerUrl,
        umaClientId,
        umaClientSecret,
        umaPrincipalExtractor,
        umaExtractorConfig,
        umaAllowedSkew,
        enableHttpPep,
        httpPepUrl,
        enableHttpPepBasicAuth,
        httpPepBasicAuthUsername,
        httpPepBasicAuthPassword,
        enableHttpPepApiKey,
        httpPepApiKeySendVia,
        httpPepApiKeyName,
        httpPepApiKeyValue,
      },
      { emitEvent: false },
    );
    this.settingsForm!.controls.lockStates.reset(
      {
        defaultContext: 'default-context' in cfg ? false : true,
        autoIngestRDF: 'auto-ingest-rdf' in cfg ? false : true,
        enableSolidWebId:
          cfg.auth && 'enable-solid-web-id' in cfg.auth ? false : true,
        requireDpop: cfg.auth && 'require-dpop' in cfg.auth ? false : true,
        skipDpopAthCheck:
          cfg.auth && 'skip-dpop-ath-check' in cfg.auth ? false : true,
        enableOidc: cfg.auth && 'oidc' in cfg.auth ? false : true,
        enableUma: cfg.auth && 'uma' in cfg.auth ? false : true,
        enableHttpPep:
          cfg.auth && 'http-endpoint-policy-enforcer' in cfg.auth
            ? false
            : true,
      },
      { emitEvent: false },
    );

    // Correct disabled states
    this.setEnabledStateOidc(enableOidc);
    this.setEnabledStateUma(enableUma);
    this.setEnabledStateHttpPep(enableHttpPep);
    this.setEnabledStateHttpPepBasicAuth(enableHttpPepBasicAuth);
    this.setEnabledStateHttpPepApiKey(enableHttpPepApiKey);

    // If credentials are redacted, keep the fields disabled
    const controls = this.podSettingsForm.controls;
    if (REDACTED_CREDENTIALS == controls.umaClientId.value) {
      this.clientIdSaved.set(true);
    }
    if (REDACTED_CREDENTIALS == controls.umaClientSecret.value) {
      this.clientSecretSaved.set(true);
    }
  }

  private setEnabledStateOidc(enabled: boolean) {
    const controls = this.podSettingsForm.controls;
    // OIDC
    setEnabledState(
      enabled,
      controls.oidcServerUrl,
      controls.oidcPrincipalExtractor,
      controls.oidcExtractorConfig,
      controls.oidcAllowedSkew,
    );
    this.oidcDisabled.set(!enabled);
  }

  private setEnabledStateUma(enabled: boolean) {
    const controls = this.podSettingsForm.controls;
    // OIDC
    setEnabledState(
      enabled,
      controls.umaServerUrl,
      controls.umaClientId,
      controls.umaClientSecret,
      controls.umaPrincipalExtractor,
      controls.umaExtractorConfig,
      controls.umaAllowedSkew,
    );
    this.umaDisabled.set(!enabled);
  }

  private setEnabledStateHttpPep(enabled: boolean) {
    const controls = this.podSettingsForm.controls;
    setEnabledState(
      enabled,
      controls.httpPepUrl,
      controls.enableHttpPepApiKey,
      controls.enableHttpPepBasicAuth,
      controls.httpPepBasicAuthUsername,
      controls.httpPepBasicAuthPassword,
      controls.httpPepApiKeySendVia,
      controls.httpPepApiKeyName,
      controls.httpPepApiKeyValue,
    );
  }

  private setEnabledStateHttpPepApiKey(enabled: boolean) {
    const controls = this.podSettingsForm.controls;
    setEnabledState(
      enabled,
      controls.httpPepApiKeySendVia,
      controls.httpPepApiKeyName,
      controls.httpPepApiKeyValue,
    );
  }

  private setEnabledStateHttpPepBasicAuth(enabled: boolean) {
    const controls = this.podSettingsForm.controls;
    setEnabledState(
      enabled,
      controls.httpPepBasicAuthUsername,
      controls.httpPepBasicAuthPassword,
    );
  }

  private confirmToggle = (
    val: boolean,
    control: FormControl<boolean>,
    onOkFn?: () => void,
  ) => {
    const reset = () => control.setValue(!val, { emitEvent: false });
    this.modal.confirm({
      nzTitle: 'Warning: advanced toggle!',
      nzContent:
        'Tweaking this setting might break the Kvasir UI!<br>Are you sure you want to do this?',
      nzIconType: 'exclamation-circle',
      nzOkDanger: true,
      nzOkText: 'Continue',
      nzOnOk: onOkFn ?? (() => {}),
      nzOnCancel: reset,
      nzCentered: true,
    });
  };

  private confirm = (msg: string, onOkFn?: () => void) => {
    this.modal.confirm({
      nzTitle: 'Warning!',
      nzContent: `${msg}<br>Are you sure you want to do this?`,
      nzIconType: 'exclamation-circle',
      nzOkDanger: true,
      nzOkText: 'Continue',
      nzOnOk: onOkFn ?? (() => {}),
      nzCentered: true,
    });
  };

  private get podSettingsForm(): FormGroup<PodSettingsForm> {
    return this.settingsForm!.controls.podSettings;
  }

  private parseToPodSettingsBody(): Partial<PodConfiguration> {
    const controls = this.podSettingsForm.controls;
    const body: Partial<PodConfiguration> = {};

    body['default-context'] = JSON.parse(controls.defaultContext.value!);
    body['auto-ingest-rdf'] = controls.autoIngestRDF.value!;
    body.auth = {
      'enable-solid-web-id': controls.enableSolidWebId.value!,
      'require-dpop': controls.requireDpop.value!,
      'skip-dpop-ath-check': controls.skipDpopAthCheck.value!,
      oidc: this.saveOidc(),
      uma: this.saveUma(),
      'http-endpoint-policy-enforcer': this.saveHttpPep(),
    };
    return body;
  }

  saveAll() {
    if (!this.isSaveDisabled()) {
      const body = this.parseToPodSettingsBody();
      const locks = this.settingsForm!.value.lockStates!;
      if (locks.defaultContext) {
        delete body['default-context'];
      }
      if (locks.autoIngestRDF) {
        delete body['auto-ingest-rdf'];
      }
      const authBody = body.auth as Partial<PodConfiguration['auth']>;
      if (locks.enableSolidWebId && body.auth) {
        delete authBody['enable-solid-web-id'];
      }
      if (locks.requireDpop) {
        delete authBody['require-dpop'];
      }
      if (locks.skipDpopAthCheck) {
        delete authBody['skip-dpop-ath-check'];
      }
      if (locks.enableOidc) {
        delete authBody.oidc;
      }
      if (locks.enableUma) {
        delete authBody.uma;
      }
      if (locks.enableHttpPep) {
        delete authBody['http-endpoint-policy-enforcer'];
      }
      if (body.auth && Object.keys(body.auth).length == 0) {
        delete body.auth;
      }

      // Save remaining settings to podSettings
      this.kvasir.updatePod(body as PodConfiguration).subscribe({
        next: () => {
          this.podResource.reload();
          this.notify.success('All settings saved');
        },
      });
    }
  }

  resetAll() {
    this.confirm('This will discard ALL your unsaved changes!', () => {
      this.podResource.reload();
      this.notify.info('Custom settings reset to last saved state');
    });
  }

  resetToPlatformDefaults() {
    this.confirm(
      'This will clear ALL your custom settings and restore the platform default configuration!',
      () => {
        if (this.platformConfig.hasValue()) {
          this.kvasir.updatePod({} as PodConfiguration).subscribe({
            next: () => {
              this.podResource.reload();
              this.notify.success(
                'Custom settings cleared, platform defaults restored',
              );
            },
          });
        }
      },
    );
  }

  resetClientId() {
    this.clientIdSaved.set(false);
    this.podSettingsForm.controls.umaClientId.setValue(null);
    this.podSettingsForm.controls.umaClientId.markAsDirty();
  }

  resetClientSecret() {
    this.clientSecretSaved.set(false);
    this.podSettingsForm.controls.umaClientSecret.setValue(null);
    this.podSettingsForm.controls.umaClientSecret.markAsDirty();
  }

  tryRegister() {
    const serverUrl = this.podSettingsForm.controls.umaServerUrl.value;
    if (serverUrl == null || serverUrl.length == 0) {
      this.notify.error('UMA Server URL is required for dynamic registration');
      return;
    }
    const podId = `${this.config.host}/${this.session.podName()}`;
    fetch(`${serverUrl}/.well-known/uma2-configuration`)
      .then((res) => res.json())
      .then((config) => config['registration_endpoint'])
      .then((regEndpoint: string) => {
        return fetch(regEndpoint, {
          method: 'POST',
          headers: {
            Authorization: `WebID ${encodeURIComponent(podId)}`,
            'Content-Type': 'application/json',
          },
          body: JSON.stringify({
            client_name: `Kvasir Pod UMA Client (${this.session.podName()})`,
            client_uri: podId,
          }),
        });
      })
      .then((res) => res.json())
      .then(
        (regResponse) => {
          if (regResponse.client_id && regResponse.client_secret) {
            this.podSettingsForm.controls.umaClientId.setValue(
              regResponse.client_id,
            );
            this.podSettingsForm.controls.umaClientSecret.setValue(
              regResponse.client_secret,
            );
            this.podSettingsForm.controls.umaClientId.markAsDirty();
            this.podSettingsForm.controls.umaClientSecret.markAsDirty();
            this.notify.success(
              "UMA client registered successfully, updating form. Don't forget to save the changes!",
            );
          } else {
            regResponse.status == 409
              ? this.notify.warning(
                  'UMA Client was already registered under this URL',
                )
              : this.notify.error('UMA client registration failed');
          }
        },
        (err) => this.notify.error(`UMA client registration failed: ${err}`),
      );
  }

  previewRuntimeConfig() {
    this.kvasir.getPodRuntimeConfig().subscribe({
      next: (config) => {
        const modalRef = this.modal.create<JsonPreviewComponent, string>({
          nzTitle: 'Runtime Pod Config',
          nzContent: JsonPreviewComponent,
          nzData: JSON.stringify(config, null, 4),
          nzWidth: '75%',
          nzFooter: [
            {
              label: 'Close',
              onClick: () => modalRef.destroy(),
            },
          ],
        });
      },
    });
  }
}

function setEnabledState(enabled: boolean, ...controls: FormControl[]): void {
  controls.forEach((control) =>
    enabled ? control.enable() : control.disable(),
  );
}
