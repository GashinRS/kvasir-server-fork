import {
  Component,
  computed,
  inject,
  input,
  linkedSignal,
  Signal,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormBuilder } from '@angular/forms';
import {
  disabled,
  FieldTree,
  form,
  FormField,
  FormRoot,
  required,
  validate,
  validateAsync,
} from '@angular/forms/signals';
import { ActivatedRoute, Router } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzCodeEditorModule } from 'ng-zorro-antd/code-editor';
import { NzFlexModule } from 'ng-zorro-antd/flex';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzGridModule } from 'ng-zorro-antd/grid';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzModalService } from 'ng-zorro-antd/modal';
import { NzPageHeaderModule } from 'ng-zorro-antd/page-header';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzSpaceModule } from 'ng-zorro-antd/space';
import { NzSpinModule } from 'ng-zorro-antd/spin';
import { NzTagModule } from 'ng-zorro-antd/tag';
import { firstValueFrom, map, of } from 'rxjs';
import { FormErrorsComponent } from '../components/form-errors/form-errors.component';
import { SdlPreviewComponent } from '../modals/sdl-preview/sdl-preview.component';
import { NzInputFormFieldBridgeDirective } from '../nz-input-form-field-bridge.directive';
import { DevSettingsService } from '../services/dev-settings.service';
import { KvasirService } from '../services/kvasir.service';
import { EmbeddedSliceSchema, Slice, SliceInput } from '../types';
import { KSS_FQN, KSS_PREFIX } from '../util/constants';

export enum EditMode {
  EDIT_SLICE,
  EDIT_TAG,
  FORK_TAG,
}

interface SliceEditModel {
  name: string;
  description: string;
  context: string;
  schema: string;
  tags: string[];
}

type ConvertedSlice = Omit<Slice, 'kss:schema' | '@context'> & {
  '@context': string;
  'kss:schema': string;
};

@Component({
  selector: 'app-slice-edit',
  imports: [
    NzPageHeaderModule,
    NzFormModule,
    NzSpaceModule,
    NzGridModule,
    NzInputModule,
    NzButtonModule,
    NzFlexModule,
    NzCodeEditorModule,
    NzSelectModule,
    NzTagModule,
    NzSpinModule,
    FormField,
    FormRoot,
    FormErrorsComponent,
    NzInputFormFieldBridgeDirective, // Because nz-input doesn't work with FormField's disabled state out of the box
  ],
  templateUrl: './slice-edit.component.html',
  styleUrl: './slice-edit.component.less',
})
export class SliceEditComponent {
  // DI
  private kvasir = inject(KvasirService);
  private router = inject(Router);
  private route = inject(ActivatedRoute);
  private modal = inject(NzModalService);
  settings = inject(DevSettingsService);

  editMode = input<EditMode>(EditMode.EDIT_SLICE);
  pageTitle = computed(() => {
    switch (this.editMode()) {
      default:
      case EditMode.EDIT_SLICE:
        return 'Edit Slice';
      case EditMode.EDIT_TAG:
        return 'Edit Tag';
      case EditMode.FORK_TAG:
        return 'Fork Tag';
    }
  });
  pageDescription = computed(() => {
    switch (this.editMode()) {
      default:
      case EditMode.EDIT_SLICE:
        return 'Edit the slice details and schema.';
      case EditMode.EDIT_TAG:
        return 'Edit the slice details and schema for this tag.';
      case EditMode.FORK_TAG:
        return 'Fork this tag and edit the slice details and schema to create a new tag.';
    }
  });

  private slice = rxResource<ConvertedSlice, unknown>({
    stream: () =>
      this.route.data.pipe(
        map(({ slice }) => slice),
        map((slice: any) => {
          const sliceCopy = { ...slice } as ConvertedSlice;
          sliceCopy['@context'] = JSON.stringify(
            sliceCopy['@context'],
            null,
            4,
          );
          if (slice['kss:schema']?.['kss:sdl'] !== undefined) {
            sliceCopy['kss:schema'] = slice['kss:schema']['kss:sdl'];
          }
          return sliceCopy;
        }),
      ),
  });

  readonly sliceId = input.required<string>();
  readonly tag = input.required<string>();
  readonly aliasTags = input<string[]>();

  sliceEditModel = linkedSignal<ConvertedSlice | undefined, SliceEditModel>({
    source: this.slice.value,
    computation: (slice) => {
      const tags = this.editMode() === EditMode.EDIT_TAG ? [this.tag()] : [];
      return slice
        ? ({
            name: slice['kss:name']!,
            description: slice['kss:description'] ?? '',
            context: slice['@context']!,
            schema: slice['kss:schema']!,
            tags,
          } as SliceEditModel)
        : ({
            name: '',
            description: '',
            context: '',
            schema: '',
            tags: [],
          } as SliceEditModel);
    },
  });

  private getDuplicateTagsResource = (
    tagsSignal: Signal<string[] | undefined>,
  ) => {
    return rxResource({
      params: () => tagsSignal(),
      stream: ({ params: tags }) =>
        tags === undefined || tags.length === 0
          ? of([])
          : this.kvasir.listSliceTags(this.sliceId()).pipe(
              map(
                (existingTags) =>
                  existingTags['@graph'].map((tag) => tag['kss:tag']) ?? [],
              ),
              map((existingTags) =>
                tags.filter((tag) => existingTags.includes(tag)),
              ),
            ),
    });
  };

  sliceEditForm = form(
    this.sliceEditModel,
    (schemaPath) => {
      required(schemaPath.name, { message: 'Name is required' });
      required(schemaPath.context, { message: 'Context is required' });
      validate(schemaPath.context, ({ value }) => this.validateJson(value()));
      required(schemaPath.schema, { message: 'Schema is required' });
      validateAsync(schemaPath.tags, {
        params: ({ value }) => value(),
        factory: this.getDuplicateTagsResource,
        onSuccess: (duplicates) =>
          duplicates.length === 0
            ? undefined
            : {
                message: `${duplicates.join(', ')} already exists (and will be overwritten)`,
                kind: 'WARN: nonUniqueTag',
              },
        onError: () => ({
          message: 'Error validating tags',
          kind: 'validationError',
        }),
      });

      disabled(schemaPath.name);
      disabled(schemaPath.tags, () => this.editMode() == EditMode.EDIT_TAG);
    },
    {
      submission: {
        action: (fields) => this.saveForm(fields),
      },
    },
  );

  constructor(fb: FormBuilder) {}

  async saveForm(fields: FieldTree<SliceEditModel>): Promise<void> {
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
      } as EmbeddedSliceSchema,
      'kss:tags': fields.tags().value(),
    } as SliceInput;

    if (description) {
      sliceInput['kss:description'] = description;
    }

    switch (this.editMode()) {
      case EditMode.EDIT_SLICE:
        await firstValueFrom(
          this.kvasir.updateSlice(this.sliceId(), sliceInput),
        );
        break;
      case EditMode.EDIT_TAG:
      case EditMode.FORK_TAG:
        await firstValueFrom(
          this.kvasir.updateTaggedSlice(this.sliceId(), this.tag(), sliceInput),
        );
        break;
    }
    this.router.navigate(['/slices']);
  }

  reset() {
    this.slice.reload();
  }

  preview() {
    if (this.sliceEditForm().valid()) {
      const context = {
        ...JSON.parse(this.sliceEditModel().context),
        ...{ [KSS_PREFIX]: KSS_FQN },
      };
      const name = this.sliceEditModel().name;
      const schema = this.sliceEditModel().schema;
      const description =
        this.sliceEditModel().description.length > 0
          ? this.sliceEditModel().description
          : undefined;

      let sliceInput = {
        '@context': context,
        'kss:name': name,
        'kss:schema': {
          '@type': 'kss:EmbeddedSliceSchema',
          'kss:sdl': schema,
        },
        'kss:tags': this.sliceEditModel().tags,
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
