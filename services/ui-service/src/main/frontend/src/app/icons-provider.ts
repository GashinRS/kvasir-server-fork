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
  UserOutline,
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
  QuestionCircleFill,
  QuestionCircleOutline,
  UserOutline,
  ReadOutline,
  SignatureOutline,
  DeleteOutline,
  StopOutline,
  KeyOutline,
  CrownOutline,
];

export function provideIcons(): EnvironmentProviders {
  return provideNzIcons(icons);
}
