import { AfterViewInit, Component, inject, signal } from '@angular/core';
import {
  AbstractControl,
  FormBuilder,
  FormGroup,
  FormsModule,
  ReactiveFormsModule,
} from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzFlexModule } from 'ng-zorro-antd/flex';
import { NzGridModule } from 'ng-zorro-antd/grid';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzLayoutModule } from 'ng-zorro-antd/layout';
import {
  NzNotificationModule,
  NzNotificationService,
} from 'ng-zorro-antd/notification';
import { NzPageHeaderModule } from 'ng-zorro-antd/page-header';
import { NzSpaceModule } from 'ng-zorro-antd/space';
import { NzSwitchModule } from 'ng-zorro-antd/switch';
import { NzToolTipModule } from 'ng-zorro-antd/tooltip';
import { NzTypographyModule } from 'ng-zorro-antd/typography';
import { Observable } from 'rxjs';
import { HelpComponent } from '../components/help/help.component';
import { KvasirService } from '../services/kvasir.service';
import { Pod, PodConfiguration } from '../types';

@Component({
  selector: 'app-settings',
  imports: [
    FormsModule,
    NzInputModule,
    NzSpaceModule,
    NzButtonModule,
    NzTypographyModule,
    NzGridModule,
    NzSwitchModule,
    NzFlexModule,
    NzPageHeaderModule,
    NzNotificationModule,
    NzToolTipModule,
    NzLayoutModule,
    HelpComponent,
    ReactiveFormsModule,
  ],
  templateUrl: './settings.component.html',
  styleUrl: './settings.component.less',
})
export class SettingsComponent implements AfterViewInit {
  // DI
  private route = inject(ActivatedRoute);
  private kvasir = inject(KvasirService);
  private notify = inject(NzNotificationService);

  defaultContextError = signal<string | undefined>(undefined);
  settingsForm: FormGroup;

  private originalConfig?: PodConfiguration;

  constructor(fb: FormBuilder) {
    // Init form
    this.settingsForm = fb.group({
      defaultContext: [],
      autoIngestRDF: [false],
    });
    this.defaultContext.valueChanges.subscribe((val) => this.onInput(val));
    this.autoIngestRDF.valueChanges.subscribe((val) =>
      this.saveAutoIngestRDF(val),
    );
  }

  ngAfterViewInit(): void {
    this.route.data.subscribe(({ pod }) => {
      this.originalConfig = (pod as Pod)['kss:configuration'];
      const defaultContext = JSON.stringify(
        JSON.parse(this.originalConfig['kss:defaultContext']!),
        null,
        4,
      );
      const autoIngestRDF = this.originalConfig['kss:autoIngestRDF']!;
      this.settingsForm.setValue(
        { defaultContext, autoIngestRDF },
        { emitEvent: false },
      );
    });
  }

  onInput(value: string) {
    if (this.JSONvalidate(value)) {
      this.defaultContextError.set(undefined);
    } else {
      this.defaultContextError.set('Invalid JSON syntax!');
    }
  }

  resetDefaultContext() {
    const defaultContext = JSON.stringify(
      JSON.parse(this.originalConfig!['kss:defaultContext']!),
      null,
      4,
    );
    this.defaultContext.setValue(defaultContext);
  }

  saveDefaultContext() {
    const currentAutoIngestRDF = this.autoIngestRDF.value;
    const podConfig: PodConfiguration = {
      'kss:defaultContext': this.defaultContext.value,
      'kss:autoIngestRDF': currentAutoIngestRDF,
      'kss:authConfiguration': this.originalConfig!['kss:authConfiguration'],
    };
    this.updatePodSettings(podConfig).subscribe({
      next: (ok) => {
        this.originalConfig!['kss:defaultContext'] =
          podConfig!['kss:defaultContext'];
        this.notify.success('Saved', 'Default context');
      },
      error: (err) =>
        this.notify.error('Error', 'Failed saving Default context state'),
    });
  }

  private saveAutoIngestRDF(val: boolean) {
    const podConfig: PodConfiguration = {
      'kss:autoIngestRDF': val,
      'kss:defaultContext': this.originalConfig!['kss:defaultContext'],
      'kss:authConfiguration': this.originalConfig!['kss:authConfiguration'],
    };
    this.updatePodSettings(podConfig).subscribe({
      next: (ok) => {
        this.originalConfig!['kss:autoIngestRDF'] =
          podConfig!['kss:autoIngestRDF'];
        this.notify.success('Saved', 'Auto-ingest RDF ' + (val ? 'on' : 'off'));
      },
      error: (err) =>
        this.notify.error('Error', 'Failed saving Auto-ingest RDF state'),
    });
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

  private get autoIngestRDF(): AbstractControl {
    return this.settingsForm.get('autoIngestRDF')!;
  }

  private get defaultContext(): AbstractControl {
    return this.settingsForm.get('defaultContext')!;
  }
}
