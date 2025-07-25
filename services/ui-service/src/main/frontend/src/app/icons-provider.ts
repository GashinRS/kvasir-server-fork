import { EnvironmentProviders, importProvidersFrom } from '@angular/core';
import {
  FolderOutline,
  QuestionCircleFill,
  QuestionCircleOutline,
  ControlOutline,
  ControlFill,
} from '@ant-design/icons-angular/icons';
import { NzIconModule } from 'ng-zorro-antd/icon';

const icons = [
  QuestionCircleFill,
  QuestionCircleOutline,
  FolderOutline,
  ControlFill,
  ControlOutline,
];

export function provideNzIcons(): EnvironmentProviders {
  return importProvidersFrom(NzIconModule.forRoot(icons));
}
