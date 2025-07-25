import { KeyValue, KeyValuePipe } from '@angular/common';
import { Component, inject, viewChild } from '@angular/core';
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
import { NzFlexModule } from 'ng-zorro-antd/flex';
import { NzFormControlComponent, NzFormModule } from 'ng-zorro-antd/form';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzPageHeaderModule } from 'ng-zorro-antd/page-header';
import { NzSpaceModule } from 'ng-zorro-antd/space';
import { NzTypographyModule } from 'ng-zorro-antd/typography';
import { KvasirService } from '../services/kvasir.service';
import { DevSettingsService } from '../services/dev-settings.service';
import { NzCodeEditorModule } from 'ng-zorro-antd/code-editor';

const DEFAULT_VALUE = `{
  "@context": {
    "kss": "https://kvasir.discover.ilabt.imec.be/vocab#"
  },
  "kss:insert": [],
  "kss:delete": []
}`;

const ERR_KEY_UNDEFINED_PREFIXES = 'undefinedPrefixes';
const ERR_KEY_INVALID_JSON = 'invalidJson';

/**
 * These keys will not disable the submit button and will be rendered in orange as warnings instead of red errors.
 */
const WARNINGS = [ERR_KEY_UNDEFINED_PREFIXES];

@Component({
  selector: 'app-change-new',
  imports: [
    ReactiveFormsModule,
    NzButtonModule,
    NzFlexModule,
    NzFormModule,
    NzInputModule,
    NzPageHeaderModule,
    NzSpaceModule,
    NzTypographyModule,
    KeyValuePipe,
    NzCodeEditorModule,
  ],
  templateUrl: './change-new.component.html',
  styleUrl: './change-new.component.less',
})
export class ChangeNewComponent {
  // DI
  private kvasir = inject(KvasirService);
  private router = inject(Router);
  settings = inject(DevSettingsService);

  readonly inputForm: FormGroup;
  // readonly ctrlChild = viewChild<NzFormControlComponent>('ctrlChild');

  constructor(fb: FormBuilder) {
    const validators = [
      Validators.required,
      this.jsonValidator,
      this.prefixValidator,
    ];
    this.inputForm = fb.group({
      request: [DEFAULT_VALUE, Validators.compose(validators)],
    });
  }

  submitForm(): void {
    if (!this.isInputInvalid()) {
      let changeRequest = this.inputForm.get('request')!.value;
      this.kvasir
        .createChangeRequest(changeRequest)
        .subscribe((location) =>
          location
            ? this.router.navigate([
                '/changes/view',
                encodeURIComponent(location),
              ])
            : this.router.navigate(['/changes']),
        );
    }
  }

  reset() {
    this.inputForm.patchValue({ request: DEFAULT_VALUE });
  }

  errorStyle = (
    errorKey: unknown,
  ): 'secondary' | 'warning' | 'danger' | 'success' | undefined => {
    return WARNINGS.includes(errorKey as string) ? 'warning' : 'danger';
  };

  showError = (entry: KeyValue<unknown, unknown>) => {
    switch (entry.key) {
      case ERR_KEY_UNDEFINED_PREFIXES:
        return `Warning: Prefixes (${(entry.value as string[])?.join(', ')}) are not defined in @context`;
      case ERR_KEY_INVALID_JSON:
        return 'Invalid JSON';
      default:
        return 'Some error occurred...';
    }
  };

  isInputInvalid(): boolean {
    if (this.inputForm.valid) {
      return false;
    } else {
      const errors = Object.values(this.inputForm.controls).flatMap((control) =>
        Object.keys(control.errors ?? {}).filter(
          (key) => !WARNINGS.includes(key),
        ),
      );
      return errors.length > 0;
    }
  }

  private checkItemForPrefixes(item: Object | string): string[] {
    const REGEX = /"(\w+):\w+"/g;
    const str = JSON.stringify(item);
    const prefixes = [];
    for (let m of str.matchAll(REGEX)) {
      prefixes.push(m.at(1)!);
    }
    return prefixes;
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

  /** Validator: undefined prefixes */
  private prefixValidator: ValidatorFn = (control: AbstractControl) => {
    // Control should contain a valid JSON array
    try {
      const { ['@context']: context, ...rest } = JSON.parse(control.value);
      const usedPrefixes = Object.values<any>(rest)
        .flatMap((item) => this.checkItemForPrefixes(item))
        .reduce((acc, prev) => acc.add(prev), new Set<string>());

      // Check if all keys are present in context
      const undefinedPrefixes = Array.from(usedPrefixes).filter(
        (prefix) => !(prefix in context),
      );
      if (undefinedPrefixes.length > 0) {
        return { [ERR_KEY_UNDEFINED_PREFIXES]: undefinedPrefixes };
      }

      return null;
    } catch {
      // No validation on prefixes, since it is not a valid JSON array
      return null;
    }
  };
}
