import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  model,
  OnInit,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { FormsModule, ReactiveFormsModule } from '@angular/forms';
import {
  disabled,
  form,
  FormField,
  pattern,
  required,
} from '@angular/forms/signals';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzFlexModule } from 'ng-zorro-antd/flex';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzModalModule, NzModalRef } from 'ng-zorro-antd/modal';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzSpaceModule } from 'ng-zorro-antd/space';
import { NzTypographyModule } from 'ng-zorro-antd/typography';
import { FormErrorsComponent } from '../../components/form-errors/form-errors.component';
import { ConfigService } from '../../services/config.service';
import { LoginSessionService } from '../../services/login-session.service';
import { RebacService } from '../../services/rebac.service';
import { RelationshipDefinition } from '../../types';
import {
  AT_CONTEXT_KSS_FGA,
  KSS_FGA_RESOURCE_TYPE,
  KSS_FGA_USER_ANONYMOUS,
  KSS_FGA_USER_TYPE,
  KSS_FGA_USER_WILDCARD,
} from '../../util/constants';
import { ensureSlashAtStart } from '../../util/utils';
import { JsonPipe } from '@angular/common';

type SubjectAddon = 'email' | 'webid' | 'user' | 'everyone' | 'unauthed';

export interface Relationship {
  subject?: string;
  relation?: string;
  object?: string;
}

interface FormModel {
  subjectType: SubjectAddon;
  subject: string;
  permission: string;
  object: string;
}

interface DomainModel {
  subject: string;
  permission: string;
  object: string;
}

const PERMISSIONS = [
  { value: 'kss-fga:can_read', label: 'can_read', icon: 'read' },
  { value: 'kss-fga:can_write', label: 'can_write', icon: 'signature' },
  { value: 'kss-fga:can_delete', label: 'can_delete', icon: 'delete' },
];

@Component({
  selector: 'app-check-permission',
  imports: [
    NzButtonModule,
    NzFormModule,
    NzInputModule,
    NzSelectModule,
    NzModalModule,
    NzFlexModule,
    NzIconModule,
    ReactiveFormsModule,
    FormsModule,
    FormField,
    NzSpaceModule,
    NzTypographyModule,
    FormErrorsComponent,
  ],
  templateUrl: './check-permission.component.html',
  styleUrl: './check-permission.component.less',
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CheckPermissionComponent implements OnInit {
  subjectAddon = model<SubjectAddon>('user');
  permissions = PERMISSIONS;
  prefix = computed(() => this.convertToPrefix(this.formModel().subjectType));
  prefixTxt = computed(() =>
    this.convertToPrefix(this.formModel().subjectType),
  );
  resultClass = signal<'unchecked' | 'granted' | 'denied'>('unchecked');
  resultLabel = computed(() => {
    switch (this.resultClass()) {
      default:
      case 'unchecked':
        return '?';
      case 'granted':
        return 'Allowed';
      case 'denied':
        return 'Not allowed';
    }
  });

  //DI
  private modalRef = inject(NzModalRef);
  private config = inject(ConfigService);
  private rebac = inject(RebacService);
  private session = inject(LoginSessionService);

  // SIGNAL FORMS
  // FormModel
  formModel = signal<FormModel>({
    subjectType: 'user',
    subject: '',
    permission: '',
    object: '',
  });

  readonly signalForm = form(this.formModel, (path) => {
    required(path.subjectType, { message: 'Cannot be empty' });
    required(path.subject, { message: 'Cannot be empty' });
    required(path.permission, { message: 'Cannot be empty' });
    pattern(path.object, /^(^\/.*$)?$/, {
      message: `Resource subpath must start with a '/'`,
    });
    disabled(
      path.subject,
      ({ valueOf }) =>
        valueOf(path.subjectType) == 'everyone' ||
        valueOf(path.subjectType) == 'unauthed',
    );
  });

  private formModelToRelationshipDefinition(
    formModel: FormModel,
  ): RelationshipDefinition {
    const userId =
      formModel.subjectType == 'everyone'
        ? this.prefix()
        : this.prefix() + formModel.subject;
    return {
      '@id': userId,
      '@type': KSS_FGA_USER_TYPE,
      [formModel.permission]: {
        '@id': `${this.config.host}${this.pathPrefix}${ensureSlashAtStart(formModel.object)}`,
        '@type': KSS_FGA_RESOURCE_TYPE,
      },
    } as RelationshipDefinition;
  }

  constructor() {}

  ngOnInit(): void {}

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
        return KSS_FGA_USER_ANONYMOUS;
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

  getRelationIcon(label: 'can_read' | 'can_write' | 'can_delete'): string {
    switch (label) {
      case 'can_read':
        return 'read';
      case 'can_write':
        return 'signature';
      case 'can_delete':
        return 'delete';
      default:
        return 'question-circle';
    }
  }

  close() {
    this.modalRef.close();
  }

  check() {
    if (this.signalForm().valid()) {
      this.resultClass.set('unchecked');
      let rel = this.formModelToRelationshipDefinition(this.formModel());
      let reqObj = { '@context': AT_CONTEXT_KSS_FGA, ...rel };
      this.rebac.check(reqObj).subscribe((res) => {
        if (res['kss:allowed']) {
          this.resultClass.set('granted');
        } else {
          this.resultClass.set('denied');
        }
      });
    } else {
      Object.keys(this.signalForm).forEach((key) => {
        this.signalForm[key as keyof FormModel]().markAsDirty();
        this.signalForm[key as keyof FormModel]().markAsTouched();
      });
    }
  }

  reset() {
    this.signalForm().reset({
      subjectType: 'user',
      subject: '',
      permission: '',
      object: '',
    });
    this.resultClass.set('unchecked');
  }
}
