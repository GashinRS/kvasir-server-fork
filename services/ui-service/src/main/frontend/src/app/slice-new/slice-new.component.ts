import { Component, inject, signal } from '@angular/core';
import {
  FieldTree,
  form,
  FormField,
  FormRoot,
  pattern,
  required,
  validate,
} from '@angular/forms/signals';
import { Router } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzCodeEditorModule } from 'ng-zorro-antd/code-editor';
import { NzFlexModule } from 'ng-zorro-antd/flex';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzGridModule } from 'ng-zorro-antd/grid';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzModalModule, NzModalService } from 'ng-zorro-antd/modal';
import { NzPageHeaderModule } from 'ng-zorro-antd/page-header';
import { NzPopoverModule } from 'ng-zorro-antd/popover';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzSpaceModule } from 'ng-zorro-antd/space';
import { firstValueFrom } from 'rxjs';
import { FormErrorsComponent } from '../components/form-errors/form-errors.component';
import { SdlPreviewComponent } from '../modals/sdl-preview/sdl-preview.component';
import { DevSettingsService } from '../services/dev-settings.service';
import { KvasirService } from '../services/kvasir.service';
import { SliceInput } from '../types';
import { KSS_FQN, KSS_PREFIX } from '../util/constants';

const DEFAULT_CONTEXT = `{
  "${KSS_PREFIX}": "${KSS_FQN}"
}`;

const SCHEMA_TEMPLATE = `type Query {
  # Define your query entry-points here (based on the Slice types below)
}

# Define the Slice types here

`;

interface SliceInputModel {
  name: string;
  description: string;
  context: string;
  schema: string;
  tags: string[];
}

@Component({
  selector: 'app-slice-new',
  imports: [
    NzPageHeaderModule,
    NzFormModule,
    NzInputModule,
    NzButtonModule,
    NzGridModule,
    NzFlexModule,
    NzSpaceModule,
    NzSelectModule,
    NzCodeEditorModule,
    NzModalModule,
    NzPopoverModule,
    FormField,
    FormRoot,
    FormErrorsComponent,
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

  sliceInputModel = signal<SliceInputModel>({
    name: '',
    description: '',
    context: DEFAULT_CONTEXT,
    schema: SCHEMA_TEMPLATE,
    tags: [],
  });

  sliceInputForm = form(
    this.sliceInputModel,
    (schemaPath) => {
      required(schemaPath.name, { message: 'Name is required' });
      pattern(schemaPath.name, /^[a-zA-Z0-9\-\_]+$/, {
        message:
          'Invalid format (only alphanumeric characters, hyphens, and underscores are allowed)',
      });
      required(schemaPath.context, { message: 'Context is required' });
      validate(schemaPath.context, ({ value }) => this.validateJson(value()));
      required(schemaPath.schema, { message: 'Schema is required' });
    },
    {
      submission: {
        action: (fields) => this.submitForm(fields),
      },
    },
  );

  constructor() {}

  async submitForm(fields: FieldTree<SliceInputModel>): Promise<void> {
    const context = {
      ...JSON.parse(fields.context().value()),
      ...{ [KSS_PREFIX]: KSS_FQN },
    };
    const name = fields.name().value();
    const schema = fields.schema().value();
    const description =
      fields.description().value().length > 0
        ? fields.description().value()
        : undefined;

    let sliceInput = {
      '@context': context,
      'kss:name': name,
      'kss:schema': {
        '@type': 'kss:EmbeddedSliceSchema',
        'kss:sdl': schema,
      },
      'kss:tags': fields.tags().value(),
    } as SliceInput;
    if (description) {
      sliceInput['kss:description'] = description;
    }

    await firstValueFrom(this.kvasir.createSlice(sliceInput)).then(() => {
      this.router.navigate(['/slices']);
    });
  }

  resetContext() {
    this.sliceInputForm.context().value.set(DEFAULT_CONTEXT);
  }

  reset() {
    this.sliceInputModel.set({
      name: '',
      description: '',
      context: DEFAULT_CONTEXT,
      schema: SCHEMA_TEMPLATE,
      tags: [],
    });
  }

  preview() {
    if (this.sliceInputForm().valid()) {
      const context = {
        ...JSON.parse(this.sliceInputModel().context),
        ...{ [KSS_PREFIX]: KSS_FQN },
      };
      const name = this.sliceInputModel().name;
      const schema = this.sliceInputModel().schema;
      const description =
        this.sliceInputModel().description.length > 0
          ? this.sliceInputModel().description
          : undefined;

      let sliceInput = {
        '@context': context,
        'kss:name': name,
        'kss:schema': {
          '@type': 'kss:EmbeddedSliceSchema',
          'kss:sdl': schema,
        },
        'kss:tags': this.sliceInputModel().tags,
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

  private validateJson(value: string) {
    try {
      JSON.parse(value);
      return null;
    } catch {
      return {
        kind: 'invalidJson',
        message: 'Invalid JSON',
      };
    }
  }
}
