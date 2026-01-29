import { EnvironmentProviders } from '@angular/core';
import {
  CodeOutline,
  ControlFill,
  ControlOutline,
  CrownOutline,
  DeleteOutline,
  EditOutline,
  FolderOutline,
  HistoryOutline,
  IdcardOutline,
  KeyOutline,
  LockOutline,
  MailOutline,
  QuestionCircleFill,
  QuestionCircleOutline,
  ReadOutline,
  SignatureOutline,
  StopOutline,
  ThunderboltOutline,
  UserOutline,
  UnlockOutline,
  ReloadOutline,
} from '@ant-design/icons-angular/icons';
import { provideNzIcons } from 'ng-zorro-antd/icon';

const icons = [
  CodeOutline,
  ControlFill,
  ControlOutline,
  DeleteOutline,
  EditOutline,
  FolderOutline,
  HistoryOutline,
  IdcardOutline,
  MailOutline,
  LockOutline,
  QuestionCircleFill,
  QuestionCircleOutline,
  UserOutline,
  ReadOutline,
  ReloadOutline,
  SignatureOutline,
  DeleteOutline,
  StopOutline,
  KeyOutline,
  CrownOutline,
  ThunderboltOutline,
  UnlockOutline,
];

export function provideIcons(): EnvironmentProviders {
  return provideNzIcons(icons);
}
