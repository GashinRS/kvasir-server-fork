import { inject, Injectable } from '@angular/core';
import { editor } from 'monaco-editor';
import { CodeEditorConfig, NzConfigService } from 'ng-zorro-antd/core/config';

@Injectable({
  providedIn: 'root',
})
export class DevSettingsService {
  private readonly MONACO_OPTS_GRAPHQL = {
    language: 'graphql',
    automaticLayout: true,
    autoIndent: 'brackets',
    lineNumbers: 'off',
    minimap: {
      enabled: false,
    },
    roundedSelection: false,
    renderLineHighlight: 'none',
  } as editor.IStandaloneEditorConstructionOptions;

  private readonly MONACO_OPTS_JSON = {
    language: 'json',
    automaticLayout: true,
    autoIndent: 'brackets',
    lineNumbers: 'off',
    minimap: {
      enabled: false,
    },
    fontSize: 13,
    roundedSelection: false,
    renderLineHighlight: 'none',
    // links: false,
  } as editor.IStandaloneEditorConstructionOptions;

  private readonly opts = {
    graphql: this.MONACO_OPTS_GRAPHQL,
    graphql_readonly: { ...this.MONACO_OPTS_GRAPHQL, ...{ readOnly: true } },
    json: this.MONACO_OPTS_JSON,
    json_readonly: { ...this.MONACO_OPTS_JSON, ...{ readOnly: true } },
  } as const;

  constructor() {
    const cfg = inject(NzConfigService);
    // FIXME: Hack for 0.52.0 version of Monaco Editor (normally relative urls work and this is not needed)
    cfg.set('codeEditor', {
      assetsRoot: window.location.origin + '/_ui/assets',
    } as CodeEditorConfig);
  }

  getCodeEditorSetting() {
    return this.opts;
  }
}
