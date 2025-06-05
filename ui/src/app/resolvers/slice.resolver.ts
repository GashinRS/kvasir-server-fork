import { inject } from '@angular/core';
import { ResolveFn } from '@angular/router';
import { KvasirService } from '../services/kvasir.service';
import { Slice } from '../types';

export const sliceResolver: ResolveFn<Slice> = (route, state) => {
  let kvasir = inject(KvasirService);
  const sliceId = route.paramMap.get('sliceId')!;
  return kvasir.getSlice(sliceId);
};
