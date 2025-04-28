{{/*
Expand the name of the chart.
*/}}
{{- define "quarkus-template.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
We truncate at 63 chars because some Kubernetes name fields are limited to this (by the DNS naming spec).
If release name contains chart name it will be used as a full name.
*/}}
{{- define "quarkus-template.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "quarkus-template.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "quarkus-template.labels" -}}
helm.sh/chart: {{ include "quarkus-template.chart" . }}
{{ include "quarkus-template.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "quarkus-template.selectorLabels" -}}
app.kubernetes.io/name: {{ include "quarkus-template.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
The image reference for the Quarkus application
*/}}
{{- define "quarkus-template.image" }}
{{- $registry := .Values.image.registry | default .Values.global.image.registry }}
{{- $name := .Values.image.name | default .Chart.Name }}
{{- $tag := .Values.image.tag | default .Values.global.image.tag | default .Chart.AppVersion }}
{{- printf "%s/%s:%s" $registry $name $tag }}
{{- end }}

{{- define "quarkus-template.oidc.envConfig" -}}
- name: QUARKUS_OIDC_AUTH_SERVER_URL
  value: {{ .Values.global.oidc.authServerUrl | quote }}
{{- if and .Values.global.oidc.existingSecret .Values.global.oidc.clientIdSecretKey }}
- name: QUARKUS_OIDC_CLIENT_ID
  valueFrom:
    secretKeyRef:
      name: {{ .Values.global.oidc.existingSecret }}
      key: {{ .Values.global.oidc.clientIdSecretKey }}
{{- else if .Values.global.oidc.clientId }}
- name: QUARKUS_OIDC_CLIENT_ID
  value: {{ .Values.global.oidc.clientId | quote }}
{{- end }}
{{- if and .Values.global.oidc.existingSecret .Values.global.oidc.clientSecretSecretKey }}
- name: QUARKUS_OIDC_CREDENTIALS_SECRET
  valueFrom:
    secretKeyRef:
      name: {{ .Values.global.oidc.existingSecret }}
      key: {{ .Values.global.oidc.clientSecretSecretKey }}
{{- else if .Values.global.oidc.clientSecret }}
- name: QUARKUS_OIDC_CREDENTIALS_SECRET
  value: {{ .Values.global.oidc.clientSecret | quote }}
{{- end }}
{{- end }}

{{- define "quarkus-template.oidc.envConfig.additional" -}}
{{- end }}

{{- define "quarkus-template.oidc.keycloakAdmin.envConfig" -}}
- name: QUARKUS_KEYCLOAK_ADMIN_CLIENT_ENABLED
  value: {{ .Values.oidc.keycloakAdmin.enabled | quote }}
- name: QUARKUS_KEYCLOAK_ADMIN_CLIENT_SERVER_URL
  value: {{ .Values.global.oidc.authServerUrl | quote }}
- name: QUARKUS_KEYCLOAK_ADMIN_CLIENT_GRANT_TYPE
  value: "password"
- name: QUARKUS_KEYCLOAK_ADMIN_CLIENT_REALM
  value: {{ .Values.global.oidc.keycloakAdmin.realm | quote }}
{{- if and .Values.global.oidc.keycloakAdmin.existingSecret .Values.global.oidc.keycloakAdmin.usernameSecretKey }}
- name: QUARKUS_KEYCLOAK_ADMIN_CLIENT_USERNAME
  valueFrom:
    secretKeyRef:
      name: {{ .Values.global.oidc.keycloakAdmin.existingSecret }}
      key: {{ .Values.global.oidc.keycloakAdmin.usernameSecretKey }}
{{- else if .Values.global.oidc.keycloakAdmin.username }}
- name: QUARKUS_KEYCLOAK_ADMIN_CLIENT_USERNAME
  value: {{ .Values.global.oidc.keycloakAdmin.username | quote }}
{{- end }}

{{- if and .Values.global.oidc.keycloakAdmin.existingSecret .Values.global.oidc.keycloakAdmin.passwordSecretKey }}
- name: QUARKUS_KEYCLOAK_ADMIN_CLIENT_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ .Values.global.oidc.keycloakAdmin.existingSecret }}
      key: {{ .Values.global.oidc.keycloakAdmin.passwordSecretKey }}
{{- else if .Values.global.oidc.keycloakAdmin.password }}
- name: QUARKUS_KEYCLOAK_ADMIN_CLIENT_PASSWORD
  value: {{ .Values.global.oidc.keycloakAdmin.password | quote }}
{{- end }}

{{- end }}


{{- define "quarkus-template.kafka.envConfig" -}}
- name: KAFKA_BOOTSTRAP_SERVERS
  value: {{ .Values.global.kafka.bootstrapServers  | quote }}
{{- end }}

{{- define "quarkus-template.kafka.envConfig.additional" -}}
{{- end }}

{{- define "quarkus-template.clickhouse.envConfig" -}}
{{- end }}

{{- define "quarkus-template.clickhouse.envConfig.additional" -}}
{{- end }}

{{- define "quarkus-template.minio.envConfig" -}}
- name: QUARKUS_MINIO_URL
  value: "{{.Values.global.minio.useTls | ternary "https" "http" }}://{{ .Values.global.minio.host }}:{{ .Values.global.minio.port }}"
- name: QUARKUS_MINIO_SECURE
  value: {{ not .Values.global.minio.skipTlsVerify | quote }}
{{- if and .Values.global.minio.existingSecret .Values.global.minio.accessKeySecretKey }}
- name: QUARKUS_MINIO_ACCESS_KEY
  valueFrom:
    secretKeyRef:
      name: {{ .Values.global.minio.existingSecret }}
      key: {{ .Values.global.minio.accessKeySecretKey }}
{{- else if .Values.global.minio.accessKey }}
- name: QUARKUS_MINIO_ACCESS_KEY
  value: {{ .Values.global.minio.accessKey | quote }}
{{- end }}
{{- if and .Values.global.minio.existingSecret .Values.global.minio.secretKeySecretKey }}
- name: QUARKUS_MINIO_SECRET_KEY
  valueFrom:
    secretKeyRef:
      name: {{ .Values.global.minio.existingSecret }}
      key: {{ .Values.global.minio.secretKeySecretKey }}
{{- else if .Values.global.minio.secretKey }}
- name: QUARKUS_MINIO_SECRET_KEY
  value: {{ .Values.global.minio.secretKey | quote }}
{{- end }}
{{- end }}

{{- define "quarkus-template.minio.envConfig.additional" -}}
{{- end }}
