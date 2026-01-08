import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzCodeEditorModule } from 'ng-zorro-antd/code-editor';
import { NZ_MODAL_DATA, NzModalModule, NzModalRef } from 'ng-zorro-antd/modal';
import { DevSettingsService } from '../../services/dev-settings.service';

@Component({
  selector: 'app-json-preview',
  imports: [NzModalModule, NzButtonModule, NzCodeEditorModule, FormsModule],
  template: `
    <nz-code-editor
      class="preview"
      [ngModel]="json"
      [nzEditorOption]="settings.getCodeEditorSetting().json_readonly"
    ></nz-code-editor>
  `,
  styles: `
    .preview {
      height: 70vh;
      border: 1px #ccc dotted;
    }
  `,
})
export class JsonPreviewComponent {
  modal = inject(NzModalRef);
  settings = inject(DevSettingsService);
  readonly json: string = inject(NZ_MODAL_DATA);
}
