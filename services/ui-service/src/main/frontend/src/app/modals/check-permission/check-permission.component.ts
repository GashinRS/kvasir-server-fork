import {
  Component,
  computed,
  effect,
  EventEmitter,
  inject,
  model,
  OnInit,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import {
  AbstractControl,
  FormBuilder,
  FormControl,
  FormGroup,
  FormsModule,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzFlexModule } from 'ng-zorro-antd/flex';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzModalModule, NzModalRef } from 'ng-zorro-antd/modal';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { debounceTime } from 'rxjs';
import { ConfigService } from '../../services/config.service';
import { RebacService } from '../../services/rebac.service';
import { RelationshipDefinition } from '../../types';
import { KSS_FGA_RESOURCE_TYPE, KSS_FGA_USER_TYPE } from '../../util/constants';
import { ensureSlashAtStart } from '../../util/utils';
import { LoginSessionService } from '../../services/login-session.service';
import { NzIconModule } from 'ng-zorro-antd/icon';

type SubjectAddon = 'email' | 'webid' | 'user' | 'everyone';

interface PermissionModel {
  subject: FormControl<string | null>;
  permission: FormControl<string | null>;
  object: FormControl<string | null>;
}

export interface Relationship {
  subject?: string;
  relation?: string;
  object?: string;
}

const PERMISSIONS = [
  { value: 'kss-fga:can_read', label: 'can_read' },
  { value: 'kss-fga:can_write', label: 'can_write' },
  { value: 'kss-fga:can_delete', label: 'can_delete' },
  { value: 'kss-fga:can_manage', label: 'can_manage' },
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
  ],
  templateUrl: './check-permission.component.html',
  styleUrl: './check-permission.component.less',
  encapsulation: ViewEncapsulation.None,
})
export class CheckPermissionComponent implements OnInit {
  permissionForm: FormGroup<PermissionModel>;
  subjectAddon = model<SubjectAddon>('user');
  permissions = PERMISSIONS;
  prefix = computed(() => this.convertToPrefix(this.subjectAddon()));
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

  autoTips: Record<string, Record<string, string>> = {
    default: {
      required: 'Cannot be empty',
      pattern: `Resource subpath must start with a '/'`,
    },
  };

  constructor(fb: FormBuilder) {
    this.permissionForm = fb.group<PermissionModel>({
      subject: fb.control(
        { disabled: false, value: null },
        {
          validators: Validators.required,
        },
      ),
      permission: fb.control(
        { disabled: false, value: null },
        {
          validators: Validators.required,
        },
      ),
      object: fb.control(
        { disabled: false, value: '' },
        {
          validators: Validators.pattern(/^(^\/.*$)?$/),
        },
      ),
    });
    effect(() => {
      if (this.subjectAddon() == 'everyone') {
        this.permissionForm.controls.subject.disable();
        this.permissionForm.controls.subject.reset();
      } else {
        this.permissionForm.controls.subject.enable();
        this.permissionForm.controls.subject.reset();
      }
    });
  }

  ngOnInit(): void {
    this.permissionForm.valueChanges.pipe(debounceTime(200)).subscribe((_) => {
      if ('unchecked' != this.resultClass()) {
        this.resultClass.set('unchecked');
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

  close() {
    this.modalRef.close();
  }

  check() {
    if (this.permissionForm.valid) {
      this.resultClass.set('unchecked');
      let rel = this.createPermissionDefinition();
      this.rebac.check(rel).subscribe((res) => {
        if (res['kss-fga:allowed']) {
          this.resultClass.set('granted');
        } else {
          this.resultClass.set('denied');
        }
      });
    } else {
      Object.values(this.permissionForm.controls).forEach(
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
    this.permissionForm.reset();
    this.subjectAddon.set('user');
    this.resultClass.set('unchecked');
  }

  private createPermissionDefinition(): RelationshipDefinition {
    let { value: rel } = this.permissionForm;
    let userId =
      this.subjectAddon() == 'everyone'
        ? `user:${this.prefix()}`
        : this.prefix() + rel.subject!;
    return {
      '@id': userId,
      '@type': KSS_FGA_USER_TYPE,
      [rel.permission!]: {
        '@id': `${this.config.host}${this.pathPrefix}${ensureSlashAtStart(rel.object!)}`,
        '@type': KSS_FGA_RESOURCE_TYPE,
      },
    } as RelationshipDefinition;
  }
}
