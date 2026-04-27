import { Routes } from '@angular/router';
import { sessionActiveGuard } from './guards';
import { podResolver } from './resolvers/pod.resolver';
import {
  aliasTagsResolver,
  sliceResolver,
  taggedSliceResolver,
} from './resolvers/slice.resolver';
import { EditMode } from './slice-edit/slice-edit.component';

export const routes: Routes = [
  {
    path: '',
    pathMatch: 'full',
    redirectTo: '/graphiql',
  },
  {
    path: 'graphiql',
    canActivate: [sessionActiveGuard],
    loadComponent: () =>
      import('./graphiql/graphiql.component').then((x) => x.GraphiqlComponent),
  },
  {
    path: 'slices',
    canActivate: [sessionActiveGuard],
    loadComponent: () =>
      import('./slices/slices.component').then((x) => x.SlicesComponent),
  },
  {
    path: 'slices/new',
    canActivate: [sessionActiveGuard],
    loadComponent: () =>
      import('./slice-new/slice-new.component').then(
        (x) => x.SliceNewComponent,
      ),
  },
  {
    path: 'slices/:sliceId/edit',
    canActivate: [sessionActiveGuard],
    resolve: {
      slice: sliceResolver,
    },
    data: {
      editMode: EditMode.EDIT_SLICE,
    },
    loadComponent: () =>
      import('./slice-edit/slice-edit.component').then(
        (x) => x.SliceEditComponent,
      ),
  },
  {
    path: 'slices/:sliceId/query',
    canActivate: [sessionActiveGuard],
    resolve: {
      slice: sliceResolver,
    },
    loadComponent: () =>
      import('./slice-query/slice-query.component').then(
        (x) => x.SliceQueryComponent,
      ),
  },
  {
    path: 'slices/:sliceId/changes',
    canActivate: [sessionActiveGuard],
    resolve: {
      slice: sliceResolver,
    },
    loadComponent: () =>
      import('./slice-changes/slice-changes.component').then(
        (x) => x.SliceChangesComponent,
      ),
  },
  {
    path: 'slices/:sliceId/changes/new',
    canActivate: [sessionActiveGuard],
    resolve: { slice: sliceResolver },
    loadComponent: () =>
      import('./slice-change-new/slice-change-new.component').then(
        (x) => x.SliceChangeNewComponent,
      ),
  },
  {
    path: 'slices/:sliceId/tags',
    canActivate: [sessionActiveGuard],
    resolve: { slice: sliceResolver },
    loadComponent: () =>
      import('./slice-tags/slice-tags.component').then(
        (x) => x.SliceTagsComponent,
      ),
  },
  {
    path: 'slices/:sliceId/tags/:tag/fork',
    canActivate: [sessionActiveGuard],
    resolve: {
      slice: taggedSliceResolver,
      aliasTags: aliasTagsResolver,
    },
    data: {
      editMode: EditMode.FORK_TAG,
    },
    loadComponent: () =>
      import('./slice-edit/slice-edit.component').then(
        (x) => x.SliceEditComponent,
      ),
  },
  {
    path: 'slices/:sliceId/tags/:tag/edit',
    canActivate: [sessionActiveGuard],
    resolve: {
      slice: taggedSliceResolver,
      aliasTags: aliasTagsResolver,
    },
    data: {
      editMode: EditMode.EDIT_TAG,
    },
    loadComponent: () =>
      import('./slice-edit/slice-edit.component').then(
        (x) => x.SliceEditComponent,
      ),
  },
  {
    path: 's3',
    canActivate: [sessionActiveGuard],
    loadComponent: () => import('./s3/s3.component').then((x) => x.S3Component),
  },
  {
    path: 's3/:prefixRaw',
    canActivate: [sessionActiveGuard],
    loadComponent: () => import('./s3/s3.component').then((x) => x.S3Component),
  },
  {
    path: 'changes',
    canActivate: [sessionActiveGuard],
    loadComponent: () =>
      import('./changes/changes.component').then((x) => x.ChangesComponent),
  },
  {
    path: 'changes/view/:changeReportId',
    canActivate: [sessionActiveGuard],
    loadComponent: () =>
      import('./change/change.component').then((x) => x.ChangeComponent),
  },
  {
    path: 'changes/new',
    canActivate: [sessionActiveGuard],
    loadComponent: () =>
      import('./change-new/change-new.component').then(
        (x) => x.ChangeNewComponent,
      ),
  },
  {
    path: 'access-control',
    canActivate: [sessionActiveGuard],
    resolve: {
      pod: podResolver,
    },
    loadComponent: () =>
      import('./access-control/access-control.component').then(
        (x) => x.AccessControlComponent,
      ),
  },
  {
    path: 'settings',
    canActivate: [sessionActiveGuard],
    resolve: {
      pod: podResolver,
    },
    loadComponent: () =>
      import('./settings/settings.component').then((x) => x.SettingsComponent),
  },
  {
    path: 'register',
    loadComponent: () =>
      import('./register/register.component').then((x) => x.RegisterComponent),
  },
  {
    path: 'login',
    loadComponent: () =>
      import('./login/login.component').then((x) => x.LoginComponent),
  },
  {
    path: 'force-session/:podId',
    loadComponent: () =>
      import('./force-session/force-session.component').then(
        (x) => x.ForceSessionComponent,
      ),
  },
];
