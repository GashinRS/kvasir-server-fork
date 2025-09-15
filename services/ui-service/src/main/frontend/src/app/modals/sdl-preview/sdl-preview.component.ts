import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzCodeEditorModule } from 'ng-zorro-antd/code-editor';
import { NZ_MODAL_DATA, NzModalModule, NzModalRef } from 'ng-zorro-antd/modal';
import { DevSettingsService } from '../../services/dev-settings.service';

@Component({
  selector: 'app-sdl-preview',
  imports: [NzModalModule, NzButtonModule, NzCodeEditorModule, FormsModule],
  templateUrl: './sdl-preview.component.html',
  styleUrl: './sdl-preview.component.less',
})
export class SdlPreviewComponent {
  modal = inject(NzModalRef);
  settings = inject(DevSettingsService);
  readonly sdl: string = inject(NZ_MODAL_DATA);
}
