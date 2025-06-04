import { EnvironmentProviders, importProvidersFrom } from '@angular/core';
import {
  FolderOutline,
  QuestionCircleFill,
  QuestionCircleOutline,
} from '@ant-design/icons-angular/icons';
import { NzIconModule } from 'ng-zorro-antd/icon';

const icons = [QuestionCircleFill, QuestionCircleOutline, FolderOutline];

export function provideNzIcons(): EnvironmentProviders {
  return importProvidersFrom(NzIconModule.forRoot(icons));
}
