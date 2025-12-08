import { inject } from '@angular/core';
import { ResolveFn } from '@angular/router';
import { KvasirService } from '../services/kvasir.service';
import { Pod, PodDetails } from '../types';

export const podResolver: ResolveFn<PodDetails> = (route, state) => {
  let kvasir = inject(KvasirService);
  return kvasir.getPod();
};
