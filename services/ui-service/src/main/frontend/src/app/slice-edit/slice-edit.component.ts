import { Component, effect, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  FormBuilder,
  FormGroup,
  ReactiveFormsModule,
  ValidatorFn,
  Validators,
} from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzFlexModule } from 'ng-zorro-antd/flex';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzGridModule } from 'ng-zorro-antd/grid';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzPageHeaderModule } from 'ng-zorro-antd/page-header';
import { NzSpaceModule } from 'ng-zorro-antd/space';
import { map, tap } from 'rxjs';
import { KvasirService } from '../services/kvasir.service';
import { Slice, SliceInput } from '../types';
import { NzCodeEditorModule } from 'ng-zorro-antd/code-editor';
import { DevSettingsService } from '../services/dev-settings.service';
import { KSS_FQN, KSS_PREFIX } from '../util/constants';

@Component({
  selector: 'app-slice-edit',
  imports: [
    NzPageHeaderModule,
    NzFormModule,
    ReactiveFormsModule,
    NzSpaceModule,
    NzGridModule,
    NzInputModule,
    NzButtonModule,
    NzFlexModule,
    NzCodeEditorModule,
  ],
  templateUrl: './slice-edit.component.html',
  styleUrl: './slice-edit.component.less',
})
export class SliceEditComponent {
  // DI
  private kvasir = inject(KvasirService);
  private router = inject(Router);
  private route = inject(ActivatedRoute);
  settings = inject(DevSettingsService);

  private slice = rxResource<Slice & { context: string }, unknown>({
    stream: () =>
      this.route.data.pipe(
        map(({ slice }) => slice),
        tap(
          (slice: any) =>
            (slice['@context'] = JSON.stringify(slice['@context'], null, 4)),
        ),
      ),
  });

  private readonly sliceId = this.route.snapshot.paramMap.get('sliceId')!;

  readonly editForm: FormGroup;
  readonly autoTips = {
    default: {
      invalidJson: 'Invalid JSON',
    },
  };

  constructor(fb: FormBuilder) {
    const validators = [Validators.required, this.jsonValidator];
    this.editForm = fb.group({
      '@context': [null, Validators.compose(validators)],
      'kss:description': [],
      'kss:name': [{ value: null, disabled: true }, Validators.required],
      'kss:schema': [null, Validators.required],
    });
    effect(() => {
      if (this.slice.hasValue()) {
        this.reset();
      }
    });
  }

  saveForm(): void {
    if (this.editForm?.valid) {
      const context = {
        ...JSON.parse(this.ctxCtrl.value),
        ...{ [KSS_PREFIX]: KSS_FQN },
      };
      const name = this.nameCtrl.value;
      const schema = this.schemaCtrl.value;
      const description =
        this.descriptionCtrl.value.length > 0
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

      this.kvasir
        .updateSlice(this.sliceId, sliceInput)
        .subscribe((_) => this.router.navigate(['/slices']));
    }
  }

  reset() {
    this.editForm?.reset(this.slice.value());
  }

  get ctxCtrl() {
    return this.editForm?.get('@context')!;
  }

  get nameCtrl() {
    return this.editForm?.get('kss:name')!;
  }

  get schemaCtrl() {
    return this.editForm?.get('kss:schema')!;
  }

  get descriptionCtrl() {
    return this.editForm?.get('kss:description')!;
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
