import {
  Component,
  effect,
  inject,
  OnInit,
  signal,
  WritableSignal,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
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
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzInputNumberModule } from 'ng-zorro-antd/input-number';
import { NzLayoutModule } from 'ng-zorro-antd/layout';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzPageHeaderModule } from 'ng-zorro-antd/page-header';
import { NzRadioModule } from 'ng-zorro-antd/radio';
import { NzSpaceModule } from 'ng-zorro-antd/space';
import { NzSwitchModule } from 'ng-zorro-antd/switch';
import { NzTabsModule } from 'ng-zorro-antd/tabs';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import { NzTypographyModule } from 'ng-zorro-antd/typography';
import { from, Observable, of, switchMap, throwError } from 'rxjs';
import { HelpComponent } from '../components/help/help.component';
import { DevSettingsService } from '../services/dev-settings.service';
import { KvasirService } from '../services/kvasir.service';
import {
  ApiKeySendVia,
  HttpEndpointPolicyEnforcerConfig,
  PodConfiguration,
  PodDetails,
  OIDCConfig as UMAConfig,
} from '../types';
import { NzModalService } from 'ng-zorro-antd/modal';

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

@Component({
  selector: 'app-settings',
  imports: [
    FormsModule,
    NzFormModule,
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
    HelpComponent,
    ReactiveFormsModule,
    NzCodeEditorModule,
  ],
  templateUrl: './settings.component.html',
  styleUrl: './settings.component.less',
})
export class SettingsComponent implements OnInit {
  // DI
  private kvasir = inject(KvasirService);
  private notify = inject(NzMessageService);
  private modal = inject(NzModalService);
  settings = inject(DevSettingsService);

  oidcDisabled = signal(true);
  umaDisabled = signal(true);
  httpPepApiKeyDisabled = signal(true);
  httpPepBasicAuthDisabled = signal(true);

  defaultContextError = signal<string | undefined>(undefined);
  oidcExtractorConfigError = signal<string | undefined>(undefined);
  umaExtractorConfigError = signal<string | undefined>(undefined);
  settingsForm?: FormGroup<PodSettingsForm> = undefined;

  private originalConfig?: PodConfiguration;
  private podResource = rxResource({
    stream: (params) => this.kvasir.getPod(),
  });

  constructor(fb: FormBuilder) {
    this.setupForm(fb);
    effect(() => {
      if (this.podResource.hasValue()) {
        const pod = this.podResource.value();
        this.initForm(pod);
      }
    });
  }

  ngOnInit(): void {
    this.podResource.reload();
  }

  jsonValidate(value: string, errorSignal: WritableSignal<string | undefined>) {
    if (this.JSONvalidate(value)) {
      errorSignal.set(undefined);
    } else {
      errorSignal.set('Invalid JSON syntax!');
    }
  }

  resetDefaultContext() {
    const defaultContext = JSON.stringify(
      JSON.parse(this.originalConfig!['default-context']!),
      null,
      4,
    );
    this.settingsForm!.controls.defaultContext.setValue(defaultContext);
  }

  saveDefaultContext() {
    const podConfig: Partial<PodConfiguration> = {
      'default-context': JSON.parse(
        this.settingsForm!.controls.defaultContext.value!,
      ),
    };
    this.saveSection(
      podConfig,
      'Default context saved',
      'Failed saving Default context',
    );
  }

  saveOidc() {
    const controls = this.settingsForm!.controls;
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

    const auth = { ...this.originalConfig!.auth, oidc };

    this.saveSection(
      { auth },
      'OIDC settings saved',
      'Failed saving OIDC settings',
    );
  }

  saveUma() {
    const controls = this.settingsForm!.controls;
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
    }

    const auth = { ...this.originalConfig!.auth, uma: uma };

    this.saveSection(
      { auth },
      'UMA settings saved',
      'Failed saving UMA settings',
    );
  }

  isHttpPepSectionValid() {
    const controls = this.settingsForm!.controls;
    return controls.enableHttpPep.value && controls.httpPepUrl.value.length == 0 ? false : true;
  }

  saveHttpPep() {
    const controls = this.settingsForm!.controls;
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

    const auth = {
      ...this.originalConfig!.auth,
      'http-endpoint-policy-enforcer': httpPep,
    };

    this.saveSection(
      { auth },
      'HTTP Endpoint Policy Enforcer settings saved',
      'Failed saving HTTP Endpoint Policy Enforcer settings',
    );
  }

  private saveSection(
    configPart: Partial<PodConfiguration>,
    msg?: string,
    errMsg?: string,
  ) {
    const podConfig: PodConfiguration = {
      ...this.originalConfig!,
      ...configPart,
    };
    this.updatePodSettings(podConfig).subscribe({
      next: (ok) => {
        this.podResource.reload();
        this.notify.success(msg ?? 'Configuration saved');
      },
      error: (err) =>
        this.notify.error(errMsg ?? 'Failed saving configuration'),
    });
  }

  private saveAutoIngestRDF(val: boolean) {
    const podConfig: Partial<PodConfiguration> = {
      'auto-ingest-rdf': val,
    };
    this.saveSection(
      podConfig,
      `Auto-ingest RDF (${val ? 'on' : 'off'}) saved`,
      'Failed saving Auto-ingest RDF',
    );
  }

  private saveEnableSolidWebId(val: boolean) {
    this.saveSection(
        { auth: { 'enable-solid-web-id': val } } as Partial<PodConfiguration>,
        `Solid WebID ${val ? 'enabled' : 'disabled'}`,
        'Failed saving Solid WebID state',
      );
  }

  private saveRequireDpop(val: boolean ) {
      this.saveSection(
        { auth: { 'require-dpop': val } } as Partial<PodConfiguration>,
        `Require DPoP ${val ? 'enabled' : 'disabled'}`,
        'Failed saving Require DPoP state',
      );
  }

  private saveSkipDpopAthCheck(val: boolean) {
      this.saveSection(
        { auth: { 'skip-dpop-ath-check': val } } as Partial<PodConfiguration>,
        `Skip DPoP ath check ${val ? 'enabled' : 'disabled'}`,
        'Failed saving Skip DPoP ath check state',
      );
  }

  private updatePodSettings(podConfig: PodConfiguration): Observable<void> {
    return this.kvasir.updatePod(podConfig);
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
    return this.settingsForm!.controls.httpPepApiKeySendVia!.value;
  }

  private setupForm(fb: FormBuilder): void {
    // Init form
    this.settingsForm = fb.group<PodSettingsForm>({
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
    });

    // Setup DISABLE triggers
    const controls = this.settingsForm.controls;
    controls.enableOidc.valueChanges.subscribe((enabled) => this.setEnabledStateOidc(enabled));
    controls.enableUma.valueChanges.subscribe((enabled) => this.setEnabledStateUma(enabled));
    controls.enableHttpPep.valueChanges.subscribe((enabled) => this.setEnabledStateHttpPep(enabled));
    controls.enableHttpPepBasicAuth.valueChanges.subscribe((enabled) => this.setEnabledStateHttpPepBasicAuth(enabled));
    controls.enableHttpPepApiKey.valueChanges.subscribe((enabled) => this.setEnabledStateHttpPepApiKey(enabled));

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

    // Setup SAVE triggers
    controls.autoIngestRDF.valueChanges.subscribe(val => this.saveAutoIngestRDF(val));
    controls.enableSolidWebId.valueChanges.subscribe((val) =>
      this.saveEnableSolidWebId(val)
    );
    controls.requireDpop.valueChanges.subscribe((val) => this.confirmToggle(
      val,
      this.saveRequireDpop.bind(this, val),
      controls.requireDpop
    ));
    controls.skipDpopAthCheck.valueChanges.subscribe((val) =>
      this.saveSkipDpopAthCheck(val)
    );
  }

  private initForm(pod: PodDetails): void {
    const cfg = pod['kss:configuration'];
    this.originalConfig = cfg;

    // Prepare values
    const defaultContext =
      JSON.stringify(cfg['default-context'], null, 4) ?? '{}';
    const autoIngestRDF = cfg['auto-ingest-rdf'];
    const enableSolidWebId = cfg.auth['enable-solid-web-id'];
    const requireDpop = cfg.auth['require-dpop'];
    const skipDpopAthCheck = cfg.auth['skip-dpop-ath-check'];
    const enableOidc = cfg.auth.oidc ? true : false;
    const oidcServerUrl = enableOidc ? cfg.auth.oidc!['server-url'] : '';
    const oidcPrincipalExtractor = enableOidc
      ? cfg.auth.oidc!['principal-extractor']?.['class-name']
      : null;
    const oidcExtractorConfig = enableOidc
      ? JSON.stringify(cfg.auth.oidc!['principal-extractor']?.config, null, 4)
      : '{}';
    const oidcAllowedSkew = enableOidc
      ? cfg.auth.oidc!['jwt-allowed-clock-skew-seconds']
      : 30;
    const enableUma = cfg.auth.uma ? true : false;
    const umaServerUrl = enableUma ? cfg.auth.uma!['server-url'] : '';
    const umaPrincipalExtractor = enableUma
      ? cfg.auth.uma!['principal-extractor']?.['class-name']
      : null;
    const umaExtractorConfig = enableUma
      ? JSON.stringify(cfg.auth.uma!['principal-extractor']?.config, null, 4)
      : '{}';
    const umaAllowedSkew = enableUma
      ? cfg.auth.uma!['jwt-allowed-clock-skew-seconds']
      : 30;
    const enableHttpPep = cfg.auth['http-endpoint-policy-enforcer']
      ? true
      : false;
    const httpPepUrl = cfg.auth['http-endpoint-policy-enforcer']?.url
    const enableHttpPepBasicAuth = cfg.auth['http-endpoint-policy-enforcer']?.[
      'basic-auth'
    ]
      ? true
      : false;
    const httpPepBasicAuthUsername = enableHttpPepBasicAuth
      ? cfg.auth['http-endpoint-policy-enforcer']?.['basic-auth']?.username
      : null;
    const httpPepBasicAuthPassword = enableHttpPepBasicAuth
      ? cfg.auth['http-endpoint-policy-enforcer']?.['basic-auth']?.password
      : null;
    const enableHttpPepApiKey = cfg.auth['http-endpoint-policy-enforcer']?.[
      'api-key'
    ]
      ? true
      : false;
    const httpPepApiKeySendVia = enableHttpPepApiKey
      ? cfg.auth['http-endpoint-policy-enforcer']?.['api-key']?.['send-via']
      : ApiKeySendVia.HEADER;
    const httpPepApiKeyName = enableHttpPepApiKey
      ? cfg.auth['http-endpoint-policy-enforcer']?.['api-key']?.['key-name']
      : null;
    const httpPepApiKeyValue = enableHttpPepApiKey
      ? cfg.auth['http-endpoint-policy-enforcer']?.['api-key']?.['key-value']
      : null;

    this.settingsForm!.reset(
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

    // Correct disabled states
    this.setEnabledStateOidc(enableOidc);
    this.setEnabledStateUma(enableUma);
    this.setEnabledStateHttpPep(enableHttpPep);
    this.setEnabledStateHttpPepBasicAuth(enableHttpPepBasicAuth)
    this.setEnabledStateHttpPepApiKey(enableHttpPepApiKey);
  }


  private setEnabledStateOidc(enabled: boolean) {
    const controls = this.settingsForm!.controls;
    // OIDC
    setEnabledState(enabled, 
      controls.oidcServerUrl,
      controls.oidcPrincipalExtractor,
      controls.oidcExtractorConfig,
      controls.oidcAllowedSkew,
    );
    this.oidcDisabled.set(!enabled);
  }

  private setEnabledStateUma(enabled: boolean) {
    const controls = this.settingsForm!.controls;
    // OIDC
    setEnabledState(enabled, 
      controls.umaServerUrl,
      controls.umaPrincipalExtractor,
      controls.umaExtractorConfig,
      controls.umaAllowedSkew,
    );
    this.umaDisabled.set(!enabled);
  }

  private setEnabledStateHttpPep(enabled: boolean) {
    const controls = this.settingsForm!.controls;
    setEnabledState(enabled,
       controls.httpPepUrl,
      controls.enableHttpPepApiKey,
      controls.enableHttpPepBasicAuth,
       controls.httpPepBasicAuthUsername,
      controls.httpPepBasicAuthPassword,
      controls.httpPepApiKeySendVia,
      controls.httpPepApiKeyName,
      controls.httpPepApiKeyValue,
    )
  }

  private setEnabledStateHttpPepApiKey(enabled: boolean) {
    const controls = this.settingsForm!.controls;
     setEnabledState(enabled,
      controls.httpPepApiKeySendVia,
      controls.httpPepApiKeyName,
      controls.httpPepApiKeyValue,
    )
  }

  private setEnabledStateHttpPepBasicAuth(enabled: boolean) {
    const controls = this.settingsForm!.controls;
     setEnabledState(enabled,
      controls.httpPepBasicAuthUsername,
      controls.httpPepBasicAuthPassword,
    )
  }

  private confirmToggle = (val: boolean, fn: (val: boolean) => void, control: FormControl<boolean>) => {
    const reset = () => control.setValue(!val, {emitEvent: false});
    this.modal.confirm({
      nzTitle: 'Warning: advanced toggle!',
      nzContent: 'Toggling this might break the Kvasir UI!<br>Are you sure you want to do this?',
      nzIconType: 'exclamation-circle',
      nzOkDanger: true,
      nzOkText: 'Continue',
      nzOnOk: fn,
      nzOnCancel: reset,
      nzCentered: true
    });
  }
}

function setEnabledState(enabled: boolean, ...controls: FormControl[]): void {
  controls.forEach((control) =>
    enabled ? control.enable() : control.disable(),
  );
}
