import {
  Component,
  computed,
  effect,
  Inject,
  inject,
  model,
  ViewEncapsulation,
} from '@angular/core';
import {
  FormBuilder,
  FormControl,
  FormGroup,
  FormsModule,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NZ_MODAL_DATA, NzModalModule, NzModalRef } from 'ng-zorro-antd/modal';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { ConfigService } from '../../services/config.service';
import { LoginSessionService } from '../../services/login-session.service';
import { RelationshipDefinition } from '../../types';
import { KSS_FGA_RESOURCE_TYPE, KSS_FGA_USER_TYPE } from '../../util/constants';
import { ensureSlashAtStart } from '../../util/utils';

type SubjectAddon = 'email' | 'webid' | 'user' | 'everyone';

interface RelationshipModel {
  subject: FormControl<string | null>;
  relation: FormControl<string | null>;
  object: FormControl<string | null>;
}

export interface IRelationship {
  subject?: string;
  relation?: string;
  object?: string;
}

export interface RelationOption {
  value: string;
  label: string;
  groupLabel: 'normal' | 'special' | 'elevated';
  icon: string;
}

const RELATIONS: RelationOption[] = [
  {
    value: 'kss-fga:reader',
    label: 'reader',
    groupLabel: 'normal',
    icon: 'read',
  },
  {
    value: 'kss-fga:writer',
    label: 'writer',
    groupLabel: 'normal',
    icon: 'signature',
  },
  {
    value: 'kss-fga:deleter',
    label: 'deleter',
    groupLabel: 'normal',
    icon: 'delete',
  },
  {
    value: 'kss-fga:blocked',
    label: 'blocked',
    groupLabel: 'special',
    icon: 'stop',
  },
  {
    value: 'kss-fga:manager',
    label: 'manager',
    groupLabel: 'elevated',
    icon: 'key',
  },
  {
    value: 'kss-fga:owner',
    label: 'owner',
    groupLabel: 'elevated',
    icon: 'crown',
  },
];

@Component({
  selector: 'app-create-relationship',
  imports: [
    NzButtonModule,
    NzFormModule,
    NzInputModule,
    NzSelectModule,
    NzModalModule,
    NzIconModule,
    ReactiveFormsModule,
    FormsModule,
  ],
  templateUrl: './create-relationship.component.html',
  styleUrl: './create-relationship.component.less',
  encapsulation: ViewEncapsulation.None,
})
export class CreateRelationshipComponent {
  relationshipForm: FormGroup<RelationshipModel>;
  subjectAddon = model<SubjectAddon>('user');
  relations = RELATIONS.reduce(
    (prev, curr) => {
      prev[curr.groupLabel].push(curr);
      return prev;
    },
    { normal: [], special: [], elevated: [] } as Record<
      'normal' | 'special' | 'elevated',
      RelationOption[]
    >,
  );
  prefix = computed(() => this.convertToPrefix(this.subjectAddon()));

  //DI
  private modalRef = inject(NzModalRef);
  private config = inject(ConfigService);
  private session = inject(LoginSessionService);

  autoTips: Record<string, Record<string, string>> = {
    default: {
      required: 'Cannot be empty',
      pattern: `Resource subpath must start with a '/'`,
    },
  };

  constructor(
    fb: FormBuilder,
    @Inject(NZ_MODAL_DATA) private data: IRelationship,
  ) {
    this.relationshipForm = fb.group<RelationshipModel>({
      subject: fb.control(
        { disabled: false, value: data?.subject ?? null },
        {
          validators: Validators.required,
        },
      ),
      relation: fb.control(
        { disabled: false, value: data?.relation ?? null },
        {
          validators: Validators.required,
        },
      ),
      object: fb.control(
        {
          disabled: false,
          value: data?.object?.substring(this.pathPrefix.length) ?? null,
        },
        {
          validators: Validators.pattern(/^(^\/.*$)?$/),
        },
      ),
    });
    effect(() => {
      if (this.subjectAddon() == 'everyone') {
        this.relationshipForm.controls.subject.disable();
        this.relationshipForm.controls.subject.reset();
      } else {
        this.relationshipForm.controls.subject.enable();
        this.relationshipForm.controls.subject.reset();
      }
    });
  }

  get pathPrefix(): string {
    return `/${this.session.getCurrentLoginSession()!.podName}`;
  }

  convertToPrefix(key: SubjectAddon): string {
    switch (key) {
      case 'email':
        return 'mailto:';
      case 'webid':
        return 'https://';
      case 'everyone':
        return '*';
      default:
      case 'user':
        return 'urn:kvasir-user:';
    }
  }

  getIcon(selected: 'email' | 'webid' | 'user' | 'everyone'): string {
    switch (selected) {
      case 'email':
        return 'mail';
      case 'webid':
        return 'idcard';
      case 'user':
        return 'user';
      default:
        return 'question-circle';
    }
  }

  getRelationIcon(
    selected: 'reader' | 'writer' | 'deleter' | 'blocked' | 'manager' | 'owner',
  ): string {
    switch (selected) {
      case 'reader':
        return 'read';
      case 'writer':
        return 'signature';
      case 'deleter':
        return 'delete';
      case 'blocked':
        return 'stop';
      case 'manager':
        return 'key';
      case 'owner':
        return 'crown';
      default:
        return 'question-circle';
    }
  }

  create() {
    if (this.relationshipForm.valid) {
      let rel = this.createRelationShipDefinition();
      this.modalRef.close(rel);
    } else {
      Object.values(this.relationshipForm.controls).forEach(
        (control: FormControl) => {
          if (control.invalid) {
            control.markAsDirty();
            control.updateValueAndValidity({ onlySelf: true });
          }
        },
      );
    }
  }

  reset() {
    const obj = {
      ...this.data,
      ...{
        object: this.data?.object?.substring(this.pathPrefix.length) ?? null,
      },
    };
    this.relationshipForm.reset(obj);
    this.subjectAddon.set('user');
  }

  private createRelationShipDefinition(): RelationshipDefinition {
    let { value: rel } = this.relationshipForm;
    let userId =
      this.subjectAddon() == 'everyone'
        ? `user:${this.prefix()}`
        : this.prefix() + rel.subject!;
    return {
      '@id': userId,
      '@type': KSS_FGA_USER_TYPE,
      [rel.relation!]: {
        '@id': `${this.config.host}${this.pathPrefix}${ensureSlashAtStart(rel.object!)}`,
        '@type': KSS_FGA_RESOURCE_TYPE,
      },
    } as RelationshipDefinition;
  }
}
