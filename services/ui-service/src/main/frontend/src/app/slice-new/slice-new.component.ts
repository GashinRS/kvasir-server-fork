import { Component, inject } from '@angular/core';
import {
  AbstractControl,
  FormBuilder,
  FormGroup,
  ReactiveFormsModule,
  ValidatorFn,
  Validators,
} from '@angular/forms';
import { Router } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzCodeEditorModule } from 'ng-zorro-antd/code-editor';
import { NzFlexModule } from 'ng-zorro-antd/flex';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzGridModule } from 'ng-zorro-antd/grid';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzModalModule, NzModalService } from 'ng-zorro-antd/modal';
import { NzPageHeaderModule } from 'ng-zorro-antd/page-header';
import { NzSpaceModule } from 'ng-zorro-antd/space';
import { SdlPreviewComponent } from '../modals/sdl-preview/sdl-preview.component';
import { DevSettingsService } from '../services/dev-settings.service';
import { KvasirService } from '../services/kvasir.service';
import { SliceInput } from '../types';
import { KSS_FQN, KSS_PREFIX } from '../util/constants';
import { NzPopoverModule } from 'ng-zorro-antd/popover';

const DEFAULT_CONTEXT = `{
  "${KSS_PREFIX}": "${KSS_FQN}"
}`;

const SCHEMA_TEMPLATE = `type Query {
  # Define your query entry-points here (based on the Slice types below)
}

# Define the Slice types here

`;

@Component({
  selector: 'app-slice-new',
  imports: [
    NzPageHeaderModule,
    ReactiveFormsModule,
    NzFormModule,
    NzInputModule,
    NzButtonModule,
    NzGridModule,
    NzFlexModule,
    NzSpaceModule,
    NzCodeEditorModule,
    NzModalModule,
    NzPopoverModule,
  ],
  templateUrl: './slice-new.component.html',
  styleUrl: './slice-new.component.less',
})
export class SliceNewComponent {
  // DI
  private kvasir = inject(KvasirService);
  private router = inject(Router);
  private modal = inject(NzModalService);
  settings = inject(DevSettingsService);

  readonly inputForm: FormGroup;
  readonly autoTips = {
    default: {
      invalidJson: 'Invalid JSON',
    },
  };

  constructor(fb: FormBuilder) {
    const validators = [Validators.required, this.jsonValidator];
    this.inputForm = fb.group({
      context: [DEFAULT_CONTEXT, Validators.compose(validators)],
      description: [],
      name: null,
      schema: [SCHEMA_TEMPLATE, Validators.required],
    });
  }

  submitForm(): void {
    if (this.inputForm.valid) {
      const context = {
        ...JSON.parse(this.ctxCtrl.value),
        ...{ [KSS_PREFIX]: KSS_FQN },
      };
      const name = this.nameCtrl.value;
      const schema = this.schemaCtrl.value;
      const description =
        this.descriptionCtrl.value?.length > 0
          ? this.descriptionCtrl.value
          : undefined;

      let sliceInput = {
        '@context': context,
        'kss:name': name,
        'kss:schema': schema,
      } as SliceInput;

      if (description) {
        sliceInput['kss:description'] = description;
      }

      this.kvasir.createSlice(sliceInput).subscribe({
        next: () => this.router.navigate(['/slices']),
      });
    }
  }

  resetContext() {
    this.ctxCtrl.reset(DEFAULT_CONTEXT);
  }

  reset() {
    this.inputForm.reset();
  }

  preview() {
    if (this.inputForm.valid) {
      const context = {
        ...JSON.parse(this.ctxCtrl.value),
        ...{ [KSS_PREFIX]: KSS_FQN },
      };
      const name = this.nameCtrl.value;
      const schema = this.schemaCtrl.value;
      const description =
        this.descriptionCtrl.value?.length > 0
          ? this.descriptionCtrl.value
          : undefined;

      let sliceInput = {
        '@context': context,
        'kss:name': name,
        'kss:schema': schema,
      } as SliceInput;

      if (description) {
        sliceInput['kss:description'] = description;
      }

      this.kvasir.previewSlice(sliceInput).subscribe({
        next: (sdl: string) => {
          const modalRef = this.modal.create<SdlPreviewComponent, string>({
            nzTitle: 'Preview SDL',
            nzContent: SdlPreviewComponent,
            nzData: sdl,
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

  get ctxCtrl() {
    return this.inputForm.get('context')!;
  }

  get nameCtrl() {
    return this.inputForm.get('name')!;
  }

  get schemaCtrl() {
    return this.inputForm.get('schema')!;
  }

  get descriptionCtrl() {
    return this.inputForm.get('description')!;
  }

  /** Validator: JSON */
  private jsonValidator: ValidatorFn = (control: AbstractControl) => {
    try {
      JSON.parse(control.value);
      return null;
    } catch {
      return {
        invalidJson: true,
      };
    }
  };
}
