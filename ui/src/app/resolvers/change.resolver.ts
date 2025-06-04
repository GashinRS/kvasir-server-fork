import { inject } from '@angular/core';
import { ResolveFn } from '@angular/router';
import { KvasirService } from '../services/kvasir.service';
import { ChangeReport } from '../types';

export const changeReportResolver: ResolveFn<ChangeReport> = (route, state) => {
  let kvasir = inject(KvasirService);
  const changeReportId = route.paramMap.get('changeReportId')!;
  return kvasir.getChangeReport(changeReportId);
};
