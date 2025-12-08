import { Component, forwardRef, viewChild } from '@angular/core';
import {
  ControlValueAccessor,
  NG_VALIDATORS,
  NG_VALUE_ACCESSOR,
  Validator,
} from '@angular/forms';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzSelectComponent, NzSelectModule } from 'ng-zorro-antd/select';

type RelationOptionValue =
  | 'kss-fga:reader'
  | 'kss-fga:writer'
  | 'kss-fga:deleter'
  | 'kss-fga:blocked'
  | 'kss-fga:owner';
type RelationOptionLabel =
  | 'reader'
  | 'writer'
  | 'deleter'
  | 'blocked'
  | 'owner';
type RelationOptionGroupLabel = 'normal' | 'special' | 'elevated';

export interface RelationOption {
  value: RelationOptionValue;
  label: RelationOptionLabel;
  groupLabel: RelationOptionGroupLabel;
  icon: string;
}

const RELATIONS: RelationOption[] = [
  {
    value: 'kss-fga:reader',
    label: 'reader',
    groupLabel: 'normal',
    icon: 'read',
  },
  {
    value: 'kss-fga:writer',
    label: 'writer',
    groupLabel: 'normal',
    icon: 'signature',
  },
  {
    value: 'kss-fga:deleter',
    label: 'deleter',
    groupLabel: 'normal',
    icon: 'delete',
  },
  {
    value: 'kss-fga:blocked',
    label: 'blocked',
    groupLabel: 'special',
    icon: 'stop',
  },
  {
    value: 'kss-fga:owner',
    label: 'owner',
    groupLabel: 'elevated',
    icon: 'crown',
  },
];

@Component({
  selector: 'app-select-relation',
  imports: [NzSelectModule, NzIconModule],
  templateUrl: './select-relation.component.html',
  styleUrl: './select-relation.component.less',
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      multi: true,
      useExisting: forwardRef(() => SelectRelationComponent),
    },
  ],
})
export class SelectRelationComponent implements ControlValueAccessor {
  nzSelect = viewChild.required(NzSelectComponent);

  relations = RELATIONS.reduce(
    (prev, curr) => {
      prev[curr.groupLabel].push(curr);
      return prev;
    },
    { normal: [], special: [], elevated: [] } as Record<
      'normal' | 'special' | 'elevated',
      RelationOption[]
    >,
  );
  selected: RelationOption | null = null;

  private touched = false;
  private disabled = false;
  private onChange = (obj: RelationOptionValue) => {};
  private onTouched = () => {};

  getRelationIcon(
    selected: 'reader' | 'writer' | 'deleter' | 'blocked' | 'owner',
  ): string {
    switch (selected) {
      case 'reader':
        return 'read';
      case 'writer':
        return 'signature';
      case 'deleter':
        return 'delete';
      case 'blocked':
        return 'stop';
      case 'owner':
        return 'crown';
      default:
        return 'question-circle';
    }
  }

  registerOnChange(fn: any): void {
    this.nzSelect().onChange = fn;
  }

  registerOnTouched(fn: any): void {
    this.nzSelect().onTouched = fn;
  }

  writeValue(obj: RelationOptionValue | null): void {
    // if (obj != null) {
    this.nzSelect().writeValue(obj);
    // this.selected = RELATIONS.find((rel) => rel.value == obj) ?? null;
    // }
  }

  setDisabledState(isDisabled: boolean): void {
    this.nzSelect().setDisabledState(isDisabled);
  }
}
