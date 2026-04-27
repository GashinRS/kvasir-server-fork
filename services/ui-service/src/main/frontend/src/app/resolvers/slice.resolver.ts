import { inject } from '@angular/core';
import { ResolveFn } from '@angular/router';
import { map } from 'rxjs/operators';
import { KvasirService } from '../services/kvasir.service';
import { Slice } from '../types';
import { of } from 'rxjs';

export const sliceResolver: ResolveFn<Slice> = (route, state) => {
  let kvasir = inject(KvasirService);
  const sliceId = route.paramMap.get('sliceId')!;
  return kvasir.getSlice(sliceId);
};

export const taggedSliceResolver: ResolveFn<Slice> = (route, state) => {
  let kvasir = inject(KvasirService);
  const sliceId = route.paramMap.get('sliceId')!;
  const tag = route.paramMap.get('tag')!;
  return kvasir.getTaggedSlice(sliceId, tag);
};

export const aliasTagsResolver: ResolveFn<string[]> = (route, state) => {
  let kvasir = inject(KvasirService);
  const sliceId = route.paramMap.get('sliceId')!;
  const tag = route.paramMap.get('tag')!;
  const revisionId = route.queryParamMap.get('revisionId');
  return revisionId
    ? kvasir
        .getSameRevisionTags(sliceId, revisionId)
        .pipe(map((tags) => tags.map((tag) => tag['kss:tag']!)))
    : of([tag]);
};
