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
import { NzPageHeaderModule } from 'ng-zorro-antd/page-header';
import { NzSpaceModule } from 'ng-zorro-antd/space';
import { KvasirService } from '../services/kvasir.service';
import { SliceInput } from '../types';
import { DevSettingsService } from '../services/dev-settings.service';

const DEFAULT_CONTEXT = `{
  "kss": "https://kvasir.discover.ilabt.imec.be/vocab#"
}`;

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
  ],
  templateUrl: './slice-new.component.html',
  styleUrl: './slice-new.component.less',
})
export class SliceNewComponent {
  // DI
  private kvasir = inject(KvasirService);
  private router = inject(Router);
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
      name: [null, Validators.required],
      schema: [null, Validators.required],
    });
  }

  submitForm(): void {
    if (this.inputForm.valid) {
      const context = JSON.parse(this.ctxCtrl.value);
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
