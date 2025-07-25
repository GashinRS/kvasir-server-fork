import {
  AfterViewInit,
  Component,
  ElementRef,
  inject,
  input,
  OnDestroy,
  signal,
} from '@angular/core';
import type { Theme } from '@graphiql/react';
import {
  CreateFetcherOptions,
  createGraphiQLFetcher,
  Storage,
} from '@graphiql/toolkit';
import GraphiQL from 'graphiql';
import { createClient } from 'graphql-sse';
import Keycloak from 'keycloak-js';
import React from 'react';
import { createRoot, Root } from 'react-dom/client';
import { ErrorHandlerService } from '../../services/error-handler.service';
import { KvasirError } from '../error/error.component';

@Component({
  selector: 'app-graphiql-editor',
  imports: [],
  template: ``,
  styles: `
    :host {
      height: 100%;
    }
  `,
})
export class GraphiqlEditorComponent implements AfterViewInit, OnDestroy {
  // Inputs
  queryEndpoint = input.required<URL>();
  historyKey = input<string>();
  theme = input<Theme>(null);

  // DI
  private keycloak = inject(Keycloak);
  private elRef = inject(ElementRef);
  private errorHandler = inject(ErrorHandlerService);

  // Vars
  private root?: Root;
  private element?: React.FunctionComponentElement<any>;
  private token = signal<string | undefined>(this.keycloak.token);

  constructor() {
    const that = this;
    this.keycloak.onAuthRefreshSuccess = function () {
      that.token.set(this.token);
    };
  }

  ngAfterViewInit(): void {
    // Options
    const forcedTheme = this.theme() ?? 'system';
    const historyKey = this.historyKey();

    const fetcher = this.createFetcher();
    const storage = historyKey ? this.createStorage(historyKey) : undefined;
    if (this.root == undefined) {
      this.root = createRoot(this.elRef.nativeElement, {
        onRecoverableError: (err, info) => {
          console.log(err);
          console.log(info);
        },
      });
    }
    if (this.element == undefined) {
      this.element = React.createElement(GraphiQL, {
        fetcher,
        forcedTheme,
        storage,
      });
    }
    this.root.render(this.element);
  }

  ngOnDestroy(): void {
    this.root?.unmount();
    this.element = undefined;
  }

  private createFetcher = () => {
    const options: CreateFetcherOptions = {
      url: this.queryEndpoint().toString(),
      enableIncrementalDelivery: false,
      wsClient: this.createSseClient(),
      fetch: this.authedFetch(),
    };
    return createGraphiQLFetcher(options);
  };

  /**
   * Fetch patch so that it will always use the latest refreshed token.
   * @returns
   */
  private authedFetch() {
    const myFetch = (input: string | URL | Request, init?: RequestInit) => {
      const headers = { Authorization: `Bearer ${this.token()}` };
      if (init) {
        init.headers = {
          ...init.headers,
          ...headers,
        };
      } else {
        init = { headers };
      }
      return fetch(input, init).then(async (res) => {
        if (res.status >= 400) {
          const err = await this.errorHandler.mapResponseToKvasirError(res);
          this.errorHandler.showInModal(err);
        }
        return res;
      });
    };
    return myFetch;
  }

  private createSseClient = () => {
    return createClient({
      url: this.queryEndpoint().toString(),
      fetchFn: this.authedFetch(),
      /* If you have an HTTP/2 server, it is recommended to use the client in "distinct connections mode" (singleConnection = false) which will create a new SSE connection for each subscribe. This is the default.
      However, when dealing with HTTP/1 servers from a browser, consider using the "single connection mode" (singleConnection = true) which will use only one SSE connection.*/
      singleConnection: false,
    });
  };

  private createStorage = (prefix: string): Storage => {
    const stor = localStorage;
    const pre = (key?: string) => `${prefix}#${key}`;
    const countPrefixedKeys = () => {
      let count = 0;
      for (let i = 0; i < stor.length; i++) {
        if (stor.key(i)?.startsWith(pre())) {
          count++;
        }
      }
      return count;
    };

    return {
      clear: () => {
        for (let i = 0; i < stor.length; i++) {
          if (stor.key(i)?.startsWith(pre())) {
            stor.removeItem(stor.key(i)!);
          }
        }
      },
      getItem: (key: string) => stor.getItem(pre(key)),
      removeItem: (key: string) => stor.removeItem(pre(key)),
      setItem: (key: string, value: string) => stor.setItem(pre(key), value),
      length: countPrefixedKeys(),
    };
  };
}
