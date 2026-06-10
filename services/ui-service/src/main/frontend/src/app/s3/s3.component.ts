import { DatePipe, DecimalPipe } from '@angular/common';
import {
  Component,
  computed,
  inject,
  input,
  OnInit,
  resource,
  ViewEncapsulation,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterModule } from '@angular/router';
import type { _Object } from '@aws-sdk/client-s3';
import { Base64 } from 'js-base64';
import { NzBreadCrumbModule } from 'ng-zorro-antd/breadcrumb';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzDividerModule } from 'ng-zorro-antd/divider';
import { NzDropdownModule } from 'ng-zorro-antd/dropdown';
import { NzEmptyModule } from 'ng-zorro-antd/empty';
import { NzFlexModule } from 'ng-zorro-antd/flex';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzModalModule, NzModalService } from 'ng-zorro-antd/modal';
import { NzPageHeaderModule } from 'ng-zorro-antd/page-header';
import { NzSpaceModule } from 'ng-zorro-antd/space';
import { NzTableModule } from 'ng-zorro-antd/table';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import {
  NzUploadChangeParam,
  NzUploadModule,
  NzUploadXHRArgs,
} from 'ng-zorro-antd/upload';
import { asyncScheduler, scheduled } from 'rxjs';
import { CreateRelationshipComponent } from '../modals/create-relationship/create-relationship.component';
import { RebacService } from '../services/rebac.service';
import { S3wrapperService } from '../services/s3wrapper.service';
import { SessionService } from '../services/session.service';
import { RelationshipDefinition, WriteTransaction } from '../types';
import { AT_CONTEXT_KSS_FGA } from '../util/constants';
import {
  ensureArray,
  ensureSlashAtEnd,
  ensureSlashAtStart,
  stripSlashAtEnd,
  stripSlashAtStart,
} from '../util/utils';

@Component({
  selector: 'app-s3',
  imports: [
    NzBreadCrumbModule,
    NzButtonModule,
    NzDropdownModule,
    NzEmptyModule,
    NzIconModule,
    NzInputModule,
    NzModalModule,
    NzPageHeaderModule,
    NzSpaceModule,
    NzFlexModule,
    NzTableModule,
    NzUploadModule,
    NzDividerModule,
    NzModalModule,
    NzTooltipModule,
    DatePipe,
    DecimalPipe,
    FormsModule,
    RouterModule,
  ],
  templateUrl: './s3.component.html',
  styleUrl: './s3.component.less',
  encapsulation: ViewEncapsulation.None,
})
export class S3Component implements OnInit {
  private s3w = inject(S3wrapperService);
  private session = inject(SessionService);
  private router = inject(Router);
  private msg = inject(NzMessageService);
  private modal = inject(NzModalService);
  private rebac = inject(RebacService);

  /** Custom upload request */
  customRequestFn = (items: NzUploadXHRArgs) => {
    const key = this.prefix()
      ? [stripSlashAtEnd(this.prefix()!), items.file.name].join('/')
      : items.file.name;

    const file = items.postFile as File;
    return scheduled(
      this.s3w.uploadObject(key, file),
      asyncScheduler,
    ).subscribe({
      error: (err) => console.log(err),
      next: () => {},
      complete: () => {
        this.dataList.reload();
      },
    });
  };

  sortName = (a: FileOrFolder, b: FileOrFolder) => {
    if (a.type == b.type) {
      return this.unPrefix(a.Key!).localeCompare(this.unPrefix(b.Key!));
    }
    return a.type === 'folder' ? -1 : 1;
  };

  podName = this.session.podName()!;
  prefixRaw = input<string>('');
  prefix = computed(() =>
    this.prefixRaw()?.length > 0 ? Base64.decode(this.prefixRaw()) : undefined,
  );
  breadCrumbPrefix = computed(() =>
    !!this.prefix() ? stripSlashAtEnd(this.prefix()!) : undefined,
  );

  prefixModalVisible = false;
  prefixInputValue: string = '';

  /** Main s3 object list */
  dataList = resource({
    params: () => ({ prefix: this.prefix() }),
    loader: async ({ params }) => {
      const result = await this.s3w.listObjects(params.prefix);
      return ensureArray(result.CommonPrefixes)
        .map(
          (folder) =>
            ({
              Key: folder?.Prefix!,
              type: 'folder',
            }) as FileOrFolder,
        )
        .concat(
          ensureArray(result.Contents ?? []).map(
            (obj: _Object) =>
              ({
                ...obj,
                type: 'file',
              }) as FileOrFolder,
          ),
        );
    },
  });

  ngOnInit(): void {
    this.dataList.reload();
  }

  showModal(): void {
    this.prefixModalVisible = true;
  }

  handleOk(): void {
    this.prefixModalVisible = false;
    const prefixNew = this.prefix()
      ? ensureSlashAtEnd(this.prefix()!) +
        stripSlashAtStart(this.prefixInputValue)
      : this.prefixInputValue;
    this.goToPage(prefixNew);
    this.prefixInputValue = '';
  }

  handleCancel(): void {
    this.prefixModalVisible = false;
    this.prefixInputValue = '';
  }

  private goToPage(prefix?: string): void {
    const prefixEnc = prefix
      ? Base64.encodeURL(ensureSlashAtEnd(prefix))
      : undefined;
    this.router.navigate(['s3', prefixEnc]);
  }

  goToFolder(folderName: string): void {
    const prefix = this.prefix()
      ? ensureSlashAtEnd(this.prefix()!) + stripSlashAtStart(folderName)
      : folderName;
    const prefixEnc = prefix
      ? Base64.encodeURL(ensureSlashAtEnd(prefix))
      : undefined;
    this.router.navigate(['s3', prefixEnc]);
  }

  goToBreadcrumbIndex(idx: number) {
    const prefixNew = this.prefix()
      ?.split('/')
      .slice(0, idx + 1)
      .join('/');
    const prefixEnc = prefixNew
      ? Base64.encodeURL(ensureSlashAtEnd(prefixNew))
      : undefined;
    this.router.navigate(['s3', prefixEnc]);
  }

  handleChange(info: NzUploadChangeParam): void {
    if (info.file.status !== 'uploading') {
      // console.log(info.file, info.fileList);
    }
    if (info.file.status === 'done') {
      this.msg.success(`${info.file.name} file uploaded successfully`);
    } else if (info.file.status === 'error') {
      this.msg.error(`${info.file.name} file upload failed.`);
    }
  }

  async downloadObject(key: string): Promise<void> {
    const res = await this.s3w.downloadObject(key);
    const bytes = await res.arrayBuffer();
    const blob = new Blob([bytes], { type: this.s3w.keyToMIME(key) });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = key.slice(key.lastIndexOf('/') + 1);
    document.body.appendChild(a);
    a.click();

    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  }

  async deleteObject(key: string): Promise<void> {
    const removeFn = async () => {
      await this.s3w.deleteObject(key);
      this.dataList.reload();
    };
    this.modal.confirm({
      nzContent: `<pre>` + key + `</pre>`,
      nzTitle: 'Remove this resource?',
      nzOkDanger: true,
      nzOkText: 'Remove',
      nzOnOk: removeFn,
      nzWidth: '680px',
    });
  }

  createPolicyRule(key: string): void {
    const ref = this.modal.create<
      CreateRelationshipComponent,
      any,
      RelationshipDefinition
    >({
      nzTitle: 'Create a new relationship',
      nzContent: CreateRelationshipComponent,
      nzClassName: 'create-relationship-content',
      nzData: {
        subject: null,
        relation: null,
        object: `/${this.podName}/s3${ensureSlashAtStart(key)}`,
      },
    });
    ref.afterClose.asObservable().subscribe((relDef) => {
      if (relDef) {
        const trans: WriteTransaction = {
          '@context': AT_CONTEXT_KSS_FGA,
          'kss:insert': [relDef],
        };
        this.rebac
          .postRelationship(trans)
          .subscribe(() => this.router.navigate(['access-control']));
      }
    });
  }

  unPrefix(key: string): string {
    const len = !!this.prefix() ? this.prefix()!.length : 0;
    const tmp = key.slice(len);
    const idx = tmp.indexOf('/');
    const end = idx === -1 ? tmp.length : idx;
    return tmp.slice(0, end);
  }

  private mapToMimeType(key: string): string {
    if (key.indexOf('.') === -1) return 'no-extension';
    const ext = key.split('.').pop();
    switch (ext) {
      case 'bmp':
        return 'image/bmp';
      case 'jpg':
      case 'jpeg':
        return 'image/jpeg';
      case 'gif':
        return 'image/gif';
      case 'png':
        return 'image/png';
      case 'svg':
        return 'image/svg+xml';
      case 'webp':
        return 'image/webp';

      case 'pdf':
        return 'application/pdf';
      case 'txt':
        return 'text/plain';
      case 'md':
        return 'text/markdown';

      case 'rdf':
        return 'application/rdf+xml';
      case 'json':
        return 'application/json';
      case 'jsonld':
        return 'application/ld+json';
      case 'ttl':
        return 'text/turtle';

      case 'doc':
        return 'application/msword';
      case 'docx':
        return 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';
      case 'ppt':
        return 'application/vnd.ms-powerpoint';
      case 'pptx':
        return 'application/vnd.openxmlformats-officedocument.presentationml.presentation';
      case 'csv':
        return 'text/csv';
      case 'xls':
        return 'application/vnd.ms-excel';
      case 'xlsx':
        return 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';

      case 'htm':
      case 'html':
        return 'text/html';

      case 'js':
        return 'application/javascript';
      case 'ts':
        return 'application/typescript';

      case 'zip':
        return 'application/zip';
      case 'rar':
        return 'application/x-rar-compressed';
      case '7z':
        return 'application/x-7z-compressed';
      case 'tar':
        return 'application/x-tar';
      case 'gz':
        return 'application/gzip';

      default:
        return 'application/octet-stream';
    }
  }

  mapToIcon(key: string): string {
    const mimeType = this.mapToMimeType(key);
    switch (mimeType) {
      case 'image/jpeg':
        return 'file-jpg';
      case 'image/gif':
        return 'file-gif';
      case 'image/png':
      case 'image/bmp':
      case 'image/svg+xml':
      case 'image/webp':
        return 'file-image';

      case 'text/csv':
      case 'application/vnd.ms-excel':
      case 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet':
        return 'file-excel';

      case 'text/markdown':
        return 'file-markdown';

      case 'application/pdf':
        return 'file-pdf';

      case 'application/vnd.ms-powerpoint':
      case 'application/vnd.openxmlformats-officedocument.presentationml.presentation':
        return 'file-ppt';

      case 'application/msword':
      case 'application/vnd.openxmlformats-officedocument.wordprocessingml.document':
        return 'file-word';

      case 'text/plain':
      case 'application/rdf+xml':
      case 'application/json':
      case 'application/ld+json':
      case 'text/turtle':
        return 'file-text';

      case 'application/zip':
      case 'application/x-rar-compressed':
      case 'application/x-7z-compressed':
      case 'application/x-tar':
      case 'application/gzip':
        return 'file-zip';

      // Edge cases
      case 'no-extension':
        return 'file';
      case 'application/octet-stream':
      default:
        return 'file-unknown';
    }
  }
}

interface _File {
  Key: string;
  LastModified: Date;
  Size: number;
  type: 'file';
}

interface _Folder {
  Key: string;
  type: 'folder';
}

type FileOrFolder = _File | _Folder;
