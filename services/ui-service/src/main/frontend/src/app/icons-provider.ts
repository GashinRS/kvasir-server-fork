import { EnvironmentProviders, importProvidersFrom } from '@angular/core';
import {
  FolderOutline,
  QuestionCircleFill,
  QuestionCircleOutline,
  ControlOutline,
  ControlFill,
  HistoryOutline,
  IdcardOutline,
  EditOutline,
  DeleteOutline,
  CodeOutline,
} from '@ant-design/icons-angular/icons';
import { NzIconModule, provideNzIcons } from 'ng-zorro-antd/icon';

const icons = [
  QuestionCircleFill,
  QuestionCircleOutline,
  FolderOutline,
  ControlFill,
  ControlOutline,
  HistoryOutline,
  IdcardOutline,
  EditOutline,
  DeleteOutline,
  CodeOutline,
];

export function provideIcons(): EnvironmentProviders {
  return provideNzIcons(icons);
}
