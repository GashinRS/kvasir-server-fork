import {
  Component,
  computed,
  effect,
  Inject,
  inject,
  model,
  viewChild,
  ViewEncapsulation
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
import { NzPopoverModule } from "ng-zorro-antd/popover";
import { NzRadioModule } from 'ng-zorro-antd/radio';
import { NzSelectComponent, NzSelectModule } from 'ng-zorro-antd/select';
import { NzSpaceModule } from 'ng-zorro-antd/space';
import { NzTabsModule } from 'ng-zorro-antd/tabs';
import { NzTooltipModule } from "ng-zorro-antd/tooltip";
import { SelectRelationComponent } from '../../components/select-relation/select-relation.component';
import { ConfigService } from '../../services/config.service';
import { LoginSessionService } from '../../services/login-session.service';
import { RelationshipDefinition } from '../../types';
import { KSS_FGA_EXTERNAL_ACCESS, KSS_FGA_EXTERNAL_ACCESS_HTTP_ENDPOINT, KSS_FGA_EXTERNAL_ACCESS_UMA, KSS_FGA_RESOURCE_TYPE, KSS_FGA_USER_ANONYMOUS, KSS_FGA_USER_TYPE, KSS_FGA_USER_WILDCARD } from '../../util/constants';
import { ensureSlashAtStart } from '../../util/utils';


type SubjectAddon = 'email' | 'webid' | 'user' | 'everyone' | 'unauthed';

interface RelationshipModel {
  acType: FormControl<AccessControlType>;
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

type AccessControlType = 'kvasir' | 'uma' | 'httpPep';

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
    NzSpaceModule,
    NzModalModule,
    NzIconModule,
    NzTabsModule,
    NzTooltipModule,
    NzPopoverModule,
    NzRadioModule,
    ReactiveFormsModule,
    FormsModule,
    SelectRelationComponent,
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

  subjectSelector = viewChild<NzSelectComponent>('subjectSelector');

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
      acType: fb.nonNullable.control('kvasir'),
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
    this.relationshipForm.controls.acType.valueChanges.subscribe((acType) => {
      let model: any;
      switch (acType) {
        case 'kvasir':
          model = {
            acType,
          };
          this.relationshipForm.controls.subject.enable();
          this.subjectSelector()?.setDisabledState(false);
          break;
        case 'httpPep':
        case 'uma':
          model = {
            acType,
            subject: null,
          };
          this.relationshipForm.controls.subject.disable();
          this.subjectAddon.set('everyone');
          this.subjectSelector()!.setDisabledState(true);
          break;

      }
      this.relationshipForm.reset(model, { emitEvent: false });
    });
    effect(() => {
      const control = this.relationshipForm.controls.subject;
      if (this.subjectAddon() == 'everyone' || this.subjectAddon() == 'unauthed') {
        control.disable();
      } else {
        control.enable();
      }
    })
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
      default:
      case 'user':
        return 'urn:kvasir-user:';
      case 'everyone':
        return KSS_FGA_USER_WILDCARD;
      case 'unauthed':
        return KSS_FGA_USER_ANONYMOUS
    }
  }

  getIcon(selected: SubjectAddon): string {
    switch (selected) {
      case 'email':
        return 'mail';
      case 'webid':
        return 'idcard';
      case 'user':
        return 'user';
      case 'everyone':
        return 'team';
      case 'unauthed':
        return 'unlock';
      default:
        return 'question-circle';
    }
  }

  getRelationIcon(
    selected: 'reader' | 'writer' | 'deleter' | 'blocked' | 'owner',
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

  isFormValid(): boolean {
    const { acType, subject, relation, object } =
      this.relationshipForm.controls;
    switch (acType.value) {
      case 'kvasir':
        switch (this.subjectAddon()) {
          case 'everyone':
          case 'unauthed':
            return relation.valid && this.prefix().trim().length > 0;
          default:
            return subject.valid && relation.valid && object.valid;
        }
      case 'uma':
        return relation.valid;
      case 'httpPep':
        return relation.valid;
      default:
        return false;
    }
  }

  private createRelationShipDefinition(): RelationshipDefinition {
    let { value: rel } = this.relationshipForm;
    let userId = this.prefix() + (rel.subject ?? '');
    let relation = {
        '@id': `${this.config.host}${this.pathPrefix}${ensureSlashAtStart(rel.object!)}`,
        '@type': KSS_FGA_RESOURCE_TYPE,
      };
    if (this.acType == 'uma') {
      relation = {...relation, ...{[KSS_FGA_EXTERNAL_ACCESS]: {"@id": KSS_FGA_EXTERNAL_ACCESS_UMA}}};
    } else if (this.acType == 'httpPep') {
      relation = {...relation, ...{[KSS_FGA_EXTERNAL_ACCESS]: {"@id": KSS_FGA_EXTERNAL_ACCESS_HTTP_ENDPOINT}}};    }
    
    return {
      '@id': userId,
      '@type': KSS_FGA_USER_TYPE,
      [rel.relation!]: relation
    } as RelationshipDefinition;
  }

  get acType(): AccessControlType {
    return this.relationshipForm.controls.acType.value;
  }
}
